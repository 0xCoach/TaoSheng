package app.resound.digest

/**
 * 整理提示词与问问工具里的 App / 节目名读取门控。
 * 「整理时带上 App 与播放信息」关时连查询都不做；基础「外放：手机在外放」仍由 SituationPrompt 按片段外放时长输出。
 */
fun allowAppPlaybackInPrompt(titleEnabled: Boolean, includeAppPlayback: Boolean): Boolean =
    titleEnabled && includeAppPlayback

/** 前台「在用 App」：屏幕时间采集开且隐私开关开才查库。不受地点/环境开关影响。 */
fun allowForegroundAppInPrompt(screenEnabled: Boolean, includeAppPlayback: Boolean): Boolean =
    screenEnabled && includeAppPlayback

fun <T> playbackRecordsForPrompt(
    titleEnabled: Boolean,
    includeAppPlayback: Boolean,
    query: () -> List<T>,
): List<T> = if (allowAppPlaybackInPrompt(titleEnabled, includeAppPlayback)) query() else emptyList()

fun <T> foregroundSessionsForPrompt(
    screenEnabled: Boolean,
    includeAppPlayback: Boolean,
    query: () -> List<T>,
): List<T> = if (allowForegroundAppInPrompt(screenEnabled, includeAppPlayback)) query() else emptyList()
