package app.resound.things

import app.resound.platform.legacyText
import app.resound.platform.legacyValue
import app.resound.platform.legacyLong
import app.resound.platform.SharedJson
import kotlinx.serialization.json.*

object ThingPromptRules {
    fun system(template: String, today: String) = template.replace("{{CALENDAR}}", "今天：$today。事实时间以原话时间为准。")
    /** 不丢弃新原话；只裁剪参考资料。跨批问答保留最近十分钟的上下文。 */
    fun context(lines: List<ThingLine>, previous: List<ThingLine>): List<ThingLine> {
        val first = lines.minOfOrNull { it.startAt } ?: return emptyList()
        return previous.filter { it.startAt <= first && it.endAt >= first - 10 * 60_000L }.takeLast(20)
    }
    fun known(lines: List<ThingLine>, context: List<ThingLine>, known: List<Thing>): List<Thing> {
        val text = (context + lines).joinToString("\n") { it.text }
        val relevant = known.filter { t -> (listOf(t.name, t.latest.place, t.lastPlace, t.latest.who) + t.aliases)
            .filterNotNull().any { it.length >= 2 && text.contains(it, ignoreCase = true) } }
        return (relevant + known.take(8)).distinctBy { it.id }.take(40)
    }
    fun input(lines: List<ThingLine>, context: List<ThingLine>, known: List<Thing>, manual: Boolean = false): String {
        fun rows(values: List<ThingLine>) = JsonArray(values.map { l -> buildJsonObject {
            put("id", l.id); put("at", l.timeText); put("segmentId", l.segmentId)
            put("speaker", l.speakerName ?: if (l.speakerPersonId == 0L) "我" else "未知"); put("text", l.text)
        } })
        return buildJsonObject {
            put("mode", if (manual) "manual" else "live"); put("contextNote", "已处理过，只作参考，不得输出旧事实")
            put("context", rows(context)); put("lines", rows(lines))
            put("known", JsonArray(known.take(200).map { t -> buildJsonObject {
                put("id", t.id); put("name", t.name); put("aliases", JsonArray(t.aliases.map(::JsonPrimitive)))
                put("action", t.latest.action.name); put("place", t.latest.place?.let(::JsonPrimitive) ?: JsonNull)
                put("lastPlace", t.lastPlace?.let(::JsonPrimitive) ?: JsonNull)
                put("who", t.latest.who?.let(::JsonPrimitive) ?: JsonNull); put("owner", t.owner.name)
            } }))
        }.legacyText()
    }
    fun parse(content: String, lines: List<ThingLine>, known: Set<Long>, now: Long): List<ThingProposalData> {
        val items = SharedJson.parseToJsonElement(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")).jsonObject.getValue("items").jsonArray
        return items.map { element ->
            val v = element.jsonObject
            fun field(key: String) = v[key]?.takeUnless { it == JsonNull }?.legacyValue()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            fun text(key: String) = v.getValue(key).legacyValue()
            fun flag(key: String) = text(key).let { require(it.equals("true", true) || it.equals("false", true)); it.equals("true", true) }
            val name = text("name").trim(); require(name.isNotBlank())
            val action = ThingAction.valueOf(text("action")); val owner = ThingOwner.valueOf(text("owner"))
            val place = field("place"); val who = field("who").takeIf { action == ThingAction.Lent || action == ThingAction.Returned }
            require(action != ThingAction.Placed || place != null)
            require(action != ThingAction.Lent || who != null)
            val line = lines.firstOrNull { it.id == v.getValue("lineId").legacyLong() } ?: error("不是新原话")
            val id = v["knownId"]?.takeUnless { it == JsonNull }?.legacyLong()?.also { require(it in known) }
            val confident = flag("confident")
            ThingProposalData(ThingRecordData(thingId = id?.takeIf { confident }, name = name, action = action.name, place = place,
                who = who, owner = owner.name, at = line.startAt, sourceLineId = line.id, sourceText = line.text,
                explicit = flag("explicit"), sameAsId = id?.takeUnless { confident }, createdAt = now,
                aliasesJson = (v["aliases"] as? JsonArray ?: JsonArray(emptyList())).legacyText()), confident)
        }
    }
}
