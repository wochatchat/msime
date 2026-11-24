package app.msime.android;

/**
 * Google 浏览器登录（RFC 8252 回环回跳，{@link GoogleBrowserSignIn}）的纯判定：回跳页的返回链接和等待结束时的那句话。不依赖 Android，`check-host.sh` 的 JVM 冒烟 `tests/account/GoogleBrowserPolicySmoke.java` 直接调用。
 */
public final class GoogleBrowserPolicy {
    private GoogleBrowserPolicy() {}

    /**
     * 回跳页上「回到水杉输入法」按钮的链接：`AuthRedirectActivity` 的 `/google` intent-filter 接住它，把应用带回前台；登录结果本身走本机回环，不经过这个链接。形状要过 Rust `valid_return_link` 的检查（小写 scheme、路径只有字母数字和 `/-_.`），包名本来就满足。
     */
    public static String returnLink(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        return packageName + "://auth/google";
    }

    /** 等不到回跳（超时、用户关掉浏览器标签页）或用户在 Google 页面拒绝时的话：与 Credential Manager 那边用户取消的口径一致。 */
    public static String cancelledMessage() { return "已取消 Google 登录"; }

    /** 打不开浏览器时的话，与 Apple 网页登录的同一句话同口径。 */
    public static String noBrowserMessage() { return "这台设备上没有可以打开 Google 登录页的浏览器"; }
}
