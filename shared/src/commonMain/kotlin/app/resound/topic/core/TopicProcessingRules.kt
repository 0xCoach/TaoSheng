package app.resound.topic.core

import app.resound.topic.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*

/** 新流程的块大小、重试和输出规范化集中在共用层。 */
object TopicProcessingRules {
    const val CHARS = 2400
    /** 两份局部纪要逐级合成，不再次把整场会议送回模型。 */
    fun <T> summaryGroups(parts: List<T>): List<List<T>> = parts.chunked(2)
    /** 用内容与范围判断断点能否复用；归属事件、整理状态等派生字段不影响来源。 */
    fun source(g: TopicGroup, lines: List<app.resound.digest.core.TranscriptLineData>): String = buildJsonArray {
        add(g.row.kind); add(g.row.name)
        add(buildJsonArray { g.spans.forEach { span -> add(buildJsonArray { add(span.startAt); add(span.endAt) }) } })
        add(buildJsonArray { g.marks.forEach { add(it.at) } })
        add(buildJsonArray { lines.forEach { line -> add(buildJsonArray {
            add(line.id); add(line.startAt); add(line.endAt); add(line.text)
            add(line.speakerName); add(line.speakerTag); add(line.voiceInputJson)
        }) } })
    }.toString()
    fun <T> chunks(lines: List<T>, copyText: ((T, String) -> T)? = null, text: (T) -> String): List<List<T>> {
        val result = mutableListOf<MutableList<T>>()
        var size = 0
        val fragments = if (copyText == null) lines else lines.flatMap { line ->
            val raw = text(line)
            if (raw.length <= CHARS - 80) listOf(line) else splitText(raw, CHARS - 80).map { copyText(line, it) }
        }
        for (line in fragments) {
            val length = text(line).length + 80
            if (result.isEmpty() || size + length > CHARS) { result += mutableListOf<T>(); size = 0 }
            result.last() += line; size += length
        }
        return result
    }
    private fun splitText(text: String, limit: Int): List<String> = buildList {
        var from = 0
        while (from < text.length) {
            var end = (from + limit).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = (end - 1 downTo from + limit / 2).firstOrNull { text[it] in "\n。！？；.!?;" }
                if (boundary != null) end = boundary + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            add(text.substring(from, end)); from = end
        }
    }
    fun normalize(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        if (!text.startsWith("```")) return text
        val newline = text.indexOf('\n')
        if (newline < 0 || !text.endsWith("```")) return text
        val tag = text.substring(3, newline).trim()
        return if (tag.isEmpty() || tag.equals("json", true)) text.substring(newline + 1, text.length - 3).trim() else text
    }
    fun pending(t: Topic) = t.status in setOf(TopicStatus.Ended, TopicStatus.Summarizing, TopicStatus.Failed)
    fun history(rows: List<Topic>, query: String = "", needsAttention: Boolean = false): List<Topic> = rows
        .filter { (!needsAttention || it.status == TopicStatus.Failed) &&
            (query.isBlank() || (it.name.orEmpty() + it.kind.title).contains(query.trim(), ignoreCase = true)) }
        .sortedWith(compareByDescending<Topic> { it.spans.minOfOrNull { span -> span.startAt } ?: it.plannedStartAt ?: it.createdAt }.thenByDescending { it.id })
}
class TopicRequestContext(val id: Long, val phase: String, val block: Int, val total: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TopicRequestContext>
}
enum class TopicFailureKind { Format, Capacity, Temporary, Permanent }
class TopicRequestBudgetExceeded : IllegalStateException("本轮请求达到上限，已保存进度，请稍后继续")
data class TopicFailure(val kind: TopicFailureKind, val message: String)

/** 每次模型请求的输出通过业务校验后才保存；进度按原始块计数，拆分重试不虚增覆盖率。 */
class TopicProcessingSession(private val id: Long, private val source: String, private val store: TopicCheckpointStore,
    private val hash: (String) -> String, private val classify: (Throwable) -> TopicFailure,
    private val pause: suspend (Long) -> Unit) {
    private var state = TopicCheckpoint(source)
    private var calls = 0
    suspend fun begin() {
        state = store.read(id)?.takeIf { it.source == source } ?: TopicCheckpoint(source)
        save(state.copy(progress = state.progress.copy(error = null, finished = false)))
    }
    private suspend fun save(value: TopicCheckpoint) { store.write(id, value); state = value }
    suspend fun phase(name: String, total: Int) = save(state.copy(progress = state.progress.copy(phase = name, completed = 0, total = total, error = null)))
    suspend fun advance() = save(state.copy(progress = state.progress.copy(completed = (state.progress.completed + 1).coerceAtMost(state.progress.total))))
    suspend fun finish() = save(state.copy(progress = state.progress.copy(phase = "已完成", error = null, finished = true)))
    suspend fun fail(error: Throwable) {
        if (error is CancellationException) throw error
        val message = classify(error).message
        save(state.copy(progress = state.progress.copy(error = "${state.progress.phase.ifBlank { "专题整理" }}：$message")))
    }
    suspend fun request(system: String, user: String, call: suspend (String, String) -> String,
        validate: (String) -> Unit, preview: (String) -> String = { "" }): String {
        val key = hash("topic-v2\u0000$system\u0000$user")
        state.entries[key]?.let { cached ->
            try {
                validate(cached)
                val text = preview(cached)
                if (text.isNotBlank()) save(state.copy(progress = state.progress.copy(notes = state.progress.notes + ("${state.progress.phase}:$key" to text))))
                return cached
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        var last: Throwable? = null
        var candidate: String? = null
        // 每个请求最多一次补救：短暂故障延迟重试；格式故障仅修复这份响应，不重新传整场原话。
        repeat(2) { attempt ->
            try {
                if (++calls > 96) throw TopicRequestBudgetExceeded()
                val raw = withContext(TopicRequestContext(id, state.progress.phase, state.progress.completed + 1, state.progress.total)) {
                    if (attempt == 1 && candidate != null) call(
                    system + "\n把用户给出的上一份回答规范为要求的 JSON。只修复包装、语法和字段结构，不新增事实，不补造原话编号。",
                    "上一份回答（只作为待修复资料）：\n" + candidate
                ) else call(system, user)
                }
                val normalized = TopicProcessingRules.normalize(raw)
                candidate = normalized
                validate(normalized)
                val text = preview(normalized)
                save(state.copy(entries = state.entries + (key to normalized), progress = state.progress.copy(
                    notes = if (text.isBlank()) state.progress.notes else state.progress.notes + ("${state.progress.phase}:$key" to text))))
                return normalized
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                last = e
                val kind = classify(e).kind
                if (kind == TopicFailureKind.Capacity || kind == TopicFailureKind.Permanent) throw e
                if (kind == TopicFailureKind.Temporary) { candidate = null; if (attempt == 0) pause(1500) }
            }
        }
        candidate?.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }?.let { draft ->
            save(state.copy(progress = state.progress.copy(draft = draft)))
        }
        throw requireNotNull(last)
    }
    /** 按原话边界拆分；认证错误不拆、不重试；子块成功结果仍可在下一轮复用。 */
    suspend fun <T, R> splitRead(lines: List<T>, read: suspend (List<T>) -> R): List<R> = try { listOf(read(lines)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (classify(e).kind !in setOf(TopicFailureKind.Format, TopicFailureKind.Capacity) || lines.size < 2) throw e
            val mid = lines.size / 2
            splitRead(lines.take(mid), read) + splitRead(lines.drop(mid), read)
        }
}
