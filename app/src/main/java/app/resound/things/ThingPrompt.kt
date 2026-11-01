package app.resound.things

import app.resound.data.TranscriptLineEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

internal data class ThingProposal(val row: ThingRecordRow, val confident: Boolean)

internal object ThingPrompt {
    fun system(template: String, today: LocalDate = LocalDate.now()) = template.replace("{{CALENDAR}}", "今天：$today。事实时间以原话时间为准。")
    /** 不丢弃新原话；只裁剪参考资料。跨批问答保留最近十分钟的上下文。 */
    fun context(lines: List<TranscriptLineEntity>, previous: List<TranscriptLineEntity>): List<TranscriptLineEntity> {
        val first = lines.minOfOrNull { it.startAt } ?: return emptyList()
        return previous.filter { it.startAt <= first && it.endAt >= first - 10 * 60_000L }.takeLast(20)
    }
    fun known(lines: List<TranscriptLineEntity>, context: List<TranscriptLineEntity>, known: List<Thing>): List<Thing> {
        val text = (context + lines).joinToString("\n") { it.text }
        val relevant = known.filter { t -> (listOf(t.name, t.latest.place, t.lastPlace, t.latest.who) + t.aliases)
            .filterNotNull().any { it.length >= 2 && text.contains(it, ignoreCase = true) } }
        return (relevant + known.take(8)).distinctBy { it.id }.take(40)
    }
    fun input(lines: List<TranscriptLineEntity>, context: List<TranscriptLineEntity>, known: List<Thing>, manual: Boolean = false): String {
        fun rows(values: List<TranscriptLineEntity>) = JSONArray(values.map { l ->
            JSONObject().put("id", l.id).put("at", Instant.ofEpochMilli(l.startAt).atZone(ZoneId.systemDefault()).toString())
                .put("segmentId", l.segmentId).put("speaker", l.speakerName ?: if (l.speakerPersonId == 0L) "我" else "未知").put("text", l.text)
        })
        return JSONObject().put("mode", if (manual) "manual" else "live")
            .put("contextNote", "已处理过，只作参考，不得输出旧事实").put("context", rows(context)).put("lines", rows(lines))
            .put("known", JSONArray(known.take(200).map { t -> JSONObject().put("id", t.id).put("name", t.name)
                .put("aliases", JSONArray(t.aliases)).put("action", t.latest.action.name).put("place", t.latest.place ?: JSONObject.NULL)
                .put("lastPlace", t.lastPlace ?: JSONObject.NULL).put("who", t.latest.who ?: JSONObject.NULL).put("owner", t.owner.name) })).toString()
    }
    fun parse(content: String, lines: List<TranscriptLineEntity>, known: Set<Long>, now: Long): List<ThingProposal> {
        val items = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")) .getJSONArray("items")
        return (0 until items.length()).map { i ->
            val v = items.getJSONObject(i)
            fun field(key: String) = if (v.isNull(key)) null else v.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
            val name = v.getString("name").trim(); require(name.isNotBlank())
            val action = ThingAction.valueOf(v.getString("action")); val owner = ThingOwner.valueOf(v.getString("owner"))
            val place = field("place"); val who = field("who").takeIf { action == ThingAction.Lent || action == ThingAction.Returned }
            require(action != ThingAction.Placed || place != null)
            require(action != ThingAction.Lent || who != null)
            val line = lines.firstOrNull { it.id == v.getLong("lineId") } ?: error("不是新原话")
            val id = if (v.isNull("knownId")) null else v.getLong("knownId").also { require(it in known) }
            val confident = v.getBoolean("confident")
            ThingProposal(ThingRecordRow(thingId = id?.takeIf { confident }, name = name, action = action.name, place = place,
                who = who, owner = owner.name, at = line.startAt, sourceLineId = line.id, sourceText = line.text,
                explicit = v.getBoolean("explicit"), sameAsId = id?.takeUnless { confident }, createdAt = now,
                aliasesJson = (v.optJSONArray("aliases") ?: JSONArray()).toString()), confident)
        }
    }
}
