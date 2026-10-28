package app.resound.skills

import app.resound.usage.UsagePurpose

import app.resound.data.MemoryDao
import app.resound.digest.*
import app.resound.push.Notifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class LocalSkillRepository(
    private val files: SkillFiles,
    private val dao: SkillDao,
    private val memory: MemoryDao,
    private val scope: CoroutineScope,
    private val credentials: (String?) -> Pair<LlmPreset, String>,
    private val request: suspend (LlmPreset, String, String, String) -> LlmReply,
    private val hotwords: () -> List<String>,
    private val notifier: Notifier,
    private val schedule: (Long?, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val generation: SkillGeneration? = null,
    private val beforeWeeklyReview: suspend () -> Unit = {},
) : SkillRepository {
    override val skills = files.skills
    override val generating = generation?.generating ?: MutableStateFlow(emptySet<Pair<String, LocalDate>>()).asStateFlow()
    override val generated = generation?.generated ?: MutableStateFlow(emptyMap<Pair<String, LocalDate>, SkillRunResult>()).asStateFlow()
    override fun generate(skillId: String, date: LocalDate) {
        checkNotNull(generation) { "后台生成未装配" }.generate(skillId, date)
    }
    internal suspend fun runGeneration(skillId: String, date: LocalDate, canRetry: Boolean = true) =
        run(skillId, date, SkillSchedule.MANUAL, background = true, canRetry = canRetry)
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val state = MutableStateFlow<Map<String, SkillStatus>>(emptyMap())
    override val status = state.asStateFlow()
    // 串行请求，避免试跑、手动与自动争抢同一技能状态和日限额。
    private val gate = Mutex()
    init {
        scope.launch {
            combine(skills, dao.runs(), running) { skills, runs, active ->
                skills.associate { s ->
                    val history = runs.filter { it.skillId == s.id }; val last = history.lastOrNull()
                    s.id to SkillStatus(s.id, s.id in active, last?.at, last?.result, last?.ok == false,
                        SkillSchedule.next(s, history, now()), history.filter { it.at >= now() - TimeUnit.DAYS.toMillis(30) }.sumOf { it.tokens })
                }
            }.collect { state.value = it }
        }
    }
    suspend fun reschedule(runningWorker: Boolean = false) {
        val next = skills.value.mapNotNull { SkillSchedule.next(it, dao.runsOf(it.id), now()) }.minOrNull()
        schedule(next, runningWorker)
    }
    private fun changed() { scope.launch { reschedule() } }
    override fun cards(date: LocalDate) = dao.cards(date.toEpochDay()).map { rows -> rows.map { it.card() } }
    override fun cardsOf(skillId: String) = dao.cardsOf(skillId).map { rows -> rows.map { it.card() } }
    override fun card(id: Long) = dao.card(id).map { it?.card() }
    override val skillsWithCards = dao.skillsWithCards()
    override fun setEnabled(skillId: String, enabled: Boolean) { files.change(skillId) { it.put("enabled", enabled) }; changed() }
    override fun setSchedule(skillId: String, minutes: Int, weekdays: Set<Int>) {
        if (skills.value.find { it.id == skillId }?.trigger !is SkillTrigger.Daily) return
        require(minutes in 0..1439 && weekdays.all { it in 1..7 })
        files.change(skillId) { it.put("schedule", SkillJson.trigger(SkillTrigger.Daily(minutes, weekdays))) }; changed()
    }
    override fun setPush(skillId: String, push: Boolean) { files.change(skillId) { it.put("push", push) } }
    override fun setPreset(skillId: String, presetId: String?) { files.change(skillId) { it.put("presetId", presetId ?: org.json.JSONObject.NULL) } }
    override fun prompt(skillId: String) = files.prompt(skillId)
    override fun defaultPrompt(skillId: String) = files.defaultPrompt(skillId)
    override fun savePrompt(skillId: String, text: String) = files.savePrompt(skillId, text)
    override fun resetPrompt(skillId: String) = files.resetPrompt(skillId)
    override fun copyAsMine(skillId: String) = files.copyAsMine(skillId).also { if (it.isSuccess) changed() }
    override fun saveMine(skill: Skill, prompt: String) = files.saveMine(skill, prompt).also { if (it.isSuccess) changed() }
    override fun deleteMine(skillId: String) { files.deleteMine(skillId); changed() }
    override fun deleteCard(id: Long) { scope.launch { dao.delete(id, now()) } }

    override suspend fun draft(conversation: List<DraftTurn>): Result<DraftStep> {
        if (conversation.firstOrNull { it.fromUser }?.text.isNullOrBlank()) return Result.failure(IllegalArgumentException("先描述一下想要什么"))
        val mustFinish = conversation.count { it.fromUser } > SkillDrafter.MAX_ASKS
        val (preset, key) = credentials(null)
        if (key.isBlank()) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        val id = "my_" + java.lang.Long.toString(now() / 1000, 36)
        // 格式不对重试一次；网络、密钥类错误直接告诉用户
        for (attempt in 0..1) {
            try {
                val reply = request(preset, key, SkillDrafter.SYSTEM, SkillDrafter.user(conversation))
                withContext(NonCancellable) { memory.addUsage(SkillSchedule.date(now()).toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Draft) }
                return Result.success(SkillDrafter.step(reply.content, id, mustFinish))
            } catch (e: CancellationException) { throw e }
            catch (e: LlmException) {
                if (e.type != LlmError.Format) return Result.failure(IllegalStateException(
                    if (e.type == LlmError.Auth) e.type.human else "网络不通，稍后再试"))
            } catch (_: Exception) { /* 模型输出不合格式，重试 */ }
        }
        return Result.failure(IllegalStateException("没生成成功，换个说法再试一次"))
    }
    override suspend fun runNow(skillId: String, date: LocalDate) = run(skillId, date, SkillSchedule.MANUAL).result
    override suspend fun trial(skillId: String, date: LocalDate, promptOverride: String?) = run(skillId, date, SkillSchedule.TRIAL, promptOverride).result

    suspend fun runDue() {
        for (s in skills.value) if (s.trigger !is SkillTrigger.AfterDigest) isolated { runDays(s, SkillSchedule.DAILY) }
    }
    /** manual：用户点“现在整理”触发的，跳过最短间隔，整理完立刻出卡。 */
    suspend fun afterDigest(manual: Boolean = false) {
        for (s in skills.value) if (s.trigger is SkillTrigger.AfterDigest) isolated { runDays(s, SkillSchedule.AFTER, manual) }
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
    private suspend fun run(id: String, date: LocalDate, kind: String, promptOverride: String? = null, background: Boolean = false, canRetry: Boolean = true, skipGap: Boolean = false): SkillAttempt = gate.withLock {
        val skill = skills.value.find { it.id == id } ?: return@withLock SkillAttempt(SkillRunResult.Failed("技能不存在"))
        if (skill.origin == SkillOrigin.Core) return@withLock SkillAttempt(SkillRunResult.Nothing("请使用原来的整理或回顾设置"))
        var started = now(); val history = dao.runsOf(id)
        val auto = kind == SkillSchedule.DAILY || kind == SkillSchedule.AFTER
        val preview = kind == SkillSchedule.TRIAL
        if (auto) {
            // 每天 6 次只限定时技能；整理后技能已有“至少隔 N 分钟”，再限次数会让下午、晚上的事等到第二天（10-25 实测）。
            if (!skill.enabled || (kind == SkillSchedule.DAILY && SkillSchedule.autoCount(history, SkillSchedule.date(started)) >= 6)) return@withLock SkillAttempt(SkillRunResult.Nothing("今天自动运行已达上限或技能已关闭"))
            if (kind == SkillSchedule.DAILY && date == SkillSchedule.date(started) && SkillSchedule.next(skill, history, started) != started) return@withLock SkillAttempt(SkillRunResult.Nothing("尚未到运行时间"))
            if (kind == SkillSchedule.AFTER) {
                val trigger = skill.trigger as? SkillTrigger.AfterDigest ?: return@withLock SkillAttempt(SkillRunResult.Nothing("不是整理后技能"))
                val last = history.lastOrNull { it.kind != SkillSchedule.TRIAL && it.date == date.toEpochDay() }
                if (!skipGap && last != null && started - last.at < TimeUnit.MINUTES.toMillis(trigger.minGapMinutes.toLong())) return@withLock SkillAttempt(SkillRunResult.Nothing("距离上次运行太近"))
            }
        }
        running.value = running.value + id
        var tokens = 0L; var retryable = false
        try {
            // 每周回顾实际是内置 weekly 技能；通过共用入口覆盖自动、手动和试跑。
            if (id == "weekly") { beforeWeeklyReview(); started = now() }
            val result = try {
                val (preset, key) = credentials(skill.presetId)
                if (key.isBlank()) SkillRunResult.Failed("还没填写大模型密钥") else {
                    // 每个归属日各记各的进度，别的日子的运行不算。
                    val since = history.lastOrNull { it.ok && it.kind != SkillSchedule.TRIAL && it.date == date.toEpochDay() }?.at
                    val input = SkillInputs.load(dao, skill, date, since, started)
                    if (skill.scope.speakers == SkillSpeakers.MeOnly && skill.scope.includeLines && input.lines.isEmpty()) {
                        SkillRunResult.Nothing(if (skill.id == "mood_diary") "今天没有明显的情绪表达" else "今天记录太少")
                    } else {
                        var parsed: SkillOutput.Parsed? = null
                        for (attempt in 0..1) {
                            try {
                                val reply = request(preset, key, SkillPrompt.system(skill, promptOverride ?: files.prompt(id), date), SkillInputs.user(input, hotwords()))
                                tokens += reply.promptTokens + reply.completionTokens
                                withContext(NonCancellable) { memory.addUsage(SkillSchedule.date(started).toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Skill) }
                                parsed = SkillOutput.parse(reply.content, skill, date, input, now(), manual = !auto)
                                break
                            } catch (e: LlmException) {
                                if (e.type != LlmError.Format || attempt == 1) throw e
                            }
                        }
                        val output = requireNotNull(parsed)
                        val cards = mutableListOf<SkillCard>()
                        var skippedReason = output.reason
                        if (preview) cards += output.rows.take(skill.maxCardsPerDay).map { it.card() }
                        else {
                            for (row in output.rows) {
                                val saved = when (val merged = dao.mergeWithReason(row, skill.maxCardsPerDay, manual = !auto)) {
                                    SkillMergeResult.Deleted -> { skippedReason = "这天的卡你删过，自动运行跳过了"; continue }
                                    is SkillMergeResult.Full -> { skippedReason = "今天已经有 ${merged.count} 张，达到每天上限"; continue }
                                    is SkillMergeResult.Saved -> merged
                                }
                                cards.removeAll { it.id == saved.row.id }; cards += saved.row.card()
                                if (auto && skill.push && saved.isNew) {
                                    try { saved.row.card().let { card -> notifier.send(SkillCardPush.title(card), SkillCardPush.body(card), "resound://card?open=card&id=${saved.row.id}", PushTopic.SkillCard) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { /* 卡已保存；推送通道记录自己的错误，不重复生成。 */ }
                                }
                            }
                        }
                        if (cards.isEmpty()) SkillRunResult.Nothing(skippedReason) else SkillRunResult.Cards(cards)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: LlmException) {
                retryable = canRetry && e.type in setOf(LlmError.Network, LlmError.Server)
                SkillRunResult.Failed(when (e.type) {
                    LlmError.Network, LlmError.Server -> if (canRetry) "网络不通，稍后会再试" else "网络或服务器异常，已重试 2 次，请稍后再试"
                    LlmError.Format -> "模型回复格式不对"
                    LlmError.Auth -> e.type.human
                })
            } catch (_: Exception) { SkillRunResult.Failed("技能运行失败，请重试") }
            record(id, date, started, kind, result, tokens, retryable)
            SkillAttempt(result, retryable)
        } catch (e: CancellationException) {
            // 被系统中断的已计费用量仍留痕；不会被当成成功的增量游标。
            withContext(NonCancellable) { record(id, date, started, kind, SkillRunResult.Failed(if (background) "系统暂停，等待后台重试" else "运行已中断"), tokens, true) }
            throw e
        } finally { running.value = running.value - id }
    }
    private suspend fun record(id: String, date: LocalDate, at: Long, kind: String, result: SkillRunResult, tokens: Long, retryable: Boolean) {
        val text = when (result) {
            is SkillRunResult.Cards -> "出了 ${result.cards.size} 张卡"
            is SkillRunResult.Nothing -> result.reason
            is SkillRunResult.Failed -> result.reason
        }
        dao.run(SkillRunRow(skillId = id, date = date.toEpochDay(), at = at, ok = result !is SkillRunResult.Failed,
            result = text, tokens = tokens, kind = kind, retryable = retryable))
        dao.trimRuns(now() - TimeUnit.DAYS.toMillis(30))
    }
}

internal data class SkillAttempt(val result: SkillRunResult, val retryable: Boolean = false)
