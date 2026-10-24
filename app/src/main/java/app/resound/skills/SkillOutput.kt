package app.resound.skills

import app.resound.digest.LlmError
import app.resound.digest.LlmException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

object SkillOutput {
    data class Parsed(val rows: List<SkillCardRow>, val reason: String)
    fun parse(content: String, skill: Skill, date: LocalDate, input: SkillInput, now: Long, manual: Boolean): Parsed {
        try {
            val j = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
            val cards = j.getJSONArray("cards")
            val rows = cards.elements().mapNotNull { raw ->
                val c = raw as? JSONObject ?: throw LlmException(LlmError.Format)
                val values = c.getJSONObject("values")
                val mapped = skill.fields.associate { f -> f.key to value(f.type, values.opt(f.key), input) }
                if (skill.fields.any { it.required && empty(mapped.getValue(it.key)) }) return@mapNotNull null
                val title = c.nullString("title") ?: return@mapNotNull null
                val entity = if (skill.maxCardsPerDay == 1) date.toString() else c.nullString("entity") ?: return@mapNotNull null
                // 一天一张的总结卡放在它运行的时刻（定时的用设定时间，还没到就用现在），不用模型挑的时间
                val at = if (skill.maxCardsPerDay == 1) {
                    val planned = (skill.trigger as? SkillTrigger.Daily)?.let { date.atTime(it.minutes / 60, it.minutes % 60).atZone(SKILL_ZONE).toInstant().toEpochMilli() }
                    val endOfDay = date.plusDays(1).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli() - 60_000
                    minOf(planned ?: now, now, endOfDay)
                } else date.atTime(LocalTime.parse(c.getString("at"))).atZone(SKILL_ZONE).toInstant().toEpochMilli()
                val end = c.nullString("end")?.let { date.atTime(LocalTime.parse(it)).atZone(SKILL_ZONE).toInstant().toEpochMilli() }
                if (end != null && end < at) return@mapNotNull null
                val evidence = (refs(c.optJSONArray("evidence"), input) + mapped.values.flatMap { it.quotes.mapNotNull { q -> q.lineId } }).distinct()
                SkillCardRow(skillId = skill.id, skillVersion = skill.version, skillName = skill.name, icon = skill.icon,
                    date = date.toEpochDay(), entity = entity, at = at, endAt = end, title = title, summary = c.optString("summary"),
                    fieldsJson = SkillJson.fields(skill.fields).toString(), valuesJson = SkillJson.values(mapped).toString(),
                    evidenceJson = JSONArray(evidence).toString(), generatedAt = now, manual = manual)
            }
            return Parsed(rows, j.nullString("nothing_reason") ?: "没有符合要求的内容")
        } catch (e: LlmException) { throw e }
        catch (_: Exception) { throw LlmException(LlmError.Format) }
    }
    private fun ref(raw: Any?, input: SkillInput) = raw?.toString()?.let { if (it.startsWith("L")) it else "L$it" }?.let(input.lines::get)
    private fun refs(a: JSONArray?, input: SkillInput) = a.elements().mapNotNull { ref(it, input)?.id }.distinct()
    private fun strings(raw: Any?): List<String> = (raw as? JSONArray).elements().filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
    private fun value(type: FieldType, raw: Any?, input: SkillInput): FieldValue {
        val obj = raw as? JSONObject
        return when (type) {
            FieldType.Text, FieldType.Paragraph -> FieldValue(text = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() })
            FieldType.Bullets, FieldType.Tags -> FieldValue(items = strings(raw))
            FieldType.Checklist -> {
                val items = strings(obj?.optJSONArray("items") ?: raw)
                FieldValue(items = items, checked = items.indices.map { obj?.optJSONArray("checked")?.optBoolean(it, false) ?: false })
            }
            FieldType.Quotes -> FieldValue(quotes = (raw as? JSONArray).elements().mapNotNull { ref(it, input) }.distinctBy { it.id }.take(3).map {
                CardQuote(it.id, SkillInputs.speaker(it), it.startAt, it.text)
            })
            FieldType.Number -> FieldValue(number = (obj?.opt("number") as? Number)?.toDouble()?.takeIf { it.isFinite() }, unit = obj?.nullString("unit"))
            FieldType.Rating -> FieldValue(rating = (raw as? Number)?.toDouble()?.takeIf { it in 1.0..5.0 && it % 1.0 == 0.0 }?.toInt())
        }
    }
    private fun empty(v: FieldValue) = v.text.isNullOrBlank() && v.items.isEmpty() && v.quotes.isEmpty() && v.number == null && v.rating == null
}
