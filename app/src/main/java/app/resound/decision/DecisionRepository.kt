package app.resound.decision

import app.resound.platform.NowMillis
import app.resound.data.MemoryDao
import app.resound.data.MemoryItemEntity
import app.resound.revise.MemoryRevisionDao
import app.resound.revise.MemoryRevisionRow
import app.resound.revise.RevisionOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 决策记忆仓库：决定的新建/否决/替代/撤回，全部留痕。
 * 同主题已有生效决定时返回冲突，由用户显式选择"替代旧决定"或"两条都保留"。
 */
class DecisionRepository(
    private val dao: DecisionDao,
    private val memory: MemoryDao,
    private val revisions: MemoryRevisionDao,
    private val io: CoroutineDispatcher,
    private val now: NowMillis,
    private val projects: app.resound.projects.ProjectDao? = null,
) {
    fun observeActive(): Flow<List<DecisionRow>> = dao.observeActive()
    fun observeHistory(): Flow<List<DecisionRow>> = dao.observeHistory()
    fun observeForProject(projectId: Long): Flow<List<DecisionRow>> = dao.observeForProject(projectId)
    fun observe(id: Long): Flow<DecisionRow?> = dao.observeDecision(id)
    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()
    fun search(query: String): Flow<List<DecisionRow>> = dao.search(app.resound.inbox.IdeaRules.likePattern(query))
    fun revisionsOf(id: Long): Flow<List<MemoryRevisionRow>> = revisions.history("decision", id)

    /** 新建决定。返回 (新决定 id, 同主题冲突的生效旧决定)——有冲突时不自动替代，让用户选。 */
    suspend fun create(
        title: String, detail: String, projectId: Long? = null, confidence: DecisionConfidence = DecisionConfidence.Sure,
        decidedAt: Long = now.now(), sourceLineId: Long? = null, sourceItemId: Long? = null,
        sourceEventId: Long? = null, sourceIdeaId: Long? = null, sourceText: String? = null,
    ): Pair<Long, List<DecisionRow>> = withContext(io) {
        val topicKey = DecisionRules.topicKey("$title $detail")
        val id = dao.insert(DecisionRow(
            title = title, detail = detail, projectId = projectId, confidence = confidence.name,
            topicKey = topicKey, decidedAt = decidedAt, sourceLineId = sourceLineId, sourceItemId = sourceItemId,
            sourceEventId = sourceEventId, sourceIdeaId = sourceIdeaId, sourceText = sourceText,
            createdAt = now.now(), updatedAt = now.now()))
        val conflicts = DecisionRules.conflicts(dao.activeSameTopic(topicKey, id), topicKey)
        log(id, RevisionOp.Edit, "", title, null)
        id to conflicts
    }

    /** 从整理出的"决定"类提取项导入；同一 item 只导入一次。
     *  证据门禁：没有原话佐证的降级为"随口"；文本判定只降不升——AI 不许把讨论说成决定。 */
    suspend fun importFromItem(item: MemoryItemEntity): Long? = withContext(io) {
        val line = item.lineId?.let { memory.lineById(it) }
        val evidence = item.sourceText ?: line?.text
        val confidence = if (evidence.isNullOrBlank()) DecisionConfidence.Tentative
            else maxOf(DecisionRules.guessConfidence("${item.content} $evidence"), DecisionConfidence.of(item.kind))
        // 项目归属随条目/事件的项目关联走，不再一律清空——决定要能上得了项目。
        val projectId = projects?.linksForRef(app.resound.projects.ProjectRefKind.Item.name, item.id)?.firstOrNull()?.projectId
            ?: item.eventId?.let { projects?.linksForRef(app.resound.projects.ProjectRefKind.Event.name, it)?.firstOrNull()?.projectId }
        val (id, _) = create(item.content, "", projectId, confidence, item.createdAt,
            sourceLineId = item.lineId, sourceItemId = item.id, sourceEventId = item.eventId,
            sourceText = evidence)
        id
    }

    /** 把 digest 里未导入的"决定/约定"类提取项补进来。返回导入数。 */
    suspend fun syncFromDigest(): Int = withContext(io) {
        var count = 0
        for (item in memory.itemsBetween(0, now.now()).first().filter { it.category in listOf("决定", "约定") && it.status != "Ignored" }) {
            // 同一 item 不重复导入：用 sourceItemId 判重
            if (dao.bySourceItem(item.id) == null) { importFromItem(item); count++ }
        }
        count
    }

    /** 替代：新决定生效，旧决定标 Superseded（记录 rejectedReason 可选），双方留痕。 */
    suspend fun supersede(oldId: Long, newId: Long, reason: String? = null): Boolean = withContext(io) {
        if (dao.supersede(oldId, newId, now.now()) == 0) return@withContext false
        log(oldId, RevisionOp.Supersede, "Active", "Superseded", buildJsonObject { put("by", JsonPrimitive(newId)) }, reason)
        true
    }

    /** 否决：记录被否决的原因，决定进入历史。 */
    suspend fun reject(id: Long, reason: String?): Boolean = withContext(io) {
        if (dao.reject(id, reason, now.now()) == 0) return@withContext false
        log(id, RevisionOp.Edit, "Active", "Rejected", reason = reason ?: "已否决")
        true
    }

    /** 确认仍有效：用户看过这条决定、认可它仍然是当前立场。只留痕不改状态。 */
    suspend fun reaffirm(id: Long): Boolean = withContext(io) {
        val d = dao.decision(id) ?: return@withContext false
        if (d.status != DecisionStatus.Active.name || d.deleted) return@withContext false
        dao.update(d.copy(updatedAt = now.now()))
        log(id, RevisionOp.Edit, d.title, d.title, reason = "确认仍有效")
        true
    }

    /** 撤回：本人表示这条不算数了（区分于被替代）。 */
    suspend fun withdraw(id: Long, reason: String? = null): Boolean = withContext(io) {
        if (dao.setStatus(id, DecisionStatus.Withdrawn.name, now.now()) == 0) return@withContext false
        log(id, RevisionOp.Edit, "Active", "Withdrawn", reason = reason)
        true
    }

    /** 人工改文本——留痕，AI 不得无痕改。 */
    suspend fun edit(id: Long, title: String, detail: String, reason: String? = null): Boolean = withContext(io) {
        val before = dao.decision(id) ?: return@withContext false
        dao.update(before.copy(title = title, detail = detail, topicKey = DecisionRules.topicKey("$title $detail"), updatedAt = now.now()))
        log(id, RevisionOp.Edit, before.title, title, reason = reason)
        true
    }

    suspend fun setProject(id: Long, projectId: Long?) = withContext(io) { dao.setProject(id, projectId, now.now()) }
    suspend fun delete(id: Long) = withContext(io) { dao.delete(id, now.now()) }

    /** 证据原文：快照优先，快照没有回查原话。 */
    suspend fun evidence(decision: DecisionRow): String? = withContext(io) {
        decision.sourceText ?: decision.sourceLineId?.let { memory.lineById(it)?.text }
    }

    private suspend fun log(id: Long, op: RevisionOp, before: String, after: String, extra: kotlinx.serialization.json.JsonObject? = null, reason: String? = null) =
        revisions.insert(MemoryRevisionRow(targetKind = "decision", targetId = id, op = op.name,
            beforeJson = buildJsonObject { put("v", JsonPrimitive(before)) }.toString(),
            afterJson = buildJsonObject { put("v", JsonPrimitive(after)) }.toString(),
            extraJson = (extra ?: buildJsonObject {}).toString(), reason = reason, at = now.now()))
}
