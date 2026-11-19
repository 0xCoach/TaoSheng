package app.resound.inbox

import app.resound.data.CaptureDatabase
import app.resound.data.MemoryItemEntity
import app.resound.data.MemoryDao
import app.resound.diagnostics.FailureNotes
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 灵感收件箱：独立灵感卡片 + 来源溯源 + 状态机 + 转换出口。无 AI 时手动功能全部可用。 */
class IdeaRepository(
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val io: CoroutineDispatcher,
) {
    private val dao get() = db.ideas()
    private val memory get() = db.memory()

    val count: Flow<Int> get() = dao.observeCount()

    fun list(status: IdeaStatus?): Flow<List<IdeaRow>> =
        if (status == null) dao.observeActive() else dao.observeByStatus(status.name)

    fun forProject(projectId: Long): Flow<List<IdeaRow>> = dao.observeForProject(projectId)

    fun observe(id: Long): Flow<IdeaRow?> = dao.observeIdea(id)

    fun search(query: String): Flow<List<IdeaRow>> =
        if (query.isBlank()) dao.observeActive() else dao.search(IdeaRules.likePattern(query.trim()))

    suspend fun create(
        title: String,
        content: String,
        category: String? = null,
        tags: List<String> = emptyList(),
        sourceItemId: Long? = null,
        sourceLineId: Long? = null,
        sourceSegmentId: Long? = null,
        sourceEventId: Long? = null,
        sourceText: String? = null,
    ): Long = withContext(io) {
        val body = content.ifBlank { title }
        dao.insert(
            IdeaRow(
                title = title.ifBlank { body.take(30) },
                content = body,
                category = category ?: IdeaRules.suggestCategory(body),
                tagsJson = tagsJson(tags),
                fingerprint = IdeaRules.fingerprint(body),
                sourceItemId = sourceItemId,
                sourceLineId = sourceLineId,
                sourceSegmentId = sourceSegmentId,
                sourceEventId = sourceEventId,
                sourceText = sourceText,
                sourceItemIdsJson = idsJson(sourceItemId?.let { listOf(it) } ?: emptyList()),
                createdAt = now.now(),
                updatedAt = now.now(),
            ),
        )
    }

    /** 从一句原话手动标记灵感；同一句话不重复建。 */
    suspend fun markFromLine(lineId: Long, segmentId: Long, text: String): Long = withContext(io) {
        dao.bySourceLine(lineId).firstOrNull()?.id ?: run {
            val eventId = runCatching { memory.lineById(lineId)?.eventId }.getOrNull()
            create(title = "", content = text,
                sourceLineId = lineId, sourceSegmentId = segmentId, sourceEventId = eventId,
                sourceText = text)
        }
    }

    /**
     * 快捷“记灵感”：按下那一刻记下时间点并关联当前音频片段。
     * 同一片段 60 秒内的重复动作返回已有卡（幂等），片段可能还没转写——原文后补。
     */
    suspend fun markMoment(segmentId: Long?, at: Long, note: String? = null): Long = withContext(io) {
        if (segmentId != null) dao.bySourceSegmentWindow(segmentId, at - 60_000)?.let { return@withContext it.id }
        val clock = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val body = note?.takeIf { it.isNotBlank() } ?: "标记时刻 $clock（待转写）"
        // 没有片段可关联时也靠指纹去重：一分钟内的重复点按返回同一条
        dao.sameFingerprint(IdeaRules.fingerprint(body)).firstOrNull { it.createdAt > at - 60_000 }?.let { return@withContext it.id }
        create(title = "", content = body, sourceSegmentId = segmentId)
    }

    /** 从整理出的“灵感”提取项建卡；已有则返回旧卡。 */
    suspend fun fromItem(item: MemoryItemEntity): Long = withContext(io) {
        dao.bySourceItem(item.id)?.id ?: create(item.content.take(30), item.content, sourceItemId = item.id,
            sourceLineId = item.lineId, sourceEventId = item.eventId, sourceText = item.sourceText)
    }

    /** 把整理出的“灵感”提取项同步进收件箱；已删除/已合并的灵感留墓碑不再复活。返回新增数。 */
    suspend fun syncFromDigest(onEvent: suspend (key: String) -> Unit = {}): Int = withContext(io) {
        var added = 0
        val items = memory.inspirationItems()
        for (item in items) {
            if (dao.anyBySourceItem(item.id) != null) continue
            runCatching { fromItem(item); added++ }.onFailure { FailureNotes.record("inbox/IdeaRepository.kt:sync", it) }
        }
        if (added > 0) onEvent("idea_created")
        added
    }

    suspend fun setStatus(id: Long, status: IdeaStatus): Boolean = withContext(io) {
        val idea = dao.idea(id) ?: return@withContext false
        val from = IdeaStatus.of(idea.status)
        if (!IdeaStatus.canMove(from, status)) return@withContext false
        dao.setStatus(id, status.name, now.now()) > 0
    }

    suspend fun update(id: Long, title: String, content: String, category: String, tags: List<String>) = withContext(io) {
        val idea = dao.idea(id) ?: return@withContext
        dao.update(
            idea.copy(
                title = title.ifBlank { content.take(30) },
                content = content,
                category = category.ifBlank { idea.category },
                tagsJson = tagsJson(tags),
                fingerprint = IdeaRules.fingerprint(content),
                updatedAt = now.now(),
            ),
        )
    }

    /** 疑似重复：指纹相同 + 前缀相似的活跃灵感 */
    suspend fun duplicatesOf(id: Long): List<IdeaRow> = withContext(io) {
        val idea = dao.idea(id) ?: return@withContext emptyList()
        val exact = dao.sameFingerprint(idea.fingerprint, id)
        val near = dao.similar(IdeaRules.prefixPattern(idea.content), id)
        (exact + near).distinctBy { it.id }.filter { IdeaRules.isDuplicate(idea.fingerprint, it.fingerprint) || exact.contains(it) }
    }

    /** 把 source 合并进 target：聚合来源项，source 不再单列。 */
    suspend fun merge(sourceId: Long, targetId: Long) = withContext(io) {
        val source = dao.idea(sourceId) ?: return@withContext
        val target = dao.idea(targetId) ?: return@withContext
        dao.transaction {
            val merged = (idList(target.sourceItemIdsJson) + idList(source.sourceItemIdsJson) +
                listOfNotNull(source.sourceItemId, target.sourceItemId)).distinct()
            dao.updateSourceItems(targetId, idsJson(merged), now.now())
            dao.mergeInto(sourceId, targetId, now.now())
        }
    }

    suspend fun setProject(id: Long, projectId: Long?) = withContext(io) { dao.setProject(id, projectId, now.now()) }

    suspend fun markConverted(id: Long, kind: String, convertedId: Long) =
        withContext(io) { dao.markConverted(id, kind, convertedId, now.now()) }

    /** 灵感转待办：写进整理条目（Accepted 状态，进“进行中”），返回新条目 id。 */
    suspend fun convertToTodo(id: Long): Long = withContext(io) {
        val idea = dao.idea(id) ?: return@withContext 0L
        val item = MemoryItemEntity(
            eventId = idea.sourceEventId, category = "待办", kind = null,
            content = "研究灵感：${idea.title.ifBlank { idea.content.take(60) }}",
            dueText = null, dueAt = null, who = null, lineId = idea.sourceLineId,
            recordedAt = idea.sourceLineId?.let { memory.lineStartAt(it) },
            status = "Accepted", createdAt = now.now(), origin = "idea", sourceText = idea.sourceText ?: idea.content,
            valueScore = app.resound.data.MemoryValueRules.itemScore("待办", null, null, null, "idea"),
        )
        dao.transaction {
            val itemId = memory.addManualItem(item)
            dao.markConverted(id, "Todo", itemId, now.now())
            itemId
        }
    }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id, now.now()) }

    /** 原始出处文本（快照优先，其次查当前句子）。 */
    suspend fun sourceText(idea: IdeaRow): String? = withContext(io) {
        idea.sourceText ?: idea.sourceLineId?.let { memory.lineById(it)?.text }
    }

    companion object {
        fun tagsJson(values: List<String>): String =
            buildJsonArray { values.filter { it.toString().isNotBlank() }.distinct().forEach { add(JsonPrimitive(it.toString())) } }.toString()
        fun idsJson(values: List<Long>): String =
            buildJsonArray { values.distinct().forEach { add(JsonPrimitive(it)) } }.toString()
        fun idList(json: String): List<Long> = runCatching {
            (app.resound.platform.SharedJson.parseToJsonElement(json) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()
        }.getOrDefault(emptyList())
        fun tagList(json: String): List<String> = runCatching {
            (app.resound.platform.SharedJson.parseToJsonElement(json) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
