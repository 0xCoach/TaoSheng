package app.resound.mcp

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * MCP 配对客户端：只读查询权限，按 projectScope 限定可看哪个项目的关联记录。
 * token 本体存 SecretStore（不落库）；scopeJson 记允许的工具与项目范围。
 */
@Entity(tableName = "mcp_clients", indices = [Index("enabled")])
data class McpClientRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** token 指纹（前 8 位 SHA-256），用于在列表里辨认哪把钥匙 */
    val tokenHint: String = "",
    /** 限定项目；null 表示可查全局记录（原话/事件等不属于项目的记录） */
    val projectScope: Long? = null,
    /** 允许的工具名列表 JSON；空数组=全部只读工具 */
    val toolsJson: String = "[]",
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastSeenAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Dao
abstract class McpClientDao {
    @Insert abstract suspend fun insert(row: McpClientRow): Long
    @Update abstract suspend fun update(row: McpClientRow)

    @Query("SELECT * FROM mcp_clients WHERE id = :id") abstract suspend fun client(id: Long): McpClientRow?
    @Query("SELECT * FROM mcp_clients WHERE deleted = 0 ORDER BY createdAt ASC")
    abstract fun observeAll(): Flow<List<McpClientRow>>
    @Query("SELECT * FROM mcp_clients WHERE deleted = 0 AND enabled = 1")
    abstract suspend fun enabled(): List<McpClientRow>

    @Query("UPDATE mcp_clients SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE mcp_clients SET lastSeenAt = :at WHERE id = :id")
    abstract suspend fun touch(id: Long, at: Long)

    @Query("UPDATE mcp_clients SET deleted = 1 WHERE id = :id")
    abstract suspend fun delete(id: Long)
}

object McpSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mcp_clients (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, tokenHint TEXT NOT NULL, projectScope INTEGER, toolsJson TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1, createdAt INTEGER NOT NULL,
            lastSeenAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mcp_clients_enabled ON mcp_clients(enabled)")
    }
}

/** MCP 只读工具清单：name → 说明。写工具/执行工具不在这批（另设审批，当前版本不开放）。 */
object McpTools {
    data class Tool(val name: String, val description: String)

    val readOnly = listOf(
        Tool("memory_search", "按关键词搜索原话与事件，返回带时间的原文片段"),
        Tool("transcript_search", "只搜转写原话"),
        Tool("project_list", "列出项目与状态"),
        Tool("project_marks", "某项目的决策/否决/状态/下一步记录"),
        Tool("idea_list", "列出灵感（可按状态过滤）"),
        Tool("decision_list", "列出有效决策及历史（被替代/否决的不混进当前）"),
        Tool("followup_list", "列出待跟进承诺与长期未推进事项"),
        Tool("capture_list", "收集箱条目（链接/文字/图片）"),
        Tool("record_get", "按类型+编号取单条记录原文"),
    )

    fun allowed(requested: List<String>): List<String> =
        readOnly.map { it.name }.filter { requested.isEmpty() || it in requested }

    fun exists(name: String) = readOnly.any { it.name == name }
}
