package app.resound.review

import app.resound.data.ReviewInput
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ReviewPrompt {
    val limits = linkedMapOf("一句话" to 1, "要紧的" to 3, "发生了什么" to 8, "待办" to 6, "值得留意" to 3, "灵感" to 5, "心力" to 1)
    fun system(template: String, appName: String) = template.replace("{{APP_NAME}}", appName)

    fun input(date: LocalDate, source: ReviewInput, zone: ZoneId = ZoneId.systemDefault(), dailyAtMinutes: Int = 22 * 60): String {
        fun time(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toString()
        val start = ReviewTime.cutoff(date.minusDays(1), dailyAtMinutes, zone)
        val end = ReviewTime.cutoff(date, dailyAtMinutes, zone)
        val clock = end.toLocalTime().toString()
        val period = if (end.hour >= 18) "昨晚 $clock 到今晚 $clock" else "昨日 $clock 到今日 $clock"
        val items = source.items.groupBy { it.eventId }
        return JSONObject().put("date", date.toString())
            .put("timeWindow", JSONObject().put("startExclusive", start.toString()).put("endInclusive", end.toString())
                .put("description", "回顾日期 $date，覆盖$period（起点不含，终点包含），不是自然日。"))
            .put("events", JSONArray(source.events.map { e ->
            JSONObject().put("id", e.id).put("startAt", time(e.startAt)).put("endAt", time(e.endAt))
                .put("scene", e.scene).put("title", e.title).put("summary", e.summary)
                .put("points", JSONArray(e.pointsJson)).put("emotion", e.emotion ?: JSONObject.NULL)
                .put("intensity", e.intensity ?: JSONObject.NULL).put("important", e.important)
                .put("items", JSONArray(items[e.id].orEmpty().map { i ->
                    JSONObject().put("category", i.category).put("kind", i.kind ?: JSONObject.NULL)
                        .put("content", i.content).put("status", i.status).put("createdAt", time(i.createdAt))
                        .put("dueText", i.dueText ?: JSONObject.NULL).put("dueAt", i.dueAt?.let(::time) ?: JSONObject.NULL)
                }))
        })).toString()
    }

    /** 越界出处整条丢弃，缺少可选栏目合法，必需栏目缺失则不保存残缺回顾。 */
    fun parse(content: String, eventIds: Set<Long>): Pair<String, List<ReviewSection>> {
        try {
            val json = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
            val brief = (json.opt("brief") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: error("brief")
            val raw = json.getJSONArray("sections")
            val sections = limits.mapNotNull { (title, limit) ->
                val lines = mutableListOf<Pair<String, Long>>()
                for (i in 0 until raw.length()) {
                    val section = raw.optJSONObject(i) ?: continue
                    if (section.optString("title") != title) continue
                    val entries = section.optJSONArray("lines") ?: continue
                    for (j in 0 until entries.length()) {
                        val entry = entries.optJSONObject(j) ?: continue
                        val text = (entry.opt("text") as? String)?.trim().orEmpty()
                        val id = (entry.opt("eventId") as? Number)?.let { n -> n.toLong().takeIf { it.toDouble() == n.toDouble() } }
                        if (text.isNotEmpty() && id in eventIds) lines += text to id!!
                    }
                }
                lines.distinct().take(limit).takeIf { it.isNotEmpty() }?.let {
                    ReviewSection(title, it.map { p -> p.first }, it.map { p -> p.second })
                }
            }
            if (sections.none { it.title == "一句话" } || sections.none { it.title == "发生了什么" }) error("sections")
            val safeBrief = brief.substring(0, brief.offsetByCodePoints(0, minOf(60, brief.codePointCount(0, brief.length))))
            return safeBrief to sections
        } catch (_: Exception) { throw LlmException(LlmError.Format) }
    }

    fun encode(sections: List<ReviewSection>): String = JSONArray(sections.map { s ->
        JSONObject().put("title", s.title).put("lines", JSONArray(s.lines)).put("eventIds", JSONArray(s.eventIds))
    }).toString()

    fun decode(text: String): List<ReviewSection> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val lines = s.getJSONArray("lines"); val ids = s.getJSONArray("eventIds")
            ReviewSection(s.getString("title"), (0 until lines.length()).map { lines.getString(it) },
                (0 until lines.length()).map { if (ids.isNull(it)) null else ids.getLong(it) })
        }
    }
}
