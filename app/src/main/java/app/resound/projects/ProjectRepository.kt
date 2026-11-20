package app.resound.projects

import app.resound.data.CaptureDatabase
import app.resound.data.MemoryItemEntity
import app.resound.inbox.IdeaRules
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 项目时间线里的一条：关联加入或记忆写入的时刻。 */
data class ProjectTimelineEntry(
    val at: Long,
    /** link / mark */
    val origin: String,
    val kind: String,
    val title: String,
    val detail: String,
    val refKind: String? = null,
    val refId: Long? = null,
)

/** 项目长期记忆：项目本身 + 关联记录 + 决策/否决/状态/下一步。不同项目之间靠 projectId 强隔离。 */
class ProjectRepository(
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val io: CoroutineDispatcher,
) {
    private val dao get() = db.projects()

    fun active(): Flow<List<ProjectRow>> = dao.observeActive()
    fun archived(): Flow<List<ProjectRow>> = dao.observeArchived()
    fun observe(id: Long): Flow<ProjectRow?> = dao.observeProject(id)
    fun links(projectId: Long): Flow<List<ProjectLinkRow>> = dao.observeLinks(projectId)
    fun marks(projectId: Long): Flow<List<ProjectMarkRow>> = dao.observeMarks(projectId)

    suspend fun create(name: String, note: String): Long = withContext(io) {
        dao.insert(ProjectRow(name = name.trim(), note = note.trim(), createdAt = now.now(), updatedAt = now.now()))
    }

    suspend fun rename(id: Long, name: String, note: String) = withContext(io) {
        dao.rename(id, name.trim(), note.trim(), now.now())
    }

    suspend fun archive(id: Long) = withContext(io) { dao.archive(id, now.now()) }
    suspend fun unarchive(id: Long) = withContext(io) { dao.unarchive(id, now.now()) }
    suspend fun delete(id: Long) = withContext(io) { dao.delete(id, now.now()) }

    /** 关联一条记录进项目；同事务把该记录产出的旧决定/灵感归属回填（只填 NULL，不覆盖已有归属）。 */
    suspend fun link(projectId: Long, kind: ProjectRefKind, refId: Long, note: String? = null): Long = withContext(io) {
        dao.transaction {
            dao.touch(projectId, now.now())
            val id = dao.insertLink(ProjectLinkRow(projectId = projectId, refKind = kind.name, refId = refId, note = note, createdAt = now.now()))
            when (kind) {
                ProjectRefKind.Event -> db.decisions().adoptForEvent(projectId, refId, now.now())
                ProjectRefKind.Item -> db.decisions().adoptForItem(projectId, refId, now.now())
                ProjectRefKind.Idea -> db.ideas().adoptForProject(projectId, refId, now.now())
                else -> {}
            }
            id
        }
    }

    suspend fun unlink(linkId: Long) = withContext(io) { dao.removeLink(linkId) }
    suspend fun unlink(projectId: Long, kind: ProjectRefKind, refId: Long) =
        withContext(io) { dao.removeLinkByRef(projectId, kind.name, refId) }

    /** 某条记录已挂在哪些项目下（溯源、防混淆展示） */
    suspend fun projectsOf(kind: ProjectRefKind, refId: Long): List<ProjectRow> = withContext(io) {
        dao.linksForRef(kind.name, refId).mapNotNull { dao.project(it.projectId) }.filter { !it.deleted }
    }

    suspend fun addMark(projectId: Long, kind: ProjectMarkKind, title: String, content: String = "", reason: String? = null,
        refKind: ProjectRefKind? = null, refId: Long? = null): Long = withContext(io) {
        dao.transaction {
            dao.touch(projectId, now.now())
            dao.insertMark(ProjectMarkRow(projectId = projectId, kind = kind.name, title = title.trim(),
                content = content.trim(), reason = reason?.trim()?.ifBlank { null },
                refKind = refKind?.name, refId = refId, at = now.now()))
        }
    }

    suspend fun updateMark(id: Long, title: String, content: String, reason: String?) =
        withContext(io) { dao.updateMark(id, title.trim(), content.trim(), reason?.trim()?.ifBlank { null }) }

    suspend fun deleteMark(id: Long) = withContext(io) { dao.deleteMark(id) }

    /** 项目当前状态摘要：最近一条 Status 记忆 + 最新 NextAction + 各维度计数。 */
    suspend fun summary(projectId: Long): ProjectSummary = withContext(io) {
        val marks = dao.marks(projectId)
        ProjectSummary(
            status = marks.firstOrNull { it.kind == ProjectMarkKind.Status.name }?.title ?: "",
            nextActions = marks.filter { it.kind == ProjectMarkKind.NextAction.name }.take(5),
            decisions = marks.count { it.kind == ProjectMarkKind.Decision.name },
            rejected = marks.count { it.kind == ProjectMarkKind.Rejected.name },
            links = dao.linkCount(projectId),
        )
    }

    /** 项目历史时间线：记忆条目与关联加入按时间倒序合成一条流。 */
    suspend fun timeline(projectId: Long): List<ProjectTimelineEntry> = withContext(io) {
        val marks = dao.marks(projectId).map {
            ProjectTimelineEntry(it.at, "mark", it.kind,
                it.title, listOfNotNull(it.content.ifBlank { null }, it.reason?.let { r -> "原因：$r" }).joinToString(" · "), it.refKind, it.refId)
        }
        val links = dao.links(projectId).map {
            ProjectTimelineEntry(it.createdAt, "link", it.refKind,
                "关联了${ProjectRefKind.of(it.refKind)?.label ?: it.refKind} #${it.refId}", it.note ?: "", it.refKind, it.refId)
        }
        (marks + links).sortedByDescending { it.at }
    }

    // ---------- R5.4：项目智能分析聚合 ----------

    /** 取齐项目全部事实，交给纯规则合并。隔离就在这里：一切按 projectId/链接集合取数。 */
    private suspend fun gather(projectId: Long): ProjectActivity.Input {
        val marks = dao.marks(projectId).filter { !it.deleted }
        val links = dao.links(projectId)
        val refs = links.mapNotNull { resolveRef(it) }
        val eventIds = links.filter { it.refKind == ProjectRefKind.Event.name }.map { it.refId }
        val directItemIds = links.filter { it.refKind == ProjectRefKind.Item.name }.map { it.refId }
        // 挂了会议 → 该会议提取出的待办/约定自动归入项目
        val viaEvent = if (eventIds.isEmpty()) emptyList() else db.memory().itemsForEvents(eventIds)
        val direct = directItemIds.mapNotNull { db.memory().itemIncludingDeleted(it) }
            .filter { !it.deleted && it.mergedInto == null }
        val items = (direct + viaEvent).distinctBy { it.id }
        val decisions = db.decisions().forProject(projectId)
        val linkedIdeas = links.filter { it.refKind == ProjectRefKind.Idea.name }
            .mapNotNull { db.ideas().idea(it.refId) }.filter { !it.deleted && it.mergedInto == null }
        val ideas = (db.ideas().forProject(projectId) + linkedIdeas).distinctBy { it.id }
        val ideaIds = ideas.map { it.id }.toSet()
        val ideaEntries = if (ideaIds.isEmpty()) emptyList() else db.ideas().entriesFor(ideaIds.toList())
        val followups = db.followups().let { it.observeOpen().first() + it.observeClosed().first() }
        return ProjectActivity.Input(
            marks = marks, refs = refs, decisions = decisions, ideas = ideas, ideaEntries = ideaEntries,
            items = items,
            followups = ProjectActivity.attributedFollowups(followups,
                items.map { it.id }.toSet(), ideaIds, decisions.map { it.id }.toSet()),
        )
    }

    /** 统一时间线：会议/决定/灵感/待办/跟进/记忆按事实发生时间合流去重。 */
    suspend fun activity(projectId: Long, limit: Int = 60, before: Long = Long.MAX_VALUE): List<ActivityEntry> =
        withContext(io) { ProjectActivity.timeline(gather(projectId), limit, before) }

    /** 行动与承诺：待确认 / 进行中+承诺 / 已完成 / 卡住。 */
    suspend fun actions(projectId: Long): ProjectActions = withContext(io) {
        ProjectActivity.actions(gather(projectId), now.now())
    }

    /** 项目范围的决定（含历史），供演变链分组。 */
    suspend fun decisionsFor(projectId: Long) = withContext(io) { db.decisions().forProject(projectId) }

    /** 把一条关联解析成真实记录：标题、摘要、实际发生时间。 */
    private suspend fun resolveRef(link: ProjectLinkRow): ResolvedRef? = runCatching {
        when (ProjectRefKind.of(link.refKind)) {
            ProjectRefKind.Event -> db.memory().event(link.refId)?.let {
                ResolvedRef(link, it.title, it.summary.take(300), it.startAt, it.scene)
            }
            ProjectRefKind.Item -> db.memory().itemIncludingDeleted(link.refId)?.takeIf { !it.deleted }?.let {
                ResolvedRef(link, it.content, it.category + (it.dueText?.let { d -> " · $d" } ?: ""),
                    it.recordedAt ?: it.createdAt, when (it.status) {
                        "Pending" -> "待确认"; "Accepted" -> "进行中"; "Done" -> "已完成"; "Ignored" -> "已忽略"; else -> it.status
                    })
            }
            ProjectRefKind.Idea -> db.ideas().idea(link.refId)?.takeIf { !it.deleted }?.let {
                ResolvedRef(link, it.title.ifBlank { it.content.take(30) }, it.content.take(300), it.createdAt,
                    app.resound.inbox.IdeaStatus.of(it.status).label)
            }
            ProjectRefKind.Line -> db.memory().lineById(link.refId)?.let {
                ResolvedRef(link, it.text.take(120), "", it.startAt)
            }
            ProjectRefKind.Segment -> db.transcribe().exportSegments(listOf(link.refId)).firstOrNull()?.let {
                ResolvedRef(link, "录音段（${it.lines.size} 句）", it.lines.take(3).joinToString(" ") { l -> l.text },
                    it.segment.startAt)
            }
            ProjectRefKind.Analysis -> db.analysis().run(link.refId)?.let {
                ResolvedRef(link, "${app.resound.analysis.AnalysisType.of(it.type).label} · ${it.templateName}",
                    app.resound.analysis.AnalysisContract.displayText(it.resultText).take(300), it.createdAt, it.status)
            }
            ProjectRefKind.Task -> db.agent().task(link.refId)?.let {
                ResolvedRef(link, it.title, "", it.createdAt, it.status)
            }
            null -> null
        }
    }.getOrNull()

    /** 全库项目知识搜索：项目名/备注 + 记忆标题/内容/否决原因。 */
    fun search(query: String): Flow<List<ProjectMarkRow>> = dao.searchAllMarks(IdeaRules.likePattern(query.trim()))
    suspend fun searchProjects(query: String): List<ProjectRow> =
        withContext(io) { dao.search(IdeaRules.likePattern(query.trim())) }
}

data class ProjectSummary(
    val status: String,
    val nextActions: List<ProjectMarkRow>,
    val decisions: Int,
    val rejected: Int,
    val links: Int,
)
