package app.resound.revise

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 记忆修订日志：每一次人工纠错（改/删/合并/拆分/恢复）都留痕，
 * beforeJson/afterJson 存完整快照，支持撤销。AI 不允许无痕覆盖——改动必须走这里。
 */
@Entity(tableName = "memory_revisions", indices = [Index("targetKind", "targetId"), Index("at")])
data class MemoryRevisionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** item（提取项）/ event（事件）/ decision / idea / capture */
    val targetKind: String,
    val targetId: Long,
    /** 见 RevisionOp */
    val op: String,
    /** 改前快照 JSON（可恢复的最小字段集） */
    val beforeJson: String,
    /** 改后快照 JSON */
    val afterJson: String,
    /** 这次修订撤销时反向操作需要的补充数据（如合并对端 id） */
    val extraJson: String = "{}",
    val reason: String? = null,
    /** 撤销标记：被哪条 Revision 撤销 */
    val undoneBy: Long? = null,
    val at: Long,
)

@Dao
abstract class MemoryRevisionDao {
    @Insert abstract suspend fun insert(row: MemoryRevisionRow): Long

    @Query("SELECT * FROM memory_revisions WHERE id = :id") abstract suspend fun revision(id: Long): MemoryRevisionRow?

    @Query("SELECT * FROM memory_revisions WHERE targetKind = :kind AND targetId = :targetId ORDER BY at DESC, id DESC")
    abstract fun history(kind: String, targetId: Long): Flow<List<MemoryRevisionRow>>

    @Query("SELECT * FROM memory_revisions ORDER BY at DESC, id DESC LIMIT 300")
    abstract fun observeRecent(): Flow<List<MemoryRevisionRow>>

    @Query("SELECT * FROM memory_revisions WHERE targetKind = :kind AND targetId = :targetId ORDER BY at DESC, id DESC LIMIT 1")
    abstract suspend fun latest(kind: String, targetId: Long): MemoryRevisionRow?

    @Query("UPDATE memory_revisions SET undoneBy = :byId WHERE id = :id")
    abstract suspend fun markUndone(id: Long, byId: Long)
}

object RevisionSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            targetKind TEXT NOT NULL, targetId INTEGER NOT NULL, op TEXT NOT NULL,
            beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, extraJson TEXT NOT NULL,
            reason TEXT, undoneBy INTEGER, at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_revisions_targetKind_targetId ON memory_revisions(targetKind, targetId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_revisions_at ON memory_revisions(at)")
    }
}

enum class RevisionOp(val label: String) {
    Edit("编辑"), Merge("合并"), Split("拆分"), Delete("删除"), Restore("恢复"), Supersede("替代"), Undo("撤销");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Edit
    }
}
