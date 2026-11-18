package app.resound.rules

import app.resound.platform.NowMillis
import app.resound.agent.AgentBridge
import app.resound.agent.AgentTaskKind
import app.resound.analysis.AnalysisRepository
import app.resound.analysis.AnalysisTarget
import app.resound.analysis.AnalysisType
import app.resound.data.MemoryDao
import app.resound.data.MemoryItemEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 自动化规则引擎：手动/定时/事件三种触发 → 通知/待办/分析/派任务四种动作。
 * AgentTask 是对外副作用——只创建 Pending 任务记 NeedsConfirm，发不发由用户在任务页确认。
 */
class WorkflowEngine(
    private val dao: WorkflowRuleDao,
    private val memory: MemoryDao,
    private val io: CoroutineDispatcher,
    private val now: NowMillis,
    private val notify: suspend (title: String, body: String, deepLink: String?) -> Unit,
    private val analysis: () -> AnalysisRepository?,
    private val agent: () -> AgentBridge?,
) {
    fun observeRules(): Flow<List<WorkflowRuleRow>> = dao.observeAll()
    fun observeRuns(): Flow<List<RuleRunRow>> = dao.observeRecentRuns()
    fun observeRuns(ruleId: Long) = dao.observeRuns(ruleId)

    suspend fun create(name: String, trigger: TriggerKind, triggerJson: String, action: ActionKind, actionJson: String): Long = withContext(io) {
        dao.insert(WorkflowRuleRow(name = name, triggerKind = trigger.name, triggerJson = triggerJson,
            actionKind = action.name, actionJson = actionJson, createdAt = now.now()))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(io) { dao.setEnabled(id, enabled) }
    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    /** 事件入口：代码各处上报 idea_created / capture_arrived / digest_done / followup_due。 */
    suspend fun emit(eventKey: String, detail: String = "") = withContext(io) {
        for (rule in dao.enabledEventRules().filter { WorkflowRules.eventMatches(it.triggerJson, eventKey) }) {
            execute(rule, "event:$eventKey")
        }
    }

    /** 定时入口：驱动循环传入当天 dayKey 与分钟数，命中且当日未发的执行。 */
    suspend fun tickTime(dayKey: String, minutesOfDay: Int) = withContext(io) {
        for (rule in dao.enabledTimeRules().filter { WorkflowRules.timeDue(it.triggerJson, it.lastFiredDay, dayKey, minutesOfDay) }) {
            execute(rule, "time:$dayKey")
        }
    }

    /** 手动执行入口（界面"立即运行"）。 */
    suspend fun runNow(ruleId: Long): Boolean = withContext(io) {
        val rule = dao.rule(ruleId) ?: return@withContext false
        execute(rule, "manual"); true
    }

    private suspend fun execute(rule: WorkflowRuleRow, trigger: String) {
        val at = now.now()
        try {
            val json = rule.actionJson
            when (ActionKind.of(rule.actionKind)) {
                ActionKind.Notify -> {
                    notify(field(json, "title") ?: rule.name, field(json, "body") ?: "", "app.resound://assistant?open=rules")
                    log(rule.id, at, trigger, "Ok", "已发通知"); dao.markFired(rule.id, at, dayKey(at))
                }
                ActionKind.Todo -> {
                    val title = field(json, "title") ?: rule.name
                    val id = memory.addManualItem(MemoryItemEntity(eventId = null, category = "待办", kind = "rule",
                        content = title, dueText = null, dueAt = null, who = null, lineId = null,
                        status = "Accepted", createdAt = at, origin = "rule"))
                    log(rule.id, at, trigger, "Ok", "待办#$id", "item", id); dao.markFired(rule.id, at, dayKey(at))
                }
                ActionKind.Analysis -> {
                    val repo = analysis() ?: throw IllegalStateException("分析未装配")
                    val target = AnalysisTarget.of(field(json, "targetKind") ?: "Day") ?: AnalysisTarget.Day
                    val targetId = field(json, "targetId")?.toLongOrNull() ?: dayStart(at)
                    val runId = repo.start(target, targetId, AnalysisType.of(field(json, "type") ?: "Summary"))
                    log(rule.id, at, trigger, "Ok", "分析#$runId", "analysis", runId); dao.markFired(rule.id, at, dayKey(at))
                }
                ActionKind.AgentTask -> {
                    val bridge = agent() ?: throw IllegalStateException("Agent 未装配")
                    val kind = AgentTaskKind.entries.firstOrNull { it.name == field(json, "kind") } ?: AgentTaskKind.DeepResearch
                    val taskId = bridge.createTask(kind = kind, title = field(json, "title") ?: rule.name,
                        content = field(json, "content") ?: field(json, "title") ?: rule.name,
                        endpointId = field(json, "endpointId")?.toLongOrNull(), sourceKind = "Rule", sourceId = rule.id)
                    // 只建 Pending，待用户确认才出网
                    log(rule.id, at, trigger, "NeedsConfirm", "任务#$taskId 待确认", "task", taskId); dao.markFired(rule.id, at, dayKey(at))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log(rule.id, at, trigger, "Failed", e.message?.take(120) ?: "执行失败") }
    }

    private suspend fun log(ruleId: Long, at: Long, trigger: String, status: String, detail: String, refKind: String? = null, refId: Long? = null) =
        dao.insertRun(RuleRunRow(ruleId = ruleId, at = at, trigger = trigger, status = status, detail = detail, refKind = refKind, refId = refId))

    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.ifEmpty { null }
            ?: Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)

    private fun dayKey(at: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(at))
    private fun dayStart(at: Long): Long = java.util.Calendar.getInstance().apply { timeInMillis = at; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
}
