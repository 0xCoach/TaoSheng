package app.resound.digest

/**
 * 整理提示词里的 App / 节目名读取门控：只有「记录播放标题」开且「整理时带上 App 与播放信息」开才查播放记录。
 * 关闭时连查询都不做，基础「外放：手机在外放」仍由 SituationPrompt 按片段外放时长输出。
 */
fun allowAppPlaybackInPrompt(titleEnabled: Boolean, includeAppPlayback: Boolean): Boolean =
    titleEnabled && includeAppPlayback

fun <T> playbackRecordsForPrompt(
    titleEnabled: Boolean,
    includeAppPlayback: Boolean,
    query: () -> List<T>,
): List<T> = if (allowAppPlaybackInPrompt(titleEnabled, includeAppPlayback)) query() else emptyList()
