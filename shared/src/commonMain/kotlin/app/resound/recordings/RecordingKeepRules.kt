package app.resound.recordings

import app.resound.push.*

/** 日期采用当地 ISO 日期字符串，时刻/到期时刻均为 Unix 毫秒，由平台提供。 */
data class KeepRecording(val id: Long, val day: String, val bytes: Long, val expiresAt: Long,
    val media: Boolean = false, val speech: Boolean = false, val call: Boolean = false, val imported: Boolean = false)
data class RecordingChannel(val id: Long, val subscription: PushSubscription, val since: String?, val uploaded: Set<Long>)
data class HeldSummary(val segments: Int = 0, val days: Int = 0, val bytes: Long = 0,
    val oldestDay: String? = null, val remind: Boolean = false)
data class KeepStatus(val channels: List<String> = emptyList(), val protecting: Boolean = false)

object RecordingKeepRules {
    fun eligible(row: KeepRecording, s: PushSubscription, since: String?, today: String): Boolean =
        s.audio != AudioPush.Off && (since == null || row.day >= since) && row.day <= today &&
            !(s.excludeMedia && row.media) && (s.audio != AudioPush.Speech || row.speech) &&
            (if (row.call) s.audioCalls else !row.imported || s.audioImports)

    fun held(rows: List<KeepRecording>, channels: List<RecordingChannel>, today: String): Set<Long> {
        val uploaded = channels.flatMap { it.uploaded }.toSet()
        return rows.filter { it.id !in uploaded && channels.any { c -> eligible(it, c.subscription, c.since, today) } }
            .mapTo(mutableSetOf()) { it.id }
    }
    fun summary(rows: List<KeepRecording>, held: Set<Long>, now: Long): HeldSummary {
        val expired = rows.filter { it.id in held && it.expiresAt <= now }
        return HeldSummary(expired.size, expired.map { it.day }.distinct().size, expired.sumOf { it.bytes },
            expired.minOfOrNull { it.day }, expired.any { now - it.expiresAt >= 7 * 86_400_000L })
    }
    fun verified(audio: Boolean, verified: Int?) = audio && (verified == null || verified == 1)
    fun perSegment(s: PushSubscription) = s.audioText == AudioText.PerSegment || s.audioText == AudioText.Both
    fun daily(s: PushSubscription) = s.audioText == AudioText.Daily || s.audioText == AudioText.Both
    fun dailyPath(day: String) = "录音/$day/$day 逐字稿.txt"
    fun dailyText(day: String, sections: List<Pair<String, String>>) = "\uFEFF$day 逐字稿\n" +
        sections.joinToString("\n") { (filename, lines) -> "—— ${filename.substringAfter(' ').take(5).replace('.', ':')}（录音：$filename）——\n$lines\n" }
    fun quotaHint(nutstore: Boolean, bytes: Long): String? = if (nutstore && bytes >= 800_000_000L)
        "本月已推 ${bytes / 1_000_000} MB，坚果云免费版每月上传约 1 GB" else null
}
