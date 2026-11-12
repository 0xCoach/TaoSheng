package app.resound.digest

import app.resound.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate
import app.resound.digest.core.DigestPrompt as Core

internal fun ZoneId.sharedZone() = kotlinx.datetime.TimeZone.of(id)
internal inline fun <T> promptResult(block: () -> T): T = try { block() } catch (e: app.resound.net.NetLlmException) {
    throw LlmException(LlmError.valueOf(e.type.name))
}
/** 安卓日期、Room 快照和诊断适配；规则在 shared。 */
object DigestPrompt {
    const val VOICE_INPUT_RULES = Core.VOICE_INPUT_RULES
    val CATEGORIES = Core.CATEGORIES
    internal val NO_ITEMS_SCENES = setOf("媒体")
    fun calendar(today: LocalDate) = Core.calendar(today.toKotlinLocalDate())
    fun system(template: String, today: LocalDate, appName: String) = Core.system(template, today.toKotlinLocalDate(), appName)
    fun transcript(lines: List<TranscriptLineEntity>, zone: ZoneId = ZoneId.systemDefault(), unseparated: Set<Long> = emptySet(), situationHeadings: Map<Int, String> = emptyMap()) =
        Core.transcript(lines.map { it.snapshot() }, zone.sharedZone(), unseparated, situationHeadings).first to lines
    fun withHotwords(text: String, words: List<String>) = Core.withHotwords(text, words)
    fun withTodoContext(text: String, unfinished: List<MemoryItemEntity>, ignored: List<MemoryItemEntity>) = Core.withTodoContext(text, unfinished.map { it.snapshot() }, ignored.map { it.snapshot() })
    fun doneHints(content: String, lines: List<TranscriptLineEntity>, allowed: Set<Long>) = Core.doneHints(content, lines.map { it.snapshot() }, allowed)
    fun speaker(line: TranscriptLineEntity) = Core.speaker(line.snapshot())
    fun parseDue(text: String, zone: ZoneId = ZoneId.systemDefault()) = Core.parseDue(text, zone.sharedZone())
    data class ParsedEvent(
        val lineIds: List<Long>,
        val startAt: Long,
        val endAt: Long,
        val scene: String,
        val title: String,
        val summary: String,
        val points: List<String>,
        val people: List<String>,
        val place: String?,
        val emotion: String?,
        val intensity: Int?,
        val important: Boolean,
    )

    data class ParsedItem(
        /** 属于第几个事件（parse 结果里的下标） */
        val eventIndex: Int,
        val category: String,
        val kind: String?,
        val content: String,
        val dueText: String?,
        val dueAt: Long?,
        val who: String?,
        val lineId: Long?,
    )

    fun parse(content: String, lines: List<TranscriptLineEntity>, zone: ZoneId = ZoneId.systemDefault(), unseparated: Set<Long> = emptySet(),
        diagnostics: app.resound.diagnostics.LlmResponseTrace? = null): Pair<List<ParsedEvent>, List<ParsedItem>> {
        return try {
            val result = promptResult { Core.parse(content, lines.map { it.snapshot() }, zone.sharedZone(), unseparated) }.let { (events, items) -> events.map { it.android() } to items.map { it.android() } }
            runCatching {
                val raw = app.resound.diagnostics.responseObject(content)
                val rawEvents = raw?.optJSONArray("events")?.length() ?: 0
                val rawItems = raw?.optJSONArray("items")?.length() ?: 0
                diagnostics?.parsed(result.first.size, result.second.size, empty = rawEvents == 0 && rawItems == 0,
                    filtered = (rawEvents > 0 && result.first.isEmpty()) || (rawItems > 0 && result.second.isEmpty()))
            }
            result
        } catch (e: LlmException) {
            diagnostics?.parsed(empty = content.isBlank(), error = e.type)
            throw e
        }
    }

}
/** 取字符串数组里的非空项。放在文件顶层，方便 [MergePrompt] 等同包代码复用。 */
internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() && s != "null" } }

/** 取可选字符串，缺失、空串或 "null" 返回 null。放在文件顶层，方便 [MergePrompt] 等同包代码复用。 */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

internal fun Core.ParsedEvent.android() = DigestPrompt.ParsedEvent(lineIds, startAt, endAt, scene, title, summary, points, people, place, emotion, intensity, important)

internal fun Core.ParsedItem.android() = DigestPrompt.ParsedItem(eventIndex, category, kind, content, dueText, dueAt, who, lineId)
