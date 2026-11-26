package app.resound.ask.core
import app.resound.ask.AskSource
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
        val refs = HashMap<AskSource, Int>(sources.size)
        sources.forEachIndexed { index, entry -> refs.getOrPut(entry) { index + 1 } }
        fun addSource(entry: AskSource) = refs.getOrPut(entry) {
            sources.add(entry)
            sources.size
        }

        val totalsArr = JSONArray()
        for ((first, millis) in totals(result.sessions)) {
            totalsArr.put(JSONObject().put("ref", addSource(source(first))).put("name", first.name).put("package", first.pkg)
                .put("minutes", ((millis + 30_000L) / 60_000L).toInt()))
        }

        val sessions = JSONArray()
        val playback = JSONArray()
        val gaps = JSONArray()
        for (gap in result.gaps) gaps.put(JSONObject().put("code", gap.code).put("source", gap.source)
            .put("from", AskTime.format(gap.from, zone)).put("until", AskTime.format(gap.until, zone)).put("fix", gap.fix))
        val output = JSONObject().put("totals", totalsArr).put("sessions", sessions).put("devicePlayback", playback)
            .put("coverageUntil", result.coverageUntil?.let { AskTime.format(it, zone) } ?: JSONObject.NULL)
            .put("gaps", gaps).put("truncated", false)

        var length = output.toString().length
        fun append(target: JSONArray, entry: AskSource, fields: JSONObject.() -> Unit): Boolean {
            val ref = refs[entry] ?: sources.size + 1
            val row = JSONObject().put("ref", ref).apply(fields)
            // 同一 JSON 编码器生成各行与整包，中间只多一个逗号。
            val newLength = length + row.toString().length + if (target.length() == 0) 0 else 1
            if (newLength > MAX_PAYLOAD_LENGTH) return false
            length = newLength
            target.put(row)
            addSource(entry)
            return true
        }
        val allSessions = result.sessions.sortedBy { it.from }.all { session ->
            append(sessions, source(session)) {
                put("name", session.name).put("package", session.pkg)
                put("start", AskTime.format(session.from, zone)).put("end", AskTime.format(session.until, zone))
            }
        }
        val allDetails = allSessions && result.devicePlayback.sortedBy { it.from }.all { item ->
            append(playback, source(item)) {
                put("name", item.name).put("package", item.pkg)
                put("title", item.title ?: JSONObject.NULL).put("subtitle", item.subtitle ?: JSONObject.NULL)
                put("start", AskTime.format(item.from, zone)).put("end", AskTime.format(item.until, zone))
            }
        }
        return output.put("truncated", !allDetails).toString()
    }

    private data class AppTotal(val first: NamedAppSession, val duration: Long)

    private fun totals(sessions: List<NamedAppSession>): List<AppTotal> {
        val byPackage = HashMap<String, AppTotal>()
        for (session in sessions) {
            val stored = byPackage[session.pkg]
            val first = if (stored != null && stored.first.from <= session.from) stored.first else session
            byPackage[session.pkg] = AppTotal(first, (stored?.duration ?: 0L) + (session.until - session.from))
        }
        return byPackage.values.sortedWith(compareByDescending<AppTotal> { it.duration }.thenBy { it.first.pkg })
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
        const val MAX_PAYLOAD_LENGTH = 6000
        const val MAX_SPAN_MS = 10L * 24 * 60 * 60 * 1000
        const val DISABLED =
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"屏幕时间未开启，没有 App 使用数据。"}"""
        const val PRIVACY_DISABLED =
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"用户关闭了整理时带上 App 与播放信息"}"""
    }
}
