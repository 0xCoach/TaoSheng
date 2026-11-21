package app.resound.projects

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** 项目长期记忆：一个可持续积累的工作/兴趣项目。 */
@Entity(tableName = "projects", indices = [Index("status"), Index("updatedAt")])
data class ProjectRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    /** Active / Archived */
    val status: String = ProjectStatus.Active.name,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** 项目与各类记录的关联；refKind 见 ProjectRefKind。 */
@Entity(tableName = "project_links",
    indices = [Index("projectId"), Index(value = ["projectId", "refKind", "refId"], unique = true), Index("createdAt")])
data class ProjectLinkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** Segment / Event / Item / Idea / Task / Analysis / Line */
    val refKind: String,
    val refId: Long,
    val note: String? = null,
    val createdAt: Long,
)

/** 项目记忆条目：重要决策、被否决的方案及原因、状态快照、下一步行动。 */
@Entity(tableName = "project_marks", indices = [Index("projectId"), Index("at")])
data class ProjectMarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** Decision / Rejected / Status / NextAction / Note */
    val kind: String,
    val title: String,
    val content: String = "",
    /** Rejected 专用：方案被否决的原因 */
    val reason: String? = null,
    /** 可选：这条记忆关联的原话或记录编号 */
    val refKind: String? = null,
    val refId: Long? = null,
    val at: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

enum class ProjectStatus(val label: String) { Active("进行中"), Archived("已归档") }

enum class ProjectRefKind(val label: String) {
    Segment("录音"), Event("事件"), Item("待办"), Idea("灵感"), Task("Agent 任务"), Analysis("AI 分析"), Line("原话");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } }
}

enum class ProjectMarkKind(val label: String) {
    Decision("重要决策"), Rejected("否决方案"), Status("状态记录"), NextAction("下一步行动"), Note("备忘");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Note }
}

@Dao
abstract class ProjectDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: ProjectRow): Long
    @Update abstract suspend fun update(row: ProjectRow)
    @Query("SELECT * FROM projects WHERE id = :id") abstract suspend fun project(id: Long): ProjectRow?
    @Query("SELECT * FROM projects WHERE id = :id AND deleted = 0") abstract fun observeProject(id: Long): Flow<ProjectRow?>
    @Query("SELECT * FROM projects WHERE deleted = 0 AND status != 'Archived' ORDER BY updatedAt DESC, id DESC")
    abstract fun observeActive(): Flow<List<ProjectRow>>
    @Query("SELECT * FROM projects WHERE deleted = 0 AND status = 'Archived' ORDER BY archivedAt DESC, id DESC")
    abstract fun observeArchived(): Flow<List<ProjectRow>>
    @Query("SELECT * FROM projects WHERE deleted = 0 ORDER BY updatedAt DESC, id DESC")
    abstract suspend fun all(): List<ProjectRow>
    @Query("SELECT * FROM projects WHERE deleted = 0 AND (name LIKE :pattern ESCAPE '\' OR note LIKE :pattern ESCAPE '\') ORDER BY updatedAt DESC")
    abstract suspend fun search(pattern: String): List<ProjectRow>
    @Query("UPDATE projects SET status = 'Archived', archivedAt = :at, updatedAt = :at WHERE id = :id")
    abstract suspend fun archive(id: Long, at: Long)
    @Query("UPDATE projects SET status = 'Active', archivedAt = NULL, updatedAt = :at WHERE id = :id")
    abstract suspend fun unarchive(id: Long, at: Long)
    @Query("UPDATE projects SET name = :name, note = :note, updatedAt = :at WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, note: String, at: Long)
    @Query("UPDATE projects SET deleted = 1, updatedAt = :at WHERE id = :id")
    abstract suspend fun delete(id: Long, at: Long)
    @Query("UPDATE projects SET updatedAt = :at WHERE id = :id")
    abstract suspend fun touch(id: Long, at: Long)

    // ---------- 关联 ----------
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertLink(row: ProjectLinkRow): Long
    @Query("DELETE FROM project_links WHERE id = :id") abstract suspend fun removeLink(id: Long)
    @Query("DELETE FROM project_links WHERE projectId = :projectId AND refKind = :kind AND refId = :refId")
    abstract suspend fun removeLinkByRef(projectId: Long, kind: String, refId: Long)
    @Query("SELECT * FROM project_links WHERE projectId = :projectId ORDER BY createdAt DESC, id DESC")
    abstract fun observeLinks(projectId: Long): Flow<List<ProjectLinkRow>>
    @Query("SELECT * FROM project_links WHERE projectId = :projectId ORDER BY createdAt DESC, id DESC")
    abstract suspend fun links(projectId: Long): List<ProjectLinkRow>
    @Query("SELECT * FROM project_links WHERE refKind = :kind AND refId = :refId ORDER BY createdAt DESC")
    abstract suspend fun linksForRef(kind: String, refId: Long): List<ProjectLinkRow>
    @Query("SELECT COUNT(*) FROM project_links WHERE projectId = :projectId")
    abstract suspend fun linkCount(projectId: Long): Int

    // ---------- 记忆 ----------
    @Insert abstract suspend fun insertMark(row: ProjectMarkRow): Long
    @Query("SELECT * FROM project_marks WHERE id = :id") abstract suspend fun mark(id: Long): ProjectMarkRow?
    @Query("UPDATE project_marks SET deleted = 1 WHERE id = :id") abstract suspend fun deleteMark(id: Long)
    @Query("UPDATE project_marks SET title = :title, content = :content, reason = :reason WHERE id = :id")
    abstract suspend fun updateMark(id: Long, title: String, content: String, reason: String?)
    @Query("SELECT * FROM project_marks WHERE projectId = :projectId AND deleted = 0 ORDER BY at DESC, id DESC")
    abstract fun observeMarks(projectId: Long): Flow<List<ProjectMarkRow>>
    @Query("SELECT * FROM project_marks WHERE projectId = :projectId AND deleted = 0 ORDER BY at DESC, id DESC")
    abstract suspend fun marks(projectId: Long): List<ProjectMarkRow>
    @Query("SELECT * FROM project_marks WHERE projectId = :projectId AND deleted = 0 AND kind = :kind ORDER BY at DESC, id DESC")
    abstract suspend fun marksOfKind(projectId: Long, kind: String): List<ProjectMarkRow>
    @Query("""SELECT * FROM project_marks WHERE projectId = :projectId AND deleted = 0
        AND (title LIKE :pattern ESCAPE '\' OR content LIKE :pattern ESCAPE '\' OR reason LIKE :pattern ESCAPE '\')
        ORDER BY at DESC, id DESC""")
    abstract suspend fun searchMarks(projectId: Long, pattern: String): List<ProjectMarkRow>
    @Query("""SELECT * FROM project_marks m WHERE m.deleted = 0
        AND (m.title LIKE :pattern ESCAPE '\' OR m.content LIKE :pattern ESCAPE '\' OR m.reason LIKE :pattern ESCAPE '\')
        ORDER BY m.at DESC, m.id DESC LIMIT 100""")
    abstract fun searchAllMarks(pattern: String): Flow<List<ProjectMarkRow>>
}

object ProjectSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, note TEXT NOT NULL, status TEXT NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, archivedAt INTEGER, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_status ON projects(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_updatedAt ON projects(updatedAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_links (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            projectId INTEGER NOT NULL, refKind TEXT NOT NULL, refId INTEGER NOT NULL, note TEXT, createdAt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_links_projectId ON project_links(projectId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_project_links_projectId_refKind_refId ON project_links(projectId, refKind, refId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_links_createdAt ON project_links(createdAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_marks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            projectId INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL,
            reason TEXT, refKind TEXT, refId INTEGER, at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_marks_projectId ON project_marks(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_marks_at ON project_marks(at)")
    }
}
