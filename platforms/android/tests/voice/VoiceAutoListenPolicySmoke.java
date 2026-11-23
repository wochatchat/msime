package app.msime.android;

/** 自动聆听的准入与挡板：四个前提缺一不可，密码框永远不自动开口，延迟常量留给排程。 */
public final class VoiceAutoListenPolicySmoke {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // 全部满足才开始：开关、语音总开关、会话就绪、键区内引擎。
        check(VoiceAutoListenPolicy.decide(true, true, true, true) == VoiceAutoListenPolicy.Decision.START,
            "四个前提都满足才开始");
        check(VoiceAutoListenPolicy.decide(false, true, true, true) == VoiceAutoListenPolicy.Decision.BLOCKED,
            "本地开关关闭不开始");
        check(VoiceAutoListenPolicy.decide(true, false, true, true) == VoiceAutoListenPolicy.Decision.BLOCKED,
            "语音输入总开关关闭不开始");
        check(VoiceAutoListenPolicy.decide(true, true, false, true) == VoiceAutoListenPolicy.Decision.BLOCKED,
            "会话未就绪或组字中不开始");
        check(VoiceAutoListenPolicy.decide(true, true, true, false) == VoiceAutoListenPolicy.Decision.BLOCKED,
            "键区内没有可用引擎（含系统识别服务）不开始");

        // 密码框按输入框类型挡住；普通文本框放行。数字密码同样算密码。
        check(!VoiceAutoListenPolicy.editorAllows(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "文字密码框不自动录音");
        check(!VoiceAutoListenPolicy.editorAllows(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "可见密码框不自动录音");
        check(!VoiceAutoListenPolicy.editorAllows(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "数字密码框不自动录音");
        check(VoiceAutoListenPolicy.editorAllows(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL),
            "普通文本框放行");
        check(VoiceAutoListenPolicy.editorAllows(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            "多行文本框放行");
        check(VoiceAutoListenPolicy.editorAllows(0),
            "没有类别的输入框（TYPE_NULL 聊天控件）放行");

        // 延迟给首帧渲染和输入框落地留时间：不能是 0（窗口还没显示），也不该太长。
        check(VoiceAutoListenPolicy.START_DELAY_MILLIS > 0 && VoiceAutoListenPolicy.START_DELAY_MILLIS <= 1000,
            "自动开始延迟在一秒以内");

        // 只在真正的隐藏→显示时排程：键盘已可见时重发的 onWindowShown 不再自动聆听。
        check(VoiceAutoListenPolicy.schedulesOnWindowShown(false), "窗口从隐藏到显示要排自动聆听");
        check(!VoiceAutoListenPolicy.schedulesOnWindowShown(true), "键盘可见期间的重复显示不排自动聆听");

        // 面板画进第一帧的门槛与开始录音的四个前提一致，只少「会话就绪」（录音等它）。
        check(VoiceAutoListenPolicy.preShowsListeningPanel(true, true, true, true),
            "开关与引擎就绪时第一帧就露聆听面板");
        check(!VoiceAutoListenPolicy.preShowsListeningPanel(false, true, true, true),
            "本地开关关闭不预画面板");
        check(!VoiceAutoListenPolicy.preShowsListeningPanel(true, false, true, true),
            "语音总开关关闭不预画面板");
        check(!VoiceAutoListenPolicy.preShowsListeningPanel(true, true, false, true),
            "键区内没有可用引擎不预画面板");
        check(!VoiceAutoListenPolicy.preShowsListeningPanel(true, true, true, false),
            "密码框不预画面板");
        System.out.println("VoiceAutoListenPolicySmoke passed");
    }
}
