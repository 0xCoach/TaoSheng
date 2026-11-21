package app.resound.revise

import app.resound.platform.NowMillis
import app.resound.data.MemoryDao
import app.resound.data.MemoryItemEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 记忆纠错中心：编辑/删除/合并/拆分/恢复提取项，每次改动写 memory_revisions 留痕，可撤销。
 * 所有纠错（包括别的模块改记忆）都应走这里，不允许无痕覆盖。
 */
class MemoryCorrector(
    private val memory: MemoryDao,
    private val revisions: MemoryRevisionDao,
    private val io: CoroutineDispatcher,
    private val now: NowMillis,
) {
    fun history(targetKind: String, targetId: Long): Flow<List<MemoryRevisionRow>> = revisions.history(targetKind, targetId)
    fun recent(): Flow<List<MemoryRevisionRow>> = revisions.observeRecent()

    private fun snapshot(item: MemoryItemEntity?): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(item?.id ?: 0))
        put("content", JsonPrimitive(item?.content ?: ""))
        put("category", JsonPrimitive(item?.category ?: ""))
        put("status", JsonPrimitive(item?.status ?: ""))
        put("dueAt", JsonPrimitive(item?.dueAt ?: -1))
        put("dueText", JsonPrimitive(item?.dueText ?: ""))
        put("who", JsonPrimitive(item?.who ?: ""))
        put("deleted", JsonPrimitive(item?.deleted ?: false))
        put("mergedInto", JsonPrimitive(item?.mergedInto ?: -1))
    }

    private suspend fun log(kind: String, id: Long, op: RevisionOp, before: JsonObject, after: JsonObject, extra: JsonObject? = null, reason: String? = null): Long =
        revisions.insert(MemoryRevisionRow(targetKind = kind, targetId = id, op = op.name,
            beforeJson = before.toString(), afterJson = after.toString(), extraJson = extra?.toString() ?: "{}", reason = reason, at = now.now()))

    /** 编辑提取项内容/时间/责任人。 */
    suspend fun editItem(id: Long, content: String, dueAt: Long?, dueText: String?, who: String?, reason: String? = null): Boolean = withContext(io) {
        val before = memory.itemIncludingDeleted(id) ?: return@withContext false
        memory.updateItemRow(id, content, dueAt, dueText, who, now.now())
        log("item", id, RevisionOp.Edit, snapshot(before), snapshot(memory.itemIncludingDeleted(id)), reason = reason)
        true
    }

    /** 删除提取项（软删，可撤销）。 */
    suspend fun deleteItem(id: Long, reason: String? = null): Boolean = withContext(io) {
        val before = memory.itemIncludingDeleted(id) ?: return@withContext false
        memory.deleteItem(id)
        log("item", id, RevisionOp.Delete, snapshot(before), snapshot(memory.itemIncludingDeleted(id)), reason = reason)
        true
    }

    /** 把 source 合并进 target（复用现有 mergedInto 机制），留痕。 */
    suspend fun mergeItem(sourceId: Long, targetId: Long, reason: String? = null): Boolean = withContext(io) {
        val before = memory.itemIncludingDeleted(sourceId) ?: return@withContext false
        if (before.deleted || before.mergedInto != null) return@withContext false
        memory.mergeInto(sourceId, targetId)
        val extra = buildJsonObject { put("targetId", JsonPrimitive(targetId)) }
        log("item", sourceId, RevisionOp.Merge, snapshot(before), snapshot(memory.itemIncludingDeleted(sourceId)), extra = extra, reason = reason)
        true
    }

    /** 拆分：原条目保留前半，后半成新条目。cutAt 是 content 的字符切点。 */
    suspend fun splitItem(id: Long, cutAt: Int, reason: String? = null): Long? = withContext(io) {
        val before = memory.itemIncludingDeleted(id) ?: return@withContext null
        if (before.deleted || cutAt !in 1 until before.content.length) return@withContext null
        val first = before.content.substring(0, cutAt).trim()
        val second = before.content.substring(cutAt).trim()
        if (first.isEmpty() || second.isEmpty()) return@withContext null
        memory.updateItemRow(id, first, before.dueAt, before.dueText, before.who, now.now())
        val newId = memory.addManualItem(before.copy(id = 0, content = second, createdAt = now.now(),
            editedAt = null, mergedInto = null, deleted = false, noMerge = false, origin = "manual"))
        val extra = buildJsonObject { put("newId", JsonPrimitive(newId)); put("cutAt", JsonPrimitive(cutAt)) }
        log("item", id, RevisionOp.Split, snapshot(before), snapshot(memory.itemIncludingDeleted(id)), extra = extra, reason = reason)
        newId
    }

    /** 撤销最近一次可撤销操作（编辑/删除/拆分）。返回是否成功。 */
    suspend fun undo(revisionId: Long): Boolean = withContext(io) {
        val rev = revisions.revision(revisionId) ?: return@withContext false
        if (rev.undoneBy != null || rev.targetKind != "item") return@withContext false
        val ok = when (RevisionOp.of(rev.op)) {
            RevisionOp.Edit -> {
                val content = str(rev.beforeJson, "content") ?: return@withContext false
                val dueAt = lng(rev.beforeJson, "dueAt")?.takeIf { it > 0 }
                memory.updateItemRow(rev.targetId, content, dueAt, str(rev.beforeJson, "dueText"), str(rev.beforeJson, "who"), now.now())
                true
            }
            RevisionOp.Delete -> memory.restoreItem(rev.targetId) > 0
            RevisionOp.Split -> {
                val content = str(rev.beforeJson, "content") ?: return@withContext false
                memory.updateItemRow(rev.targetId, content, lng(rev.beforeJson, "dueAt")?.takeIf { it > 0 },
                    str(rev.beforeJson, "dueText"), str(rev.beforeJson, "who"), now.now())
                val newId = lng(rev.extraJson, "newId")
                if (newId != null && newId > 0) memory.deleteItem(newId)
                true
            }
            else -> false
        }
        if (ok) {
            val undoRev = log("item", rev.targetId, RevisionOp.Undo, rev.afterJson.let(::parseObj), rev.beforeJson.let(::parseObj), reason = "撤销 ${RevisionOp.of(rev.op).label}")
            revisions.markUndone(rev.id, undoRev)
        }
        ok
    }

    private fun str(json: String, key: String): String? = parseObj(json)[key]?.let { (it as? JsonPrimitive)?.content }?.ifEmpty { null }
    private fun lng(json: String, key: String): Long? = parseObj(json)[key]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
    private fun parseObj(json: String): JsonObject = runCatching { app.resound.platform.SharedJson.parseToJsonElement(json) as JsonObject }.getOrElse { buildJsonObject {} }
}
