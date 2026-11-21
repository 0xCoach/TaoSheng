package app.resound.transcribe

/** 升级前的录音暂存格式。 */
object LegacyPlaybackFile {
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
