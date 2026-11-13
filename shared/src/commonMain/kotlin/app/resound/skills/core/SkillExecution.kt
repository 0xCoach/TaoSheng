package app.resound.skills.core
import app.resound.skills.*
import app.resound.platform.NowMillis
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 调用、用量和通知由平台提供；串行运行、重试、门槛和完成凭据在这里。 */
class SkillExecution(
 private val files: SkillDefinitionStore,
 private val dao: SkillRunStore,
 private val clock: NowMillis,
 private val models: (String?) -> SkillModel,
 private val hotwords: () -> List<String>,
 private val appName: () -> String,
 private val usage: suspend (String,Long,Long) -> Unit,
 private val push: suspend (SkillCard) -> Unit,
 private val decorate: suspend (SkillCard) -> SkillCard,
 private val beforeWeeklyReview: suspend () -> Unit,
 private val running: (String,Boolean) -> Unit,
) {
 private val gate = Mutex()
 private fun now() = clock.now()
    suspend fun runDue() {
        for (s in files.skills.value) if (s.trigger !is SkillTrigger.AfterDigest) isolated { runDays(s, SkillSchedule.DAILY) }
    }
    /** manual：用户点“现在整理”触发的，跳过最短间隔，整理完立刻出卡。 */
    suspend fun afterDigest(manual: Boolean = false) {
        for (s in files.skills.value) if (s.trigger is SkillTrigger.AfterDigest) isolated { runDays(s, SkillSchedule.AFTER, manual) }
    }
    /** “上次以来”的技能按事件发生日出卡：昨天下午的会凌晨才整理出来，先补跑昨天，卡片归昨天，再跑今天。 */
    private suspend fun runDays(s: Skill, kind: String, skipGap: Boolean = false) {
        val today = SkillSchedule.date(now())
        if (s.scope.range == SkillRange.SinceLastRun) {
            val last = dao.runsOf(s.id).lastOrNull { it.ok && it.kind != SkillSchedule.TRIAL }?.at
            if (last != null) dao.newEventStarts(last, now()).map(SkillSchedule::date).distinct().filter { it < today }.sorted()
                .forEach { day -> isolated { run(s.id, day, kind, skipGap = skipGap) } }
        }
        run(s.id, today, kind, skipGap = skipGap)
    }
    private suspend fun isolated(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 单个技能失败不阻断后续技能。 */ }
    }
    suspend fun run(id: String, date: LocalDate, kind: String, promptOverride: String? = null, background: Boolean = false, canRetry: Boolean = true, skipGap: Boolean = false): SkillAttempt = gate.withLock {
        val skill = files.skills.value.find { it.id == id } ?: return@withLock SkillAttempt(SkillRunResult.Failed("技能不存在"))
        if (skill.origin == SkillOrigin.Core) return@withLock SkillAttempt(SkillRunResult.Nothing("请使用原来的整理或回顾设置"))
        var started = now(); val history = dao.runsOf(id)
        val auto = kind == SkillSchedule.DAILY || kind == SkillSchedule.AFTER
        val preview = kind == SkillSchedule.TRIAL
        SkillRunRules.skip(skill,date,kind,history,started,skipGap)?.let {
            return@withLock SkillAttempt(SkillRunResult.Nothing(it))
        }
        running(id, true)
        var tokens = 0L; var retryable = false; var gateDiscarded = 0
        try {
            // 每周回顾实际是内置 weekly 技能；通过共用入口覆盖自动、手动和试跑。
            if (id == "weekly") { beforeWeeklyReview(); started = now() }
            val result = try {
                val model = models(skill.presetId)
                if (!model.ready) SkillRunResult.Failed("还没填写大模型密钥") else {
                    // 每个归属日各记各的进度，别的日子的运行不算。
                    val since = history.lastOrNull { it.ok && it.kind != SkillSchedule.TRIAL && it.date == date.toEpochDay() }?.at
                    val source = SkillInputs.source(dao, skill, date, since, started)
                    val gateLines = SkillInputs.gateLines(source.lines, skill.scope.speakers)
                    val gateFailure = SkillGateRules.failure(skill.gate, gateLines)
                    if (gateFailure != null) SkillRunResult.Nothing(gateFailure) else {
                        val input = SkillInputs.prepare(dao, skill, date, started, source)
                        if (skill.scope.speakers == SkillSpeakers.MeOnly && skill.scope.includeLines && input.lines.isEmpty()) {
                            SkillRunResult.Nothing(if (skill.id == "mood_diary") "今天没有明显的情绪表达" else "今天记录太少")
                        } else {
                            var parsed: SkillOutput.Parsed? = null
                            for (attempt in 0..1) {
                                try {
                                    val reply = model.request(SkillPrompt.system(skill, promptOverride ?: files.prompt(id), date, appName()), SkillInputs.user(input, hotwords()))
                                    tokens += reply.promptTokens + reply.completionTokens
                                    withContext(NonCancellable) { usage(SkillSchedule.date(started).toString(), reply.promptTokens, reply.completionTokens) }
                                    parsed = try { SkillOutput.parse(reply.content, skill, date, input, now(), manual = !auto) } catch (_: SkillFormatException) { throw SkillCallException("Format", "模型回复格式不对", "模型回复格式不对") }
                                    break
                                } catch (e: SkillCallException) {
                                    if (e.kind != "Format" || attempt == 1) throw e
                                }
                            }
                            val output = requireNotNull(parsed)
                            val cards = mutableListOf<SkillCard>()
                            var skippedReason = output.reason
                            val rows = output.rows.filter { row ->
                                val reason = SkillGateRules.cardFailure(skill.gate, gateLines, row.at, row.endAt)
                                if (reason != null) {
                                    if (gateDiscarded == 0) skippedReason = reason
                                    gateDiscarded++
                                }
                                reason == null
                            }
                            if (preview) cards += rows.take(skill.maxCardsPerDay).map { decorate(it.card()) }
                            else {
                                for (row in rows) {
                                    val saved = when (val merged = dao.mergeWithReason(row, skill.maxCardsPerDay, manual = !auto)) {
                                        SkillMergeResult.Deleted -> { skippedReason = "这天的卡你删过，自动运行跳过了"; continue }
                                        is SkillMergeResult.Full -> { skippedReason = "今天已经有 ${merged.count} 张，达到每天上限"; continue }
                                        is SkillMergeResult.Saved -> merged
                                    }
                                    cards.removeAll { it.id == saved.row.id }; cards += decorate(saved.row.card())
                                    if (auto && skill.push && saved.isNew) {
                                        try { push(decorate(saved.row.card())) }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { /* 卡已保存；推送通道记录自己的错误，不重复生成。 */ }
                                    }
                                }
                            }
                            if (cards.isEmpty()) SkillRunResult.Nothing(skippedReason) else SkillRunResult.Cards(cards)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SkillCallException) {
                retryable = canRetry && e.kind in setOf("Network", "Server", "RateLimit")
                SkillRunResult.Failed(if (e.detail != e.human) e.detail else when (e.kind) {
                    "Network", "Server" -> if (canRetry) "网络不通，稍后会再试" else "网络或服务器异常，已重试 2 次，请稍后再试"
                    "Format" -> "模型回复格式不对"
                    else -> e.human
                })
            } catch (_: Exception) { SkillRunResult.Failed("技能运行失败，请重试") }
            record(id, date, started, kind, result, tokens, retryable, gateDiscarded)
            SkillAttempt(result, retryable)
        } catch (e: CancellationException) {
            // 被系统中断的已计费用量仍留痕；不会被当成成功的增量游标。
            withContext(NonCancellable) { record(id, date, started, kind, SkillRunResult.Failed(if (background) "系统暂停，等待后台重试" else "运行已中断"), tokens, true, gateDiscarded) }
            throw e
        } finally { running(id, false) }
    }
    private suspend fun record(id: String, date: LocalDate, at: Long, kind: String, result: SkillRunResult, tokens: Long, retryable: Boolean, gateDiscarded: Int = 0) {
        val text = SkillRunRules.resultText(result,gateDiscarded)
        dao.run(SkillRunData(skillId = id, date = date.toEpochDay(), at = at, ok = result !is SkillRunResult.Failed,
            result = text, tokens = tokens, kind = kind, retryable = retryable))
        dao.trimRuns(now() - 30L * 86_400_000L)
    }
}
data class SkillAttempt(val result: SkillRunResult,val retryable: Boolean = false)
data class SkillReply(val content: String,val promptTokens: Long,val completionTokens: Long)
interface SkillModel {
 val ready: Boolean
 suspend fun request(system: String,user: String): SkillReply
}
class SkillCallException(val kind: String,val human: String,val detail: String) : Exception(detail)
sealed interface SkillMergeResult {
 data class Saved(val row: SkillCardData,val isNew: Boolean) : SkillMergeResult
 data object Deleted : SkillMergeResult
 data class Full(val count: Int) : SkillMergeResult
}
interface SkillRunStore : SkillInputStore {
 suspend fun runsOf(id: String): List<SkillRunData>
 suspend fun newEventStarts(since: Long,until: Long): List<Long>
 suspend fun mergeWithReason(row: SkillCardData,max: Int,manual: Boolean): SkillMergeResult
 suspend fun run(row: SkillRunData)
 suspend fun trimRuns(before: Long)
}
