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
    @Suppress("UNUSED_PARAMETER")
    fun build(date: LocalDate, zone: TimeZone, result: ScreenTimeResult, topApps: Int = 5): ScreenTimeReport? {
        if (!result.enabled) return null
        val totalMinutes = roundMinutes(result.sessions.sumOf { durationMs(it) })
        val apps = result.sessions.groupBy { it.pkg }.map { (pkg, sessions) ->
            AppMinutes(pkg, sessions.first().name, roundMinutes(sessions.sumOf { durationMs(it) }))
        }.sortedWith(compareByDescending<AppMinutes> { it.minutes }.thenBy { it.pkg })
        val top = apps.take(topApps)
        val appLines = top.joinToString("\n") { "${it.name} ${it.minutes} 分" }
        val playbackLines = playbackLines(result.devicePlayback, topApps)
        val playbackBlock = playbackLines.takeIf { it.isNotEmpty() }?.let { "设备播放记录：\n$it" }
        val gapLine = gapLine(result.gaps)
        val body = listOfNotNull(appLines.takeIf { it.isNotEmpty() }, playbackBlock, gapLine).joinToString("\n")
        val markdown = buildString {
            append(durationText("今日共", totalMinutes))
            if (top.isNotEmpty()) {
                append("\n\n")
                append(top.joinToString("\n") { "- ${it.name} ${it.minutes} 分" })
            }
            if (playbackLines.isNotEmpty()) {
                append("\n\n")
                append("设备播放记录（只表示手机在播放，不代表本人在听）：\n")
                append(playbackLines.lines().joinToString("\n") { "- $it" })
            }
            if (gapLine != null) append("\n\n").append(gapLine)
        }
        return ScreenTimeReport(date, durationText("今日屏幕时间", totalMinutes), body, markdown, totalMinutes, apps, result)
    }

    private fun durationMs(session: NamedAppSession) = (session.until - session.from).coerceAtLeast(0L)

    private fun playbackLines(records: List<DevicePlayback>, topApps: Int): String =
        records.groupBy { it.name to it.title }.map { (key, rows) ->
            val minutes = roundMinutes(rows.sumOf { (it.until - it.from).coerceAtLeast(0L) })
            Triple(key.first, key.second, minutes)
        }.filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<String, String?, Int>> { it.third }.thenBy { it.first })
            .take(topApps)
            .joinToString("\n") { (name, title, minutes) ->
                if (title != null) "$name《$title》 $minutes 分" else "$name $minutes 分"
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
