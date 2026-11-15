package app.resound.transcribe

/** null 表示当前不会自动删除；0 表示已到期；正数向上取整为剩余天数。
 * Done 的声纹未处理、Failed 的重试未结束、正在读取等保护由调用方通过 protected 传入。
 * 重点保留传入已结合 originalKeepStarred 的有效值，不把所有收藏都视为永久保留。
 */
fun originalAudioRemainingDays(completedAt: Long?, status: String, retentionDays: Int,
    keepImportant: Boolean, now: Long, protected: Boolean = false): Int? {
    if (keepImportant || protected || completedAt == null || status !in setOf(
            "Done", "Empty", "SkippedPlayback", "SkippedEnv", "Abandoned", "Quiet", "Failed")) return null
    val days = if (status == "SkippedEnv") maxOf(1, retentionDays) else retentionDays.coerceAtLeast(0)
    val age = now - completedAt
    val remaining = days * 86_400_000L - age
    return if (remaining <= 0) 0 else ((remaining - 1) / 86_400_000L + 1).toInt()
}
