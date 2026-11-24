import app.msime.android.GoogleBrowserPolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class GoogleBrowserPolicySmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String readHead(java.net.Socket socket, long deadline, int maxBytes) throws Exception {
        // GoogleBrowserSignIn.readHead 是包私有的静态方法，签名与 JVM 可运行性都在这里钉住。
        var method = Class.forName("app.msime.android.GoogleBrowserSignIn")
            .getDeclaredMethod("readHead", java.net.Socket.class, long.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, socket, deadline, maxBytes);
    }

    public static void main(String[] args) throws Exception {
        check(GoogleBrowserPolicy.returnLink("app.msime.android").equals("app.msime.android://auth/google"),
            "return link carries the caller's applicationId");
        check(GoogleBrowserPolicy.returnLink("app.msime.android.pinyin").equals("app.msime.android.pinyin://auth/google"),
            "edition package names work the same");
        check(GoogleBrowserPolicy.returnLink(null).isEmpty() && GoogleBrowserPolicy.returnLink("").isEmpty(),
            "no package name means no return link");
        // Rust valid_return_link 的形状：小写 scheme、路径只有字母数字和 /-_.
        String link = GoogleBrowserPolicy.returnLink("app.msime.android");
        check(link.matches("[a-z][a-z0-9+.-]*://[A-Za-z0-9/._-]+"), "link passes the Rust shape check: " + link);
        check(!link.startsWith("http"), "return link is not a web scheme");

        check("已取消 Google 登录".equals(GoogleBrowserPolicy.cancelledMessage()),
            "cancel wording matches the Credential Manager cancellation");
        check(GoogleBrowserPolicy.noBrowserMessage().contains("浏览器"), "no-browser message names the browser");

        // readHead：完整请求头返回原文；不完整、超限、超时都返回 null（Rust 按 unknown 回 404 页）。
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
                try (Socket conn = server.accept()) {
                    client.getOutputStream().write("GET /callback?code=x&state=s HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                    String head = readHead(conn, System.currentTimeMillis() + 5000, 8192);
                    check(head != null && head.startsWith("GET /callback?code=x&state=s HTTP/1.1\r\n"),
                        "complete request head is returned verbatim");
                    check(head.endsWith("\r\n\r\n"), "head ends at the blank line");
                }
            }
            try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
                try (Socket conn = server.accept()) {
                    // 只发半个请求头：到时限为止没有 \r\n\r\n，按没读到处理。
                    client.getOutputStream().write("GET /callback HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                    String head = readHead(conn, System.currentTimeMillis() + 300, 8192);
                    check(head == null, "an incomplete head is not a head");
                }
            }
            try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
                try (Socket conn = server.accept()) {
                    String huge = "G " + "x".repeat(9000) + " HTTP/1.1\r\n\r\n";
                    client.getOutputStream().write(huge.getBytes(StandardCharsets.US_ASCII));
                    String head = readHead(conn, System.currentTimeMillis() + 3000, 8192);
                    check(head == null, "a head over the size limit is refused: " + (head == null ? "null" : head.length()));
                }
            }
        }
        System.out.println("GoogleBrowserPolicySmoke passed");
    }
}
