package app.resound.rules

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 自动化规则：触发条件 → 动作。
 * 触发：Manual（手动）/ Time（每天某时点）/ Event（事件钩子）。
 * 动作：Notify / Todo / Analysis / AgentTask——凡是对外发送的（AgentTask）一律只创建 Pending 任务，等用户确认。
 */
@Entity(tableName = "workflow_rules", indices = [Index("enabled"), Index("triggerKind")])
data class WorkflowRuleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** 见 TriggerKind */
    val triggerKind: String,
    /** 触发配置 JSON：Time={hour,minute,lastDay}；Event={event}；Manual={} */
    val triggerJson: String = "{}",
    /** 见 ActionKind */
    val actionKind: String,
    /** 动作配置 JSON：Notify={title,body}；Todo={title}；Analysis={targetKind,type}；AgentTask={endpointId,kind,titleTemplate,payloadTemplate} */
    val actionJson: String = "{}",
    val lastFiredAt: Long = 0,
    /** Time 触发用的当日去重标记 */
    val lastFiredDay: String = "",
    val fireCount: Int = 0,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "rule_runs", indices = [Index("ruleId"), Index("at")])
data class RuleRunRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val at: Long,
    val trigger: String,
    /** Ok / NeedsConfirm / Failed / Skipped */
    val status: String,
    val detail: String = "",
    /** 产出物编号（待办/分析/任务） */
    val refKind: String? = null,
    val refId: Long? = null,
)

@Dao
abstract class WorkflowRuleDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: WorkflowRuleRow): Long
    @Update abstract suspend fun update(row: WorkflowRuleRow)

    @Query("SELECT * FROM workflow_rules WHERE id = :id") abstract suspend fun rule(id: Long): WorkflowRuleRow?
    @Query("SELECT * FROM workflow_rules WHERE deleted = 0 ORDER BY createdAt ASC, id ASC")
    abstract fun observeAll(): Flow<List<WorkflowRuleRow>>

    @Query("SELECT * FROM workflow_rules WHERE deleted = 0 AND enabled = 1 AND triggerKind = 'Time'")
    abstract suspend fun enabledTimeRules(): List<WorkflowRuleRow>

    @Query("SELECT * FROM workflow_rules WHERE deleted = 0 AND enabled = 1 AND triggerKind = 'Event'")
    abstract suspend fun enabledEventRules(): List<WorkflowRuleRow>

    @Query("UPDATE workflow_rules SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE workflow_rules SET lastFiredAt = :at, lastFiredDay = :day, fireCount = fireCount + 1 WHERE id = :id")
    abstract suspend fun markFired(id: Long, at: Long, day: String)

    @Query("UPDATE workflow_rules SET deleted = 1 WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert abstract suspend fun insertRun(row: RuleRunRow): Long
    @Query("SELECT * FROM rule_runs WHERE ruleId = :ruleId ORDER BY at DESC, id DESC LIMIT 100")
    abstract fun observeRuns(ruleId: Long): Flow<List<RuleRunRow>>
    @Query("SELECT * FROM rule_runs ORDER BY at DESC, id DESC LIMIT 200")
    abstract fun observeRecentRuns(): Flow<List<RuleRunRow>>
}

object RuleSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS workflow_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, triggerKind TEXT NOT NULL, triggerJson TEXT NOT NULL,
            actionKind TEXT NOT NULL, actionJson TEXT NOT NULL, lastFiredAt INTEGER NOT NULL DEFAULT 0,
            lastFiredDay TEXT NOT NULL DEFAULT '', fireCount INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_rules_enabled ON workflow_rules(enabled)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_rules_triggerKind ON workflow_rules(triggerKind)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS rule_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            ruleId INTEGER NOT NULL, at INTEGER NOT NULL, trigger TEXT NOT NULL, status TEXT NOT NULL,
            detail TEXT NOT NULL, refKind TEXT, refId INTEGER)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rule_runs_ruleId ON rule_runs(ruleId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rule_runs_at ON rule_runs(at)")
    }
}

enum class TriggerKind(val label: String) {
    Manual("手动执行"), Time("每天定时"), Event("事件触发");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Manual
    }
}

/** 事件钩子枚举：新增事件类型在这里登记，引擎按它派发。 */
enum class RuleEvent(val label: String, val key: String) {
    IdeaCreated("新灵感进收件箱", "idea_created"),
    CaptureArrived("收集箱新条目", "capture_arrived"),
    DigestDone("每日整理完成", "digest_done"),
    FollowupDue("跟进项到期", "followup_due"),
    TopicDone("专题纪要完成", "topic_done");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class ActionKind(val label: String) {
    Notify("发通知"), Todo("建待办"), Analysis("跑分析"), AgentTask("派任务");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Notify
        /** 是否对外副作用：AgentTask 必须走确认 */
        fun needsConfirm(kind: ActionKind) = kind == AgentTask
    }
}

object WorkflowRules {
    /**
     * Time 触发判定：到了当天 HH:mm 且今天还没触发过。
     * hour/minute 来自 triggerJson；dayKey 由调用方按本地时区给出（yyyy-MM-dd）。
     */
    fun timeDue(triggerJson: String, lastFiredDay: String, dayKey: String, minutesOfDay: Int): Boolean {
        if (lastFiredDay == dayKey) return false
        val hour = triggerJson.substringAfter("\"hour\":").substringBefore(",").substringBefore("}").trim().toIntOrNull() ?: 9
        val minute = triggerJson.substringAfter("\"minute\":").substringBefore(",").substringBefore("}").trim().toIntOrNull() ?: 0
        return minutesOfDay >= hour * 60 + minute
    }

    /** Event 触发判定：规则声明的事件与本次事件一致。 */
    fun eventMatches(triggerJson: String, eventKey: String): Boolean =
        triggerJson.contains("\"event\":\"$eventKey\"") || triggerJson.contains("\"event\": \"$eventKey\"")
}
