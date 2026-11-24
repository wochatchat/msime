package app.msime.android.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import app.msime.android.ScreenCaptureProtection;
import app.msime.android.AppleWebSignIn;
import app.msime.android.ThreadPolicy;

/**
 * 网页登录的回跳：Apple 的回调 `<applicationId>://auth/apple?grant=…`（或 `?error=…`），以及 Google 浏览器登录回跳页的「回到水杉输入法」按钮 `${applicationId}://auth/google`（结果走本机回环，这里只负责把应用带回前台）。
 *
 * <p>没有界面（`Theme.NoDisplay`），在 `onCreate` 里处理完就 `finish()`。先只看回调地址的形状（{@link AppleWebSignIn#acceptableCallback}），不对就丢弃、不碰 `msime_auth_pending`；再看有没有本应用刚发起、未过期的那次流程，没有也丢弃。恶意应用伪造的回调没有对应的 verifier，换不到会话；服务端以「grant 无效」拒绝时等待中的流程原样保留，真正的回调随后仍能完成（见 {@link SignIn#completeApple}）。兑换在工作线程上串行进行，结果交给仍在等待的登录面板；登录成功不触发同步。
 */
public final class AuthRedirectActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 「防止截屏」（#10098）：窗口显示前按本地设置加上 FLAG_SECURE。
        ScreenCaptureProtection.apply(this);
        handle(getIntent());
        finish();
    }

    private void handle(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !getPackageName().equals(uri.getScheme()) || !"auth".equals(uri.getHost())) return;
        if ("/google".equals(uri.getPath())) {
            // Google 浏览器登录的结果走本机回环（GoogleBrowserSignIn），这里只是回跳页上「回到水杉输入法」按钮的落点，把应用带回前台就够了。
            bringToFront();
            return;
        }
        if (!"/apple".equals(uri.getPath())) return;
        String grant = uri.getQueryParameter("grant");
        String error = uri.getQueryParameter("error");
        // 形状不对的回调不碰等待中的流程，免得任何应用随手发一个 VIEW 就打断正在进行的登录。
        if (!AppleWebSignIn.acceptableCallback(grant, error)) return;
        Context context = getApplicationContext();
        if (AppleWebSignIn.peekPending(context) == null) return;
        ThreadPolicy.startNamedThread("msime-apple-sign-in",
            () -> SignIn.completeApple(context, grant, error));
        bringToFront();
    }

    /** 浏览器里的落地页跳回来时，这个透明的 Activity 可能落在浏览器的任务里；把本应用已有的任务带回前台，登录面板就在那里等结果。 */
    private void bringToFront() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        }
    }
}
