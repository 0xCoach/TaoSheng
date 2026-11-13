package app.resound.skills

import app.resound.data.MemoryDao
import app.resound.skills.core.*
import app.resound.skills.core.SkillSchedule as CoreSchedule
import app.resound.skills.core.SkillCardPush as CorePush
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate

/** 仓储查询、状态与设置协调；文件、系统任务和模型通过现有端口接入。 */
class SkillStoreRepository(
    private val files: SkillDefinitionStore, private val dao: SkillDao,
    private val memory: MemoryDao, private val scope: CoroutineScope,
    models: (String?) -> SkillModel, private val draftModel: () -> SkillModel,
    runStore: SkillRunStore, hotwords: () -> List<String>, private val appName: () -> String,
    push: suspend (String, String, String) -> Unit, pushBody: (SkillCard) -> String,
    private val schedule: (Long?, Boolean) -> Unit, private val now: () -> Long,
    private val generateRequest: ((String, LocalDate) -> Unit)?,
    generatingState: StateFlow<Set<Pair<String, LocalDate>>>,
    generatedState: StateFlow<Map<Pair<String, LocalDate>, SkillRunResult>>,
    beforeWeeklyReview: suspend () -> Unit,
    private val presentationChanges: () -> Flow<Unit>,
    private val cardModel: suspend (SkillCardRow) -> SkillCard,
    decorate: suspend (SkillCard) -> SkillCard,
    private val io: CoroutineDispatcher,
) : SkillRepository {
    override val skills = files.skills
    override val generating = generatingState
    override val generated = generatedState
    override fun generate(skillId: String, date: LocalDate) {
        checkNotNull(generateRequest) { "后台生成未装配" }.invoke(skillId, date)
    }
    suspend fun runGeneration(skillId: String, date: LocalDate, canRetry: Boolean = true) =
        run(skillId, date, CoreSchedule.MANUAL, background = true, canRetry = canRetry)
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val state = MutableStateFlow<Map<String, SkillStatus>>(emptyMap())
    override val status = state.asStateFlow()
    // 串行请求，避免试跑、手动与自动争抢同一技能状态和日限额。
    init {
        scope.launch {
            combine(skills, dao.runs(), running) { skills, runs, active ->
                skills.associate { s ->
                    val history = runs.filter { it.skillId == s.id }; val last = history.lastOrNull()
                    s.id to SkillStatus(s.id, s.id in active, last?.at, last?.result, last?.ok == false,
                        CoreSchedule.next(s, history.map { it.snapshot() }, now()), history.filter { it.at >= now() - 30 * 86_400_000L }.sumOf { it.tokens })
                }
            }.collect { state.value = it }
        }
    }
    suspend fun reschedule(runningWorker: Boolean = false) {
        val next = skills.value.mapNotNull { CoreSchedule.next(it, dao.runsOf(it.id).map { it.snapshot() }, now()) }.minOrNull()
        schedule(next, runningWorker)
    }
    private fun changed() { scope.launch { reschedule() } }
    override fun cards(date: LocalDate) = combine(dao.cards(date.toEpochDays().toLong()), presentationChanges()) { rows, _ -> rows.map { cardModel(it) } }.flowOn(io)
    override fun cardsOf(skillId: String) = combine(dao.cardsOf(skillId), presentationChanges()) { rows, _ -> rows.map { cardModel(it) } }.flowOn(io)
    override fun card(id: Long) = combine(dao.card(id), presentationChanges()) { row, _ -> row?.let { cardModel(it) } }.flowOn(io)
    override val skillsWithCards = dao.skillsWithCards()
    override fun setEnabled(skillId: String, enabled: Boolean) { files.setEnabled(skillId, enabled); changed() }
    override fun setSchedule(skillId: String, minutes: Int, weekdays: Set<Int>) {
        if (skills.value.find { it.id == skillId }?.trigger !is SkillTrigger.Daily) return
        require(minutes in 0..1439 && weekdays.all { it in 1..7 })
        files.setSchedule(skillId, SkillTrigger.Daily(minutes, weekdays)); changed()
    }
    override fun setPush(skillId: String, push: Boolean) { files.setPush(skillId,push) }
    override fun setPreset(skillId: String, presetId: String?) { files.setPreset(skillId,presetId) }
    override fun prompt(skillId: String) = files.prompt(skillId)
    override fun defaultPrompt(skillId: String) = files.defaultPrompt(skillId)
    override fun savePrompt(skillId: String, text: String) = files.savePrompt(skillId, text)
    override fun resetPrompt(skillId: String) = files.resetPrompt(skillId)
    override fun copyAsMine(skillId: String) = files.copyAsMine(skillId).also { if (it.isSuccess) changed() }
    override fun saveMine(skill: Skill, prompt: String) = files.saveMine(skill, prompt).also { if (it.isSuccess) changed() }
    override fun deleteMine(skillId: String) { files.deleteMine(skillId); changed() }
    override fun deleteCard(id: Long) { scope.launch { dao.delete(id, now()) } }

    override suspend fun draft(conversation: List<DraftTurn>) = app.resound.skills.core.SkillDrafting.draft(
        conversation, app.resound.platform.NowMillis(now), appName(),
        model = draftModel,
        usage = { date,prompt,completion -> memory.addUsage(date,prompt,completion,UsagePurpose.Draft) },
    )
    override suspend fun runNow(skillId: String, date: LocalDate) = run(skillId, date, CoreSchedule.MANUAL).result
    override suspend fun trial(skillId: String, date: LocalDate, promptOverride: String?) = run(skillId, date, CoreSchedule.TRIAL, promptOverride).result

    private val execution = app.resound.skills.core.SkillExecution(
        files, runStore, app.resound.platform.NowMillis(now),
        models = models, hotwords = hotwords, appName = appName,
        usage = { date,prompt,completion -> memory.addUsage(date,prompt,completion,UsagePurpose.Skill) },
        push = { card -> push(CorePush.title(card), pushBody(card), "resound://card?open=card&id=${card.id}") },
        decorate = decorate,
        beforeWeeklyReview = beforeWeeklyReview,
        running = { id,active -> running.value = if(active) running.value + id else running.value - id },
    )
    suspend fun runDue() = execution.runDue()
    suspend fun afterDigest(manual: Boolean = false) = execution.afterDigest(manual)
    private suspend fun run(id: String,date: LocalDate,kind: String,promptOverride: String? = null,background: Boolean = false,canRetry: Boolean = true) =
        execution.run(id,date,kind,promptOverride,background,canRetry)
}
