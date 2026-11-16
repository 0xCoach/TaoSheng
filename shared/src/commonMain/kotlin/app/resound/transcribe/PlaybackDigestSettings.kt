package app.resound.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 「外放内容也参与整理」开关，默认关；与 skipPlayback 同放 transcribe 偏好容器。 */
class PlaybackDigestSettings(
    readBoolean: (String, Boolean) -> Boolean,
    private val saveBoolean: (String, Boolean) -> Boolean,
) {
    private val _includePlaybackDigest = MutableStateFlow(readBoolean(KEY, false))
    val includePlaybackDigest = _includePlaybackDigest.asStateFlow()
    fun setIncludePlaybackDigest(on: Boolean) {
        check(saveBoolean(KEY, on))
        _includePlaybackDigest.value = on
    }

    companion object {
        const val KEY = "includePlaybackDigest"

        /** 外放占一半以上（同 PlaybackPolicy.skip 阈值，不看 skipPlayback）时：开写 2，关写 1；普通片段写 0。 */
        fun initial(durationMillis: Long, playbackMillis: Long, include: Boolean): Int {
            val majority = durationMillis > 0 && playbackMillis >= (durationMillis + 1) / 2
            return when {
                !majority -> 0
                include -> 2
                else -> 1
            }
        }

        fun formatFile(playbackMillis: Long, skipEnabled: Boolean, include: Boolean): String =
            "$playbackMillis:$skipEnabled:$include"

        fun parseFile(text: String): Triple<Long, Boolean, Boolean> {
            val values = text.split(":")
            return Triple(
                values.firstOrNull()?.toLongOrNull() ?: 0L,
                values.getOrNull(1) != "false",
                values.getOrNull(2) == "true",
            )
        }
    }
}
