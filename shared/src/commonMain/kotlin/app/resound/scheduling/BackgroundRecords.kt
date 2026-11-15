package app.resound.scheduling

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackgroundRun(val at: Long, val day: String, val source: String, val mode: String,
    val dueAt: Long, val result: String, val durationMillis: Long, val lockWaitMillis: Long = 0,
    val stages: Map<String, Long> = emptyMap(), val reason: String = "", val runId: String = "")

/** 内部快照；界面约定须由 Claude 确认后再接入 AppGraph。 */
data class BackgroundHealth(val systemType: String, val todayDigestCount: Int = 0,
    val lastAutomaticDigestAt: Long? = null, val maxDelayMinutes: Long = 0,
    val suspectedSystemRestriction: Boolean = false)

object BackgroundRecords {
    fun decode(value: String): List<BackgroundRun> = runCatching { Json.decodeFromString<List<BackgroundRun>>(value) }.getOrDefault(emptyList())
    fun encode(rows: List<BackgroundRun>): String = Json.encodeToString(rows)
    fun retain(rows: List<BackgroundRun>, now: Long) = rows.filter { it.at >= now - 7 * 86_400_000L }.takeLast(2000)
    fun summary(rows: List<BackgroundRun>): String = rows.groupBy { it.day }.entries.sortedBy { it.key }.joinToString("\n") { (day, entries) ->
        val counts = (listOf("心跳", "亮屏", "接电", "打开App", "闹钟", "系统周期", "到点闹钟", "手动", "系统补漏") + entries.map { it.source }).distinct().joinToString("、") { source -> "$source=${entries.count { it.source == source }}" }
        val delays = entries.map { (it.at - it.dueAt).coerceAtLeast(0) }
        "$day $counts 平均迟到=${delays.average().toLong()}ms 最长迟到=${delays.maxOrNull() ?: 0}ms"
    }
    fun suspectedRestriction(intervalMode: Boolean, now: Long, serviceSince: Long, lastPeriodic: Long): Boolean =
        intervalMode && now - maxOf(serviceSince, lastPeriodic) >= 6 * 3_600_000L
    fun health(rows: List<BackgroundRun>, day: String, system: String, suspected: Boolean): BackgroundHealth {
        val automatic = rows.filter { it.source != "手动" && it.mode in listOf("digest", "timed") }
        return BackgroundHealth(system, automatic.count { it.day == day }, automatic.maxOfOrNull { it.at },
            automatic.maxOfOrNull { (it.at - it.dueAt).coerceAtLeast(0) / 60_000 } ?: 0, suspected)
    }
}
