package app.resound.capture

import kotlinx.coroutines.flow.StateFlow

/**
 * 卡 048：记下语音输入的文字和当时的上下文。
 * 用户在别的应用里用输入法语音输入时，系统把麦克风交给输入法、回响收不到声音；
 * 改由无障碍服务在“麦克风被别的应用占用”的这段时间里，读出输入框新多出来的文字，存成一句“我”说的话，
 * 并截下当时屏幕上的对话（之前的内容 + 发出后 2 分钟内的回复）作为上下文。
 */
data class VoiceInputSettings(
    /** 总开关，默认关 */
    val enabled: Boolean = false,
    /** 记当时屏幕上的上下文 */
    val context: Boolean = true,
    /** 发出后 2 分钟内同一界面的回复也补记一次 */
    val replies: Boolean = true,
    /** 这些应用不记（包名） */
    val excludedPackages: Set<String> = emptySet(),
    /** 卡 049：读不到界面时（微信、终端等）改用截屏识字；截图只在内存里识别，识完即丢，只留文字 */
    val screenOcr: Boolean = true,
)

data class InputApp(val packageName: String, val label: String)

interface VoiceInputCapture {
    val settings: StateFlow<VoiceInputSettings>

    /** 随备份 */
    fun update(settings: VoiceInputSettings)

    /** 系统设置里回响的无障碍服务是否开着（小米可能悄悄关掉） */
    val serviceEnabled: StateFlow<Boolean>

    /** 今天记下了几条 */
    val todayCount: StateFlow<Int>

    /** 用过语音输入的应用（最近的在前），给“这些应用不记”挑选 */
    val seenApps: StateFlow<List<InputApp>>
}
