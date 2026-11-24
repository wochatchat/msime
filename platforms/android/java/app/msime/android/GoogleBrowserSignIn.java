package app.msime.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Google 登录的浏览器回环流程（RFC 8252），与桌面端 `AccountSession::sign_in_google_with_browser`、鸿蒙的 `HarmonyGoogleSignIn` 是同一条链路：后端持有 OAuth client 和 PKCE verifier，本机在 127.0.0.1 上监听，系统浏览器打开后端生成的授权页，Google 跳回本机端口，授权码交给 {@link SignIn#completeGoogleBrowser} 换会话。
 *
 * <p>为什么需要它：Credential Manager 的 ID token 流程要求调用方的包名与签名证书登记进 Google Cloud 的 Android OAuth client——fork 自签的安装包没有登记（状态码 28444），不带 Play services 的设备连凭据提供方都没有。回环流程里授权代理是真正的浏览器，Google 不校验本包签名，任何签名的安装包都能用。回跳地址的形状、授权链接能不能打开、一次回环请求是不是回跳、写回浏览器的页面，都在 Rust 的 `msime_client_google_loopback`（{@link NativeClient#googleLoopback}）里，与桌面和鸿蒙共用一份判定；这里只做 socket、浏览器和等待。
 *
 * <p>只支持登录：后端的回环 challenge 不带 link，关联流程继续走 Credential Manager。新的一次登录让上一次以取消结束（按代次丢弃结果）。冒烟测试覆盖 {@link #readHead} 与 {@link GoogleBrowserPolicy}。
 */
public final class GoogleBrowserSignIn {
    private static final String TAG = "MSIMEGoogleBrowser";
    /** accept 轮询间隔：等待回跳时要按 deadline 与代次反复醒来。 */
    private static final int POLL_MILLIS = 500;
    /** 打开浏览器的 UI 任务最长等这么久；超时按打不开处理。 */
    private static final long OPEN_WAIT_MILLIS = 2000;

    /** 一次登录的完成：回跳带来的授权码和它所属的 challenge。 */
    public record Completed(String challengeId, String code) {}

    /** 收到授权码或以失败结束时交回，都在开始这次登录的工作线程上。 */
    public interface Listener {
        void onCode(Completed completed);

        void onFailure(String message);
    }

    /** 一次回跳的结算：拿到授权码，或以某个 `account_*` 错误码结束。 */
    private record Callback(String code, String error) {}

    /** 新的一次登录会让上一次以取消结束；读和写都过这把锁。 */
    private static final Object GENERATION_LOCK = new Object();
    private static int generation;

    private GoogleBrowserSignIn() {}

    /**
     * 完成一次 Google 浏览器登录。阻塞（几分钟上限，见 plan 的 wait_ms），不要在主线程调用；浏览器在主线程上打开。新的一次会让还在等回跳的上一次以取消结束。
     */
    public static void signIn(Activity activity, Listener listener) {
        final int mine;
        synchronized (GENERATION_LOCK) {
            mine = ++generation;
        }
        try {
            signInOnce(activity, listener, mine);
        } catch (OperationCanceledException superseded) {
            listener.onFailure(GoogleBrowserPolicy.cancelledMessage());
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "google browser sign-in failed", error);
            listener.onFailure(SignInFailurePolicy.login(AccountStore.code(error)));
        }
    }

    private static void signInOnce(Activity activity, Listener listener, int mine) throws Exception {
        Context context = activity.getApplicationContext();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // 回跳地址的形状由 Rust 给出（target），Java 不自己拼。
            JSONObject targetReply = new JSONObject(NativeClient.googleLoopback(
                new JSONObject().put("operation", "target").put("port", server.getLocalPort()).toString()));
            String target = JsonPolicy.strictStringOrEmpty(targetReply.opt("target"));
            if (target.isEmpty()) throw new AccountStore.Failure("account_unavailable", 0);
            // 授权链接由后端生成、Rust 校验过指向这个回跳地址；后端持有 client secret 与 verifier，这里只转发授权码。
            BackendAccount.LoopbackChallenge challenge = new BackendAccount(context).googleLoopbackChallenge(target);
            JSONObject plan = new JSONObject(NativeClient.googleLoopback(new JSONObject()
                .put("operation", "plan")
                .put("authorization_url", challenge.authorizationUrl())
                .put("target", target)
                .put("expires_in", challenge.expiresIn())
                .toString()));
            String state = JsonPolicy.strictStringOrEmpty(plan.opt("state"));
            long waitMillis = plan.optLong("wait_ms");
            int maxRequestBytes = plan.optInt("max_request_bytes", 8192);
            int ioTimeoutMillis = plan.optInt("io_timeout_ms", 2000);
            if (state.isEmpty() || waitMillis <= 0) throw new AccountStore.Failure("account_unavailable", 0);
            if (mine != generation()) throw new OperationCanceledException();
            // 先开浏览器再等回跳不行：回跳来得快就会没人接。监听已经挂好，打开授权页后马上进 receive。
            if (!openBrowser(activity, challenge.authorizationUrl())) {
                listener.onFailure(GoogleBrowserPolicy.noBrowserMessage());
                return;
            }
            Callback callback = receive(server, state, System.currentTimeMillis() + waitMillis, mine,
                maxRequestBytes, ioTimeoutMillis, GoogleBrowserPolicy.returnLink(context.getPackageName()));
            if (callback.error() != null) listener.onFailure("account_cancelled".equals(callback.error())
                ? GoogleBrowserPolicy.cancelledMessage() : SignInFailurePolicy.login(callback.error()));
            else listener.onCode(new Completed(challenge.challengeId(), callback.code()));
        }
    }

    private static int generation() {
        synchronized (GENERATION_LOCK) {
            return generation;
        }
    }

    private static final class OperationCanceledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 在主线程上把授权页交给系统浏览器（与 `LinkActions.openBrowsable` 同一个意图形状）。授权代理必须是真正的浏览器：Google 拒绝应用内 WebView。 */
    private static boolean openBrowser(Activity activity, String url) throws InterruptedException {
        AtomicBoolean opened = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
                opened.set(true);
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "no browser for google sign-in");
            } finally {
                done.countDown();
            }
        });
        done.await(OPEN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        return opened.get();
    }

    /**
     * 等回跳：accept 轮询里查 deadline 与代次；每个连接读完请求头交给 Rust 判定，把它的页面写回浏览器。回跳带来的授权码或回跳里的 `error` 原样返回；超时或被新的一次登录取代时按取消结束（与桌面端一致）。
     */
    private static Callback receive(ServerSocket server, String state, long deadline, int mine,
            int maxRequestBytes, int ioTimeoutMillis, String returnLink) throws IOException {
        while (System.currentTimeMillis() < deadline && mine == generation()) {
            long remaining = deadline - System.currentTimeMillis();
            server.setSoTimeout(remaining > POLL_MILLIS ? POLL_MILLIS : (int) Math.max(1, remaining));
            try (Socket socket = server.accept()) {
                Callback outcome = answer(socket, state, deadline, maxRequestBytes, ioTimeoutMillis, returnLink);
                if (outcome != null) return outcome;
            } catch (SocketTimeoutException expected) {
                continue;
            }
        }
        return new Callback(null, "account_cancelled");
    }

    /** 处理一个回环连接。state 对上的回跳返回结果；别人的请求（`/favicon.ico` 之类）、读不出形状的连接或 Rust 拒绝的请求回答它的页面（没有页面时静默关闭）并返回 null，监听继续。 */
    private static Callback answer(Socket socket, String state, long deadline, int maxRequestBytes,
            int ioTimeoutMillis, String returnLink) {
        String head = readHead(socket, Math.min(deadline, System.currentTimeMillis() + ioTimeoutMillis), maxRequestBytes);
        JSONObject reply = null;
        try {
            reply = new JSONObject(NativeClient.googleLoopback(new JSONObject()
                .put("operation", "reply")
                .put("head", head == null ? JSONObject.NULL : head)
                .put("state", state)
                .put("return_link", returnLink)
                .toString()));
        } catch (JSONException | RuntimeException error) {
            Log.w(TAG, "loopback request not understood");
        }
        if (reply == null) return null;
        // 页面只是告知；结果不取决于它有没有送达。
        page(socket, JsonPolicy.strictStringOrEmpty(reply.opt("response")));
        String outcome = JsonPolicy.strictStringOrEmpty(reply.opt("outcome"));
        if ("code".equals(outcome)) return new Callback(JsonPolicy.strictStringOrEmpty(reply.opt("code")), null);
        if ("failed".equals(outcome)) return new Callback(null, JsonPolicy.strictStringOrEmpty(reply.opt("error")));
        return null;
    }

    /** 把 Rust 画好的页面写回浏览器，尽力而为。 */
    private static void page(Socket socket, String response) {
        if (response.isEmpty()) return;
        try (OutputStream out = socket.getOutputStream()) {
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** 读一个请求头：读到 `\r\n\r\n` 为止，到达头大小上限或时限时放弃。没读完整的请求头返回 null（Rust 按 unknown 处理，回 404 页），与桌面端 `read_request_head` 的口径一致。请求头是 ASCII，用 ISO-8859-1 保字节拼串。 */
    static String readHead(Socket socket, long deadline, int maxBytes) {
        try {
            StringBuilder head = new StringBuilder(1024);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[1024];
            while (head.length() < maxBytes && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                socket.setSoTimeout((int) Math.min(200, Math.max(1, remaining)));
                int read;
                try {
                    read = in.read(buffer);
                } catch (SocketTimeoutException expected) {
                    continue;
                }
                if (read < 0) break;
                head.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                if (head.indexOf("\r\n\r\n") >= 0) return head.toString();
            }
            return null;
        } catch (IOException error) {
            return null;
        }
    }
}
