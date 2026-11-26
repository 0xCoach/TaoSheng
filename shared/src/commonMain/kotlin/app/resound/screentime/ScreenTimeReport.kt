package app.resound.screentime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

data class ScreenTimeReport(
    val date: LocalDate,
    val title: String,
    val body: String,
    val markdown: String,
    val totalMinutes: Int,
    val apps: List<AppMinutes>,
    val result: ScreenTimeResult,
)

data class AppMinutes(val pkg: String, val name: String, val minutes: Int)

object ScreenTimeReports {
    private data class AppDuration(val name: String, val millis: Long)
    private data class PlaybackMinutes(val name: String, val title: String?, val minutes: Int)

    @Suppress("UNUSED_PARAMETER")
    fun build(date: LocalDate, zone: TimeZone, result: ScreenTimeResult, topApps: Int = 5): ScreenTimeReport? {
        if (!result.enabled) return null
        var allMillis = 0L
        val durationByApp = HashMap<String, AppDuration>()
        for (session in result.sessions) {
            val duration = (session.until - session.from).coerceAtLeast(0L)
            allMillis += duration
            val aggregate = durationByApp[session.pkg]
            durationByApp[session.pkg] = if (aggregate == null) AppDuration(session.name, duration)
                else aggregate.copy(millis = aggregate.millis + duration)
        }
        val totalMinutes = roundMinutes(allMillis)
        val apps = durationByApp.map { (pkg, value) -> AppMinutes(pkg, value.name, roundMinutes(value.millis)) }
            .sortedWith(compareByDescending<AppMinutes> { it.minutes }.thenBy { it.pkg })

        val displayApps = apps.take(topApps).map { "${it.name} ${it.minutes} 分" }
        val displayedPlayback = playbackLines(result.devicePlayback, topApps)
        val fixes = gapLine(result.gaps)
        val body = buildList {
            addAll(displayApps)
            if (displayedPlayback.isNotEmpty()) {
                add("设备播放记录：")
                add(displayedPlayback)
            }
            if (fixes != null) add(fixes)
        }.joinToString("\n")
        val markdown = buildList {
            add(durationText("今日共", totalMinutes))
            if (displayApps.isNotEmpty()) add(displayApps.joinToString("\n") { "- $it" })
            if (displayedPlayback.isNotEmpty()) {
                add(displayedPlayback.lines().joinToString(
                    separator = "\n", prefix = "设备播放记录（只表示手机在播放，不代表本人在听）：\n",
                ) { "- $it" })
            }
            if (fixes != null) add(fixes)
        }.joinToString("\n\n")
        return ScreenTimeReport(date, durationText("今日屏幕时间", totalMinutes), body, markdown, totalMinutes, apps, result)
    }

    private fun playbackLines(records: List<DevicePlayback>, topApps: Int): String {
        // 同名且同标题的记录合计。分钟与显示名相同时保留首次出现的顺序。
        val plays = LinkedHashMap<Pair<String, String?>, Long>()
        for (record in records) {
            val label = record.name to record.title
            val elapsed = (record.until - record.from).coerceAtLeast(0L)
            plays[label] = (plays[label] ?: 0L) + elapsed
        }
        return plays.mapNotNull { (label, millis) ->
            val minutes = roundMinutes(millis)
            if (minutes > 0) PlaybackMinutes(label.first, label.second, minutes) else null
        }.sortedWith(compareByDescending<PlaybackMinutes> { it.minutes }.thenBy { it.name })
            .take(topApps)
            .joinToString("\n") { (name, title, minutes) ->
                if (title != null) "$name《$title》 $minutes 分" else "$name $minutes 分"
            }
    }

    /** 四舍五入到整分钟（正数 half-up，不用银行家舍入）。 */
    internal fun roundMinutes(ms: Long) = ((ms.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()

    private fun durationText(prefix: String, minutes: Int) =
        if (minutes < 60) "$prefix $minutes 分" else "$prefix ${minutes / 60} 小时 ${minutes % 60} 分"

    private fun gapLine(gaps: List<CoverageGap>): String? {
        val fixes = gaps.map { it.fix }.filter { it.isNotBlank() }.distinct()
        if (fixes.isEmpty()) return null
        return "部分时段没有数据：" + fixes.joinToString("；")
    }
}
