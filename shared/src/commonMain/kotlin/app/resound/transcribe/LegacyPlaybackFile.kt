package app.resound.transcribe

/** 从升级前的录音暂存文件恢复外放决策。 */
object LegacyPlaybackFile {
    private const val ORDINARY_RECORDING = 0
    private const val NEEDS_DIGEST_APPROVAL = 1
    private const val MAY_JOIN_DIGEST = 2

    fun recover(text: String, durationMillis: Long): PlaybackDecision {
        // 只读前三栏。旧文件缺省跳过外放，整理选项则须明确开启。
        val fields = text.split(':', limit = 4)
        val recordedMillis = fields[0].toLongOrNull() ?: 0L
        val audibleMillis = recordedMillis.coerceIn(0L, durationMillis)
        val half = (durationMillis + 1L) / 2L
        val isPlayback = durationMillis > 0L && audibleMillis >= half
        val digest = when {
            !isPlayback -> ORDINARY_RECORDING
            fields.getOrNull(2) == "true" -> MAY_JOIN_DIGEST
            else -> NEEDS_DIGEST_APPROVAL
        }
        return PlaybackDecision(
            intervals = emptyList(),
            millis = audibleMillis,
            skip = isPlayback && fields.getOrNull(1) != "false",
            digest = digest,
        )
    }
}
