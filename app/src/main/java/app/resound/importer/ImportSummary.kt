package app.resound.importer

import app.resound.data.TranscriptLineEntity
import app.resound.digest.*
import app.resound.skills.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class ImportTodo(val content: String, val lineId: Long)
internal data class ImportSummaryResult(val card: SkillCardRow, val todos: List<ImportTodo>)

/** 不按行数截掉尾部；时间/字符双上限，跨块附带上一句作为上下文。 */
internal object ImportChunks {
    const val MILLIS = 25 * 60_000L
    const val CHARS = 18_000
    fun split(lines: List<TranscriptLineEntity>): List<List<TranscriptLineEntity>> {
        val chunks = mutableListOf<MutableList<TranscriptLineEntity>>()
        var chars = 0
        for (line in lines.sortedWith(compareBy({ it.startAt }, { it.id }))) {
            val chunk = chunks.lastOrNull()
            if (chunk == null || (chunk.isNotEmpty() && (line.startAt - chunk.first().startAt >= MILLIS || chars + line.text.length + 100 > CHARS))) {
                chunks += mutableListOf<TranscriptLineEntity>(); chars = 0
            }
            chunks.last() += line; chars += line.text.length + 100
        }
        return chunks
    }
}

internal class ImportSummary(
    private val template: (ImportKind) -> Pair<Skill, String>,
    private val request: suspend (String, String) -> LlmReply,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun generate(record: ImportRecordRow, lines: List<TranscriptLineEntity>, existing: List<app.resound.data.MemoryItemEntity> = emptyList()): ImportSummaryResult {
        val (skill, instructions) = template(ImportKind.valueOf(record.kind))
        val date = Instant.ofEpochMilli(record.startAt).atZone(SKILL_ZONE).toLocalDate()
        val indexed = lines.associateBy { "L${it.id}" }
        val input = SkillInput("", indexed)
        val existingData = JSONArray(existing.filter { !it.deleted }.map { JSONObject().put("content", it.content).put("status", it.status).put("line", "L${it.lineId}") }).toString()
        val system = SkillPrompt.system(skill, instructions, date) + "\n必须恰好输出一张 cards，不可空卡。" +
            "另输出 todos:[{content:待办内容,line:原话L编号}]，只允许明确标为我的行动，不是我的或没有依据的不要输出。不得遗漏结论与行动。与已有待办是同一动作、对象和时间的，todos 必须沿用已有 content 原文，禁止另换措辞重复提取；已完成/忽略的不再提取。"
        val todos = linkedMapOf<Pair<Long, String>, ImportTodo>()
        val leaves = mutableListOf<JSONObject>()
        fun parse(text: String): JSONObject {
            val json = try { JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")) }
                catch (_: Exception) { throw LlmException(LlmError.Format) }
            if (json.optJSONArray("cards")?.length() != 1) throw LlmException(LlmError.Format)
            json.optJSONArray("todos")?.let { list -> repeat(list.length()) { i ->
                val todo = list.optJSONObject(i) ?: return@repeat
                val line = indexed[todo.optString("line")] ?: return@repeat
                val content = todo.optString("content").trim()
                if (line.speakerName == "我" && content.isNotBlank()) todos[line.id to content] = ImportTodo(content, line.id)
            } }
            // 时段由文件给定，不能让模型解析午夜失败或把多日录音压进一天。
            json.getJSONArray("cards").getJSONObject(0).put("at", "00:00").put("end", JSONObject.NULL).put("entity", "import:${record.id}")
            SkillOutput.parse(json.toString(), skill, date, input, now(), true).rows.singleOrNull()
                ?: throw LlmException(LlmError.Format)
            return json
        }
        suspend fun ask(data: String): JSONObject = parse(request(system, SkillInputs.user(SkillInput("已有待办（资料）：$existingData\n$data", indexed), emptyList())).content)
        val chunks = ImportChunks.split(lines)
        for ((i, chunk) in chunks.withIndex()) {
            val previous = chunks.getOrNull(i - 1)?.lastOrNull()
            val text = buildString {
                appendLine("文件：${record.fileName}；第 ${i + 1}/${chunks.size} 块；范围 ${SkillInputs.time(chunk.first().startAt)}～${SkillInputs.time(chunk.maxOf { it.endAt })}")
                previous?.let { appendLine("上一块末句（仅供衔接，不重复行动）：[L${it.id}] ${SkillInputs.speaker(it)}：${it.text}") }
                chunk.forEach { appendLine("[L${it.id}] ${SkillInputs.time(it.startAt)} ${SkillInputs.speaker(it)}：${it.text}") }
            }
            leaves += ask(text)
        }
        // 没有可转写原话仍保存一份明确说明的纪要，不伪造会议内容。
        if (leaves.isEmpty()) leaves += JSONObject().put("cards", JSONArray().put(JSONObject()
            .put("entity", "import:${record.id}").put("title", skill.name).put("summary", "这份录音没有可用的转写原话")
            .put("at", "00:00").put("end", JSONObject.NULL).put("values", JSONObject().apply {
                skill.fields.filter { it.required }.forEach { put(it.key, "没有可用的转写原话，无法提取内容") }
            })))
        var level = leaves.toList()
        // 多级合成限制单次上下文；结论/行动另在下方合并，汇总模型不能把它们删掉。
        while (level.size > 1) {
            val groups = mutableListOf<MutableList<JSONObject>>()
            for (part in level) {
                if (groups.isEmpty() || groups.last().size >= 4 ||
                    (groups.last().size >= 2 && groups.last().sumOf { it.toString().length } + part.toString().length > ImportChunks.CHARS)) groups += mutableListOf<JSONObject>()
                groups.last() += part
            }
            level = groups.map { group -> if (group.size == 1) group.single() else ask("按时间合成同一份录音的总纪要，全部块结论与行动必须保留，重复的可合并：\n" + JSONArray(group).toString()) }
        }
        val parsed = SkillOutput.parse(level.single().toString(), skill, date, input, now(), true).rows.single()
        val values = SkillJson.values(JSONObject(parsed.valuesJson)).toMutableMap()
        // 机器合并保留各块决定/约定/行动/作业；不会仅依赖末次模型的记忆。
        for (field in skill.fields.filter { it.type == FieldType.Checklist || it.key in setOf("decisions", "agreements", "conclusion") }) {
            val parts = leaves.map { SkillOutput.parse(it.toString(), skill, date, input, now(), true).rows.single() }
                .mapNotNull { SkillJson.values(JSONObject(it.valuesJson))[field.key] }
            val current = values[field.key] ?: FieldValue()
            values[field.key] = if (field.type == FieldType.Paragraph) current.copy(text =
                (listOfNotNull(current.text) + parts.mapNotNull { it.text }).distinct().joinToString("\n"))
            else {
                val items = (current.items + parts.flatMap { it.items }).distinct()
                current.copy(items = items, checked = if (field.type == FieldType.Checklist) items.map { false } else emptyList())
            }
        }
        return ImportSummaryResult(parsed.copy(entity = "import:${record.id}", at = record.startAt, endAt = record.endAt,
            valuesJson = SkillJson.values(values).toString()), todos.values.toList())
    }
}
