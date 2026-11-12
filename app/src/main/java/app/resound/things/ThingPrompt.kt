package app.resound.things

import app.resound.data.TranscriptLineEntity
import java.time.LocalDate

internal data class ThingProposal(val row: ThingRecordRow, val confident: Boolean)

internal object ThingPrompt {
    fun system(template: String, today: LocalDate = LocalDate.now()) = ThingPromptRules.system(template, today.toString())
    fun context(lines: List<TranscriptLineEntity>, previous: List<TranscriptLineEntity>): List<TranscriptLineEntity> {
        val ids = ThingPromptRules.context(lines.map { it.shared() }, previous.map { it.shared() }).map { it.id }.toSet()
        return previous.filter { it.id in ids }
    }
    fun known(lines: List<TranscriptLineEntity>, context: List<TranscriptLineEntity>, known: List<Thing>) =
        ThingPromptRules.known(lines.map { it.shared() }, context.map { it.shared() }, known)
    fun input(lines: List<TranscriptLineEntity>, context: List<TranscriptLineEntity>, known: List<Thing>, manual: Boolean = false) =
        ThingPromptRules.input(lines.map { it.shared() }, context.map { it.shared() }, known, manual)
    fun parse(content: String, lines: List<TranscriptLineEntity>, known: Set<Long>, now: Long) =
        ThingPromptRules.parse(normalizeThingJson(content), lines.map { it.shared() }, known, now).map { ThingProposal(it.row.room(), it.confident) }
}

/** 保留旧模型回复解析的宽容语法；跨平台规则只接收标准 JSON。 */
internal fun normalizeThingJson(content: String): String = org.json.JSONObject(
    content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")) .toString()
