package app.msime.android;

/**
 * 「进入输入框自动聆听」（本地设置 `platform.android.voice_auto_listen`）的准入与提示规则，不碰 View 和识别器，JVM 冒烟直接测。
 *
 * <p>自动聆听是「点按/长按/工具栏」之外多出来的第四种开始方式：键盘一弹出就按用户当前的识别配置直接开始一次聆听，省掉每次先点一下的操作。它只替用户按下开始，之后的结束、判停、上屏、取消与点按入口完全一致；不改变任何入口的开关和出厂默认（关）。
 *
 * <p>准入比手动入口严：系统识别服务在部分键盘宿主里弹不出前台录音（`onStartInputView` 一进来就启动更容易被系统静默拒绝），自动开始只覆盖进程内能跑的识别器（本机模型、豆包流式、上传式），没有配置这些时保持沉默，绝不转交识别窗口——否则每点一个输入框就盖一层灰色蒙层。密码框、听写期间切入的编辑器、组字中和尚未就绪的会话也都不自动开始。
 */
public final class VoiceAutoListenPolicy {
    private VoiceAutoListenPolicy() {}

    /** 键盘弹出后延迟这么久才开始聆听：给第一帧渲染和输入框落地留出时间，太早了窗口还没显示、触摸流还没建立。 */
    public static final long START_DELAY_MILLIS = 350;

    /** 一次自动开始的理由。{@code BLOCKED} 的原因只进调试日志，不提示用户：自动聆听本来就不该打扰不打扰的人。 */
    public enum Decision { START, BLOCKED }

    /**
     * 这次键盘弹出要不要自动开始聆听。四个前提各自来自键盘现有的守卫，缺一个都不开始：
     * {@code enabled} 本地开关已打开；{@code voiceEnabled} 共享偏好的语音输入总开关没有关；
     * {@code insertionReady} 会话已建好、连接可用、没有组字（{@code voiceInsertionReady}）；
     * {@code inKeyboardEngine} 当前识别配置能在键区里识别（本机模型、豆包流式或上传式，{@code choose} 不返回 null 也不落系统识别服务）。
     */
    public static Decision decide(boolean enabled, boolean voiceEnabled, boolean insertionReady,
                                  boolean inKeyboardEngine) {
        if (!enabled || !voiceEnabled || !insertionReady) return Decision.BLOCKED;
        return inKeyboardEngine ? Decision.START : Decision.BLOCKED;
    }

    /** 这次的编辑框允不允许录音：密码框永远不自动开口。 */
    public static boolean editorAllows(int inputType) {
        return !EditorPolicy.password(inputType);
    }

    /** 这次 onWindowShown 要不要排一次自动聆听：只在真正的隐藏→显示时排。键盘可见期间部分系统或宿主会重发 onWindowShown（应用重复 showSoftInput、切换编辑框时再叫窗口），用户正在打字或改稿，这时再弹语音页等于抢焦点，保持沉默。 */
    public static boolean schedulesOnWindowShown(boolean windowAlreadyVisible) {
        return !windowAlreadyVisible;
    }

    /** 第一帧要不要直接露出聆听面板（只画面板、不开始录音）：开关与键区内识别器就绪、编辑框允许时才画。录音是否真的开始仍由 {@link #decide} 在延迟到点按会话状态决定，画了但到点不开口时马上收回面板。 */
    public static boolean preShowsListeningPanel(boolean enabled, boolean voiceEnabled,
                                                 boolean inKeyboardEngine, boolean editorAllows) {
        return enabled && voiceEnabled && inKeyboardEngine && editorAllows;
    }
}
