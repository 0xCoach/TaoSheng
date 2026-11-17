package app.resound.projects

import app.resound.data.CaptureDatabase
import app.resound.inbox.IdeaRules
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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

    suspend fun link(projectId: Long, kind: ProjectRefKind, refId: Long, note: String? = null): Long = withContext(io) {
        dao.transaction {
            dao.touch(projectId, now.now())
            dao.insertLink(ProjectLinkRow(projectId = projectId, refKind = kind.name, refId = refId, note = note, createdAt = now.now()))
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
