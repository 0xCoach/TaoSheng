package app.resound.followup

import app.resound.platform.NowMillis
import app.resound.data.MemoryDao
import app.resound.inbox.IdeaDao
import app.resound.inbox.IdeaStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主动跟进引擎：定期扫描"说过要做但没下文"的事项，去重入库、限频提醒。
 * 三件事的边界写死在 kind 里：Commitment=用户承诺、Todo=待办超时、Idea=灵感搁置、
 * Suggestion=AI/系统建议——提醒文案按 kind 区分，建议绝不当承诺。
 */
class FollowupEngine(
    private val dao: FollowupDao,
    private val ideas: IdeaDao,
    private val memory: MemoryDao,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val notify: suspend (title: String, body: String, deepLink: String?) -> Unit,
    private val onEvent: suspend (key: String) -> Unit = {},
) {
    /** 有新提醒发出时广播给规则引擎（followup_due 事件） */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun observeOpen(): Flow<List<FollowupRow>> = dao.observeOpen()
    fun observeClosed(): Flow<List<FollowupRow>> = dao.observeClosed()
    fun observeOpenCount(): Flow<Int> = dao.observeOpenCount()

    private fun staleTodoCutoff() = now.now() - TODO_STALE_MS
    private fun staleIdeaCutoff() = now.now() - IDEA_STALE_MS

    /** 手动登记一条承诺（用户自己说要做的事）。 */
    suspend fun add(title: String, detail: String = "", kind: FollowupKind = FollowupKind.Commitment,
        refKind: String? = null, refId: Long? = null, sourceText: String? = null, sourceLineId: Long? = null): Long = withContext(io) {
        if (refKind != null && refId != null) dao.openByRef(refKind, refId)?.let { return@withContext it.id }
        dao.insert(FollowupRow(kind = kind.name, refKind = refKind, refId = refId, title = title, detail = detail,
            sourceText = sourceText, sourceLineId = sourceLineId,
            createdAt = now.now(), nextCheckAt = now.now(), updatedAt = now.now()))
    }

    /**
     * 扫一轮：复活到期稍后项 → 发现新落后项（去重）→ 对到期项限频提醒。
     * 每天/每次启动跑都行，本身幂等且限频。
     */
    suspend fun scan(): Int = withContext(io) {
        val at = now.now()
        dao.reopenSnoozed(at, at, at)
        // 落后待办：接受状态、没完成、创建超过一天
        val staleTodos = memory.itemsBetween(0, staleTodoCutoff()).first()
            .filter { it.category in listOf("待办", "约定", "备忘") && it.status == "Accepted" && it.doneAt == null }
        for (item in staleTodos) {
            if (dao.openByRef("item", item.id) != null) continue
            dao.insert(FollowupRow(kind = FollowupKind.Todo.name, refKind = "item", refId = item.id,
                title = item.content.take(80), detail = item.dueText ?: "",
                sourceText = item.sourceText, sourceLineId = item.lineId,
                createdAt = item.createdAt, nextCheckAt = at, updatedAt = at))
        }
        // 搁置灵感：待研究/研究中超过两天没动
        for (idea in ideas.activeList().filter {
            it.status in listOf(IdeaStatus.Research.name, IdeaStatus.Studying.name) && it.createdAt < staleIdeaCutoff() }) {
            if (dao.openByRef("idea", idea.id) != null) continue
            dao.insert(FollowupRow(kind = FollowupKind.Idea.name, refKind = "idea", refId = idea.id,
                title = idea.title.take(80), detail = idea.category,
                sourceText = idea.sourceText, sourceLineId = idea.sourceLineId,
                createdAt = idea.createdAt, nextCheckAt = at, updatedAt = at))
        }
        remind(at)
    }

    /** 对到期项发提醒：每日上限 + 单项间隔 + 提醒降频，全在 FollowupRules 里。 */
    private suspend fun remind(at: Long): Int {
        val due = dao.due(at)
        if (due.isEmpty()) return 0
        var sent = 0
        for (row in due) {
            val remindedToday = dao.remindedToday(dayStart(at))
            if (!FollowupRules.canRemind(row, at, remindedToday)) continue
            runCatching { notify("声声不息提醒", FollowupRules.reminderText(row), "app.resound://assistant?open=followups") }
                .onSuccess {
                    dao.markReminded(row.id, at, FollowupRules.nextCheckAt(row.remindCount, at))
                    sent++
                    onEvent("followup_due")
                }
        }
        return sent
    }

    /** 标记完成并回写源对象：跟进项的"做了"要推进真实待办/灵感，不只是关掉提醒。 */
    suspend fun markDone(id: Long) = withContext(io) {
        val row = dao.followup(id) ?: return@withContext
        dao.setStatus(id, FollowupStatus.Done.name, now.now(), now.now())
        when (row.refKind) {
            "item" -> row.refId?.let { memory.setItemStatus(it, "Done", now.now()) }
            "idea" -> row.refId?.let { ideas.setStatus(it, IdeaStatus.Done.name, now.now()) }
        }
    }

    /** 关闭提醒但不回写源对象："不值得提醒"≠"事不存在"，承诺/灵感本身仍保留。 */
    suspend fun dismiss(id: Long) = withContext(io) { dao.setStatus(id, FollowupStatus.Dismissed.name, now.now(), now.now()) }
    suspend fun snooze(id: Long, hours: Int = 24) = withContext(io) { dao.snooze(id, now.now() + hours * 3600_000L, now.now()) }
    suspend fun delete(id: Long) = withContext(io) { dao.delete(id, now.now()) }

    private fun dayStart(at: Long): Long = at - at % (24 * 3600_000L) // UTC 粗切，提醒限频不需要精确时区

    companion object {
        const val TODO_STALE_MS = 24L * 3600 * 1000
        const val IDEA_STALE_MS = 48L * 3600 * 1000
    }
}

