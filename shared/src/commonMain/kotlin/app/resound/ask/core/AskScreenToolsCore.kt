package app.resound.ask.core
import app.resound.ask.*
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import app.resound.screentime.DevicePlayback
import app.resound.screentime.NamedAppSession
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeResult
import kotlinx.datetime.TimeZone

class AskScreenToolsCore(
    private val reader: () -> ScreenTimeReader?,
    private val zone: TimeZone,
    private val includeAppPlayback: () -> Boolean = { true },
    private val screenEnabled: () -> Boolean = { true },
    private val playbackTitlesEnabled: () -> Boolean = { true },
) {
    suspend fun execute(args: String, sources: MutableList<AskSource>): String {
        val input = JSONObject(args)
        val from = AskTime.timestamp(input.getString("from"), false, zone)
        val to = AskTime.timestamp(input.getString("to"), true, zone)
        if (to <= from || to - from > MAX_SPAN_MS) throw AskDateException(IllegalArgumentException("查询区间无效"))
        if (!includeAppPlayback()) return PRIVACY_DISABLED
        if (!screenEnabled()) return DISABLED
        val current = reader() ?: return DISABLED
        val result = current.sessions(from, to)
        if (!result.enabled) return DISABLED
        val visible = if (playbackTitlesEnabled()) result else result.copy(devicePlayback = emptyList())
        return enabled(visible, sources)
    }

    private fun enabled(result: ScreenTimeResult, sources: MutableList<AskSource>): String {
        val totals = result.sessions.groupBy { it.pkg }.map { (_, rows) ->
            val first = rows.minBy { it.from }
            Triple(first, rows.sumOf { it.until - it.from }, first.pkg)
        }.sortedWith(compareByDescending<Triple<NamedAppSession, Long, String>> { it.second }.thenBy { it.third })
        val totalsArr = JSONArray()
        for ((first, millis, pkg) in totals) {
            val source = source(first)
            val existing = sources.indexOf(source)
            val ref = if (existing >= 0) existing + 1 else sources.size + 1
            if (existing < 0) sources += source
            totalsArr.put(JSONObject().put("ref", ref).put("name", first.name).put("package", pkg)
                .put("minutes", ((millis + 30_000L) / 60_000L).toInt()))
        }
        val sessionsArr = JSONArray()
        var truncated = false
        for (session in result.sessions.sortedBy { it.from }) {
            val source = source(session)
            val existing = sources.indexOf(source)
            val ref = if (existing >= 0) existing + 1 else sources.size + 1
            sessionsArr.put(JSONObject().put("ref", ref).put("name", session.name).put("package", session.pkg)
                .put("start", AskTime.format(session.from, zone)).put("end", AskTime.format(session.until, zone)))
            if (payload(totalsArr, sessionsArr, JSONArray(), result, false).length > 6000) {
                sessionsArr.remove(sessionsArr.length() - 1)
                truncated = true
                break
            }
            if (existing < 0) sources += source
        }
        val playbackArr = JSONArray()
        if (!truncated) for (item in result.devicePlayback.sortedBy { it.from }) {
            val source = source(item)
            val existing = sources.indexOf(source)
            val ref = if (existing >= 0) existing + 1 else sources.size + 1
            playbackArr.put(JSONObject().put("ref", ref).put("name", item.name).put("package", item.pkg)
                .put("title", item.title ?: JSONObject.NULL).put("subtitle", item.subtitle ?: JSONObject.NULL)
                .put("start", AskTime.format(item.from, zone)).put("end", AskTime.format(item.until, zone)))
            if (payload(totalsArr, sessionsArr, playbackArr, result, false).length > 6000) {
                playbackArr.remove(playbackArr.length() - 1)
                truncated = true
                break
            }
            if (existing < 0) sources += source
        }
        return payload(totalsArr, sessionsArr, playbackArr, result, truncated)
    }

    private fun payload(
        totals: JSONArray, sessions: JSONArray, playback: JSONArray,
        result: ScreenTimeResult, truncated: Boolean,
    ): String {
        val gaps = JSONArray()
        for (gap in result.gaps) gaps.put(JSONObject().put("code", gap.code).put("source", gap.source)
            .put("from", AskTime.format(gap.from, zone)).put("until", AskTime.format(gap.until, zone)).put("fix", gap.fix))
        return JSONObject().put("totals", totals).put("sessions", sessions).put("devicePlayback", playback)
            .put("coverageUntil", result.coverageUntil?.let { AskTime.format(it, zone) } ?: JSONObject.NULL)
            .put("gaps", gaps).put("truncated", truncated).toString()
    }

    private fun source(session: NamedAppSession) = AskSource(
        "screen", session.from, session.from, "${session.name} 使用",
        "${clock(session.from)}\u2013${clock(session.until)} ${session.name}",
    )
    private fun source(item: DevicePlayback) = AskSource(
        "playback", item.from, item.from, "${item.name} 播放",
        "${clock(item.from)}\u2013${clock(item.until)} ${item.name}《${item.title ?: ""}》",
    )
    private fun clock(at: Long) = AskTime.format(at, zone).takeLast(5)

    private companion object {
        const val MAX_SPAN_MS = 10L * 24 * 60 * 60 * 1000
        const val DISABLED =
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"屏幕时间未开启，没有 App 使用数据。"}"""
        const val PRIVACY_DISABLED =
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"用户关闭了整理时带上 App 与播放信息"}"""
    }
}
