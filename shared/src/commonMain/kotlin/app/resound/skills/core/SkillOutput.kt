package app.resound.skills.core

import app.resound.skills.*


import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

object SkillOutput {
    data class Parsed(val rows: List<SkillCardData>, val reason: String)
    fun parse(content: String, skill: Skill, date: LocalDate, input: SkillInput, now: Long, manual: Boolean): Parsed {
        try {
            val j = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
            val cards = j.getJSONArray("cards")
            val rows = cards.elements().mapNotNull { raw ->
                val c = raw as? JSONObject ?: throw SkillFormatException()
                val values = c.getJSONObject("values")
                val mapped = skill.fields.associate { f -> f.key to value(f.type, values.opt(f.key), input) }
                if (skill.fields.any { it.required && empty(mapped.getValue(it.key)) }) return@mapNotNull null
                val title = c.nullString("title") ?: return@mapNotNull null
                val entity = if (skill.maxCardsPerDay == 1) date.toString() else c.nullString("entity") ?: return@mapNotNull null
                // 无门槛的一日总结沿用运行时刻；有门槛的卡保留自己的时段，供原话复核。
                val at = if (skill.maxCardsPerDay == 1 && skill.gate == null) {
                    val planned = (skill.trigger as? SkillTrigger.Daily)?.let { date.atMillis(LocalTime(it.minutes / 60, it.minutes % 60)) }
                    val endOfDay = date.plusDays(1).startMillis() - 60_000
                    minOf(planned ?: now, now, endOfDay)
                } else date.atMillis(LocalTime.parse(c.getString("at")))
                val end = c.nullString("end")?.let { date.atMillis(LocalTime.parse(it)) }
                if (end != null && end < at) return@mapNotNull null
                val evidence = (refs(c.optJSONArray("evidence"), input) + mapped.values.flatMap { it.quotes.mapNotNull { q -> q.lineId } }).distinct()
                SkillCardData(skillId = skill.id, skillVersion = skill.version, skillName = skill.name, icon = skill.icon,
                    date = date.toEpochDay(), entity = entity, at = at, endAt = end, title = title, summary = c.optString("summary"),
                    fieldsJson = SkillJson.fields(skill.fields).toString(), valuesJson = SkillJson.values(mapped).toString(),
                    evidenceJson = JSONArray(evidence).toString(), generatedAt = now, manual = manual)
            }
            return Parsed(rows, j.nullString("nothing_reason") ?: "没有符合要求的内容")
        } catch (e: SkillFormatException) { throw e }
        catch (_: Exception) { throw SkillFormatException() }
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

class SkillFormatException : IllegalArgumentException("模型回复格式不对")
