package app.resound.agent

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.resound.platform.SharedJson

/**
 * Agent 桥接：把整理好的任务发给外部 AI 执行体（自建 Webhook / n8n / Dify / 自研服务）。
 * 状态机：Pending(待确认) → Confirmed(已确认待发) → Running(已受理执行中) → Succeeded / Failed / Cancelled。
 */
@Entity(tableName = "agent_endpoints")
data class AgentEndpointRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** HTTPS 接口；局域网 / Tailscale 允许 HTTP，由界面提示确认 */
    val url: String,
    /** 自定义附加请求头 JSON（{名:值}），密钥不在这里，进加密存储 */
    val headersJson: String = "{}",
    /** 密钥在加密存储里的键名；空表示该端点不带密钥 */
    val secretName: String = "",
    /** 状态查询地址；空表示不支持回查，发送受理后只能等回执或按完成处理 */
    val statusUrlTemplate: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "agent_tasks",
    indices = [Index(value = ["taskId"], unique = true), Index("status"), Index("createdAt"), Index("nextRetryAt"), Index("endpointId")])
data class AgentTaskRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对外唯一任务号：同一任务重复发送凭它去重，服务端幂等 */
    val taskId: String,
    val endpointId: Long? = null,
    /** 见 AgentTaskKind */
    val type: String,
    val title: String,
    /** 发送内容快照；确认后不再变化，重试不重编 */
    val payloadJson: String,
    val status: String = AgentTaskStatus.Pending.name,
    val attempts: Int = 0,
    val maxAttempts: Int = 5,
    val nextRetryAt: Long? = null,
    val confirmedAt: Long? = null,
    val dispatchedAt: Long? = null,
    val finishedAt: Long? = null,
    /** 服务端受理后回传的任务号 */
    val remoteId: String? = null,
    /** 最近一次 HTTP 回执摘要（状态码、耗时、回执 JSON 摘录） */
    val receiptJson: String = "",
    /** 完成时服务端回传的结果摘要 */
    val resultText: String = "",
    val error: String? = null,
    /** 来源：Idea / Project / Segment / Event / Manual */
    val sourceKind: String = "Manual",
    val sourceId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class AgentTaskStatus(val label: String) {
    Pending("待确认"), Confirmed("待发送"), Running("执行中"),
    Succeeded("已完成"), Failed("已失败"), Cancelled("已取消");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Pending
        /** 状态迁移表：业务层按此校验，不允许跳变。 */
        fun canMove(from: AgentTaskStatus, to: AgentTaskStatus): Boolean = when (from) {
            Pending -> to in setOf(Confirmed, Cancelled)
            Confirmed -> to in setOf(Running, Failed, Cancelled, Pending)
            Running -> to in setOf(Succeeded, Failed, Cancelled)
            Failed -> to in setOf(Confirmed, Pending, Cancelled)
            Succeeded -> false
            Cancelled -> to == Pending
        }
        /** 还能不能发送/重试 */
        fun sendable(status: AgentTaskStatus) = status in setOf(Confirmed, Failed)
        fun open(status: AgentTaskStatus) = status in setOf(Pending, Confirmed, Running)
    }
}

enum class AgentTaskKind(val label: String, val wire: String) {
    DeepResearch("深度研究", "deep_research"),
    MarketResearch("市场调研", "market_research"),
    DevRequirements("编写开发需求", "dev_requirements"),
    TodoPlan("制作待办计划", "todo_plan"),
    ContentDraft("生成内容草稿", "content_draft"),
    ProjectMemoryUpdate("更新项目记忆", "project_memory_update"),
    Custom("自定义任务", "custom");
    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Custom
        fun ofWire(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
data class AgentTaskPayload(
    val taskId: String,
    val type: String,
    val title: String,
    /** 正文材料：用户确认过的文本，绝不包含音频本体 */
    val content: String,
    val context: String = "",
    val sourceKind: String = "Manual",
    val sourceId: Long? = null,
    val createdAt: Long,
    val callbackHint: String = "",
)

@Serializable
data class AgentReceipt(
    val httpStatus: Int,
    val elapsedMs: Long,
    val remoteId: String? = null,
    val remoteStatus: String? = null,
    val note: String = "",
    val at: Long,
)

@Dao
abstract class AgentDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    // ---------- 端点 ----------
    @Insert abstract suspend fun insertEndpoint(row: AgentEndpointRow): Long
    @Update abstract suspend fun updateEndpoint(row: AgentEndpointRow)
    @Query("SELECT * FROM agent_endpoints ORDER BY id") abstract fun observeEndpoints(): Flow<List<AgentEndpointRow>>
    @Query("SELECT * FROM agent_endpoints ORDER BY id") abstract suspend fun endpoints(): List<AgentEndpointRow>
    @Query("SELECT * FROM agent_endpoints WHERE id = :id") abstract suspend fun endpoint(id: Long): AgentEndpointRow?
    @Query("SELECT * FROM agent_endpoints WHERE enabled = 1 ORDER BY id") abstract suspend fun enabledEndpoints(): List<AgentEndpointRow>
    @Query("DELETE FROM agent_endpoints WHERE id = :id") abstract suspend fun deleteEndpoint(id: Long)

    // ---------- 任务 ----------
    @Insert abstract suspend fun insertTask(row: AgentTaskRow): Long
    @Update abstract suspend fun updateTask(row: AgentTaskRow)
    @Query("SELECT * FROM agent_tasks WHERE id = :id") abstract suspend fun task(id: Long): AgentTaskRow?
    @Query("SELECT * FROM agent_tasks WHERE id = :id") abstract fun observeTask(id: Long): Flow<AgentTaskRow?>
    @Query("SELECT * FROM agent_tasks WHERE taskId = :taskId") abstract suspend fun taskByTaskId(taskId: String): AgentTaskRow?
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC, id DESC LIMIT 300")
    abstract fun observeTasks(): Flow<List<AgentTaskRow>>
    @Query("SELECT * FROM agent_tasks WHERE status IN ('Pending','Confirmed','Running') ORDER BY createdAt DESC, id DESC")
    abstract fun observeOpen(): Flow<List<AgentTaskRow>>
    @Query("SELECT * FROM agent_tasks WHERE sourceKind = :kind AND sourceId = :sourceId ORDER BY createdAt DESC")
    abstract suspend fun tasksForSource(kind: String, sourceId: Long): List<AgentTaskRow>
    @Query("SELECT * FROM agent_tasks WHERE status = 'Confirmed' OR (status = 'Failed' AND nextRetryAt IS NOT NULL AND nextRetryAt <= :now AND attempts < maxAttempts) ORDER BY createdAt, id")
    abstract suspend fun dispatchable(now: Long): List<AgentTaskRow>
    @Query("SELECT * FROM agent_tasks WHERE status = 'Running' ORDER BY dispatchedAt")
    abstract suspend fun running(): List<AgentTaskRow>
    /** CAS 认领：只有仍处于 Confirmed/待重试的任务才能被发出，保证同一任务不会被并发发两次。 */
    @Query("UPDATE agent_tasks SET status = 'Running', attempts = attempts + 1, dispatchedAt = :at, updatedAt = :at, nextRetryAt = NULL WHERE id = :id AND (status = 'Confirmed' OR (status = 'Failed' AND nextRetryAt IS NOT NULL AND nextRetryAt <= :at))")
    abstract suspend fun claimForSend(id: Long, at: Long): Int
    @Query("UPDATE agent_tasks SET status = :status, updatedAt = :at, finishedAt = CASE WHEN :status IN ('Succeeded','Failed','Cancelled') THEN :at ELSE finishedAt END, resultText = CASE WHEN :resultText != '' THEN :resultText ELSE resultText END, error = :error WHERE id = :id AND status = 'Running'")
    abstract suspend fun finishRunning(id: Long, status: String, resultText: String, error: String?, at: Long): Int
    @Query("UPDATE agent_tasks SET receiptJson = :receipt, remoteId = COALESCE(:remoteId, remoteId), updatedAt = :at WHERE id = :id")
    abstract suspend fun saveReceipt(id: Long, receipt: String, remoteId: String?, at: Long)
    @Query("UPDATE agent_tasks SET status = :status, updatedAt = :at, confirmedAt = CASE WHEN :status = 'Confirmed' THEN :at ELSE confirmedAt END WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String, at: Long): Int
    @Query("UPDATE agent_tasks SET status = 'Failed', error = :error, nextRetryAt = :retryAt, updatedAt = :at WHERE id = :id")
    abstract suspend fun scheduleRetry(id: Long, error: String, retryAt: Long?, at: Long)
    @Query("UPDATE agent_tasks SET endpointId = :endpointId, updatedAt = :at WHERE id = :id")
    abstract suspend fun setEndpoint(id: Long, endpointId: Long?, at: Long)
    @Query("DELETE FROM agent_tasks WHERE id = :id") abstract suspend fun deleteTask(id: Long)
}

object AgentSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS agent_endpoints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, url TEXT NOT NULL, headersJson TEXT NOT NULL, secretName TEXT NOT NULL,
            statusUrlTemplate TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS agent_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            taskId TEXT NOT NULL, endpointId INTEGER, type TEXT NOT NULL, title TEXT NOT NULL, payloadJson TEXT NOT NULL,
            status TEXT NOT NULL, attempts INTEGER NOT NULL, maxAttempts INTEGER NOT NULL, nextRetryAt INTEGER,
            confirmedAt INTEGER, dispatchedAt INTEGER, finishedAt INTEGER, remoteId TEXT, receiptJson TEXT NOT NULL,
            resultText TEXT NOT NULL, error TEXT, sourceKind TEXT NOT NULL, sourceId INTEGER,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_tasks_taskId ON agent_tasks(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_status ON agent_tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_createdAt ON agent_tasks(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_nextRetryAt ON agent_tasks(nextRetryAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_endpointId ON agent_tasks(endpointId)")
    }
}

object AgentRules {
    /** 随机任务号：时间戳 + 16 位十六进制随机段，本地生成不查库也有唯一索引兜底。 */
    fun newTaskId(now: Long, random: kotlin.random.Random = kotlin.random.Random.Default): String {
        val suffix = StringBuilder(16)
        val hex = "0123456789abcdef"
        repeat(16) { suffix.append(hex[random.nextInt(16)]) }
        return "task-${now.toString(36)}-$suffix"
    }

    /** 失败重试间隔：第 n 次失败后等 1m/5m/15m/1h/4h；超过 maxAttempts 不再排期。 */
    fun nextRetryDelay(attempts: Int): Long? = when (attempts) {
        1 -> 60_000L
        2 -> 300_000L
        3 -> 900_000L
        4 -> 3_600_000L
        5 -> 14_400_000L
        else -> null
    }

    /** 构造对外 JSON 载荷。只放确认过的文本；音频、整库原话永不出域。 */
    fun payloadJson(payload: AgentTaskPayload, json: Json = SharedJson): String = json.encodeToString(AgentTaskPayload.serializer(), payload)

    fun parsePayload(text: String, json: Json = SharedJson): AgentTaskPayload? =
        runCatching { json.decodeFromString(AgentTaskPayload.serializer(), text) }.getOrNull()

    fun receiptJson(receipt: AgentReceipt, json: Json = SharedJson): String = json.encodeToString(AgentReceipt.serializer(), receipt)

    fun parseReceipt(text: String, json: Json = SharedJson): AgentReceipt? =
        runCatching { json.decodeFromString(AgentReceipt.serializer(), text) }.getOrNull()

    /** 端点地址校验：必须 http(s)，公网要求 https；私网地址允许 http。 */
    fun endpointUrlValid(url: String): Pair<Boolean, String> {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false to "地址要以 http:// 或 https:// 开头"
        if (u.startsWith("https://")) return true to ""
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        val private = host == "localhost" || host.endsWith(".local") || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) ||
            host.endsWith(".ts.net") || host.endsWith(".internal")
        return if (private) true to "" else false to "公网地址必须用 https；http 只允许局域网 / Tailscale"
    }

    /** 从服务端响应中解析远程任务号与状态：宽容匹配常见字段名。 */
    fun remoteAck(body: String): Pair<String?, String?> {
        val obj = runCatching { Json.parseToJsonElement(body) }
            .getOrNull() as? JsonObject ?: return null to null
        fun pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        val id = pick("taskId", "task_id", "id", "runId", "run_id")
        val status = pick("status", "state")
        return id to status
    }

    /** 服务端状态文本映射回本地状态；识别不了的返回 null 保持 Running。 */
    fun mapRemoteStatus(text: String?): AgentTaskStatus? = when (text?.lowercase()?.trim()) {
        "succeeded", "success", "done", "completed", "complete", "finished", "ok" -> AgentTaskStatus.Succeeded
        "failed", "error", "cancelled", "canceled", "rejected", "timeout" -> AgentTaskStatus.Failed
        else -> null
    }
}
