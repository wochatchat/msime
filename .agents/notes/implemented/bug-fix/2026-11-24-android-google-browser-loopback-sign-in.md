# Android Google 登录加浏览器回环回退（2026-11-24）

## 问题

fork 自签的安装包（CI 测试包）Google 登录必失败：Credential Manager 的 `GetSignInWithGoogleOption` 要求调用方的包名与签名证书登记进后端 Google Cloud 项目（237074057301）的 Android OAuth client；fork 的 keystore SHA-1 没登记，Play services 报 28444。不带 Play services 的设备连凭据提供方都没有，Google 按钮同样是死路。用户无法在别人的 Google 项目里登记自己的证书指纹，所以「登记 SHA-1」不可行。

## 方案

加浏览器回环登录（RFC 8252）作回退：本机在 127.0.0.1 随机端口监听，系统浏览器打开后端生成的授权页，Google 跳回本机端口，授权码交给后端换会话。授权代理是真正的浏览器，Google 不校验调用 app 的签名，任何签名的安装包都能用——桌面（`AccountSession::sign_in_google_with_browser`）和鸿蒙（`HarmonyGoogleSignIn`）已经在用同一条链路，后端无需任何改动。

- 判定共用：`msime_client_google_loopback` 的 target/plan/reply 三个操作本来就在 libmsimeclient.so 里（鸿蒙在用），安卓侧只加了 JNI 桥（`NativeClient.googleLoopback`），回跳地址形状、授权链接校验、state 比对、回跳页 HTML 全部复用 Rust 的一份。
- 触发点：`SignIn.startGoogle` 的 Credential Manager `onFailure`（`purpose=login`）→ `startGoogleBrowser`。用户自己关掉账号选择走新增的 `onCancelled`，不开浏览器。回环 challenge 后端不支持 link，关联流程原样报错。
- 完成：回跳码经 `SignIn.completeGoogleBrowser`（`google_code_login` + `bind(context,"google")`），与 ID token 路径共用绑定锁与绑定时机。
- 回跳页「回到水杉输入法」：`AuthRedirectActivity` 加 `/google` intent-filter，收到只把应用带回前台；结果不走这个链接。
- 界面结果：`googleBrowserWaiter` 按消费者身份结算；面板 dismiss（`LoginSheet.googleResult`）或重新发起登录后，晚到的结果不再打扰，登录本身仍会完成（与 Apple 网页登录同口径）。

## 弃选

- **在 fork 侧换自己的 Google Cloud 项目**：ID token 的 `aud` 会变成自己的 web client，后端 `msime-cloud` 的校验器只认它自己配置的 client_ids，改不了上游后端就通不了。
- **回环流程做主路径**：上游签名包的 Credential Manager 体验更好（原生账号选择）；留着做首选，失败才回退。
- **WebView 打开授权页**：Google 明确拒绝嵌入式浏览器。

## 冒烟

`tests/account/GoogleBrowserPolicySmoke.java`：返回链接形状过 Rust `valid_return_link`、取消/无浏览器文案口径、`GoogleBrowserSignIn.readHead` 的完整头/半截头/超限头三种判定（真回环 socket）。
