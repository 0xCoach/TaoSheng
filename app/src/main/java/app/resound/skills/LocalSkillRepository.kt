package app.resound.skills

import app.resound.usage.UsagePurpose

import app.resound.data.MemoryDao
import app.resound.digest.*
import app.resound.push.Notifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
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
    private val hasBackup: (LlmPreset) -> Boolean = { false },
    private val draftRequest: suspend (LlmPreset, String, String, String) -> LlmReply = request,
) : SkillRepository {
    override fun cards(date: kotlinx.datetime.LocalDate) = cards(date.toJavaLocalDate())
    override fun generate(skillId: String, date: kotlinx.datetime.LocalDate) = generate(skillId, date.toJavaLocalDate())
    override suspend fun runNow(skillId: String, date: kotlinx.datetime.LocalDate) = runNow(skillId, date.toJavaLocalDate())
    override suspend fun trial(skillId: String, date: kotlinx.datetime.LocalDate, promptOverride: String?) = trial(skillId,date.toJavaLocalDate(),promptOverride)
    override val skills = files.skills
    override val generating = generation?.generating ?: MutableStateFlow(emptySet<Pair<String, kotlinx.datetime.LocalDate>>()).asStateFlow()
    override val generated = generation?.generated ?: MutableStateFlow(emptyMap<Pair<String, kotlinx.datetime.LocalDate>, SkillRunResult>()).asStateFlow()
    fun generate(skillId: String, date: LocalDate) {
        checkNotNull(generation) { "后台生成未装配" }.generate(skillId, date)
    }
    internal suspend fun runGeneration(skillId: String, date: LocalDate, canRetry: Boolean = true) =
        run(skillId, date, SkillSchedule.MANUAL, background = true, canRetry = canRetry)
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
    fun cards(date: LocalDate) = combine(dao.cards(date.toEpochDay()), app.resound.envsound.EnvSoundPresentation.changes()) { rows, _ -> rows.map { it.card() } }.flowOn(Dispatchers.IO)
    override fun cardsOf(skillId: String) = combine(dao.cardsOf(skillId), app.resound.envsound.EnvSoundPresentation.changes()) { rows, _ -> rows.map { it.card() } }.flowOn(Dispatchers.IO)
    override fun card(id: Long) = combine(dao.card(id), app.resound.envsound.EnvSoundPresentation.changes()) { row, _ -> row?.card() }.flowOn(Dispatchers.IO)
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
        conversation, app.resound.platform.NowMillis(now), app.resound.appDisplayName,
        model = { model(null, draftRequest) },
        usage = { date,prompt,completion -> memory.addUsage(date,prompt,completion,UsagePurpose.Draft) },
    )
    suspend fun runNow(skillId: String, date: LocalDate) = run(skillId, date, SkillSchedule.MANUAL).result
    suspend fun trial(skillId: String, date: LocalDate, promptOverride: String? = null) = run(skillId, date, SkillSchedule.TRIAL, promptOverride).result

    private fun model(presetId: String?, call: suspend (LlmPreset,String,String,String) -> LlmReply): app.resound.skills.core.SkillModel {
        val (preset, key) = credentials(presetId)
        return object : app.resound.skills.core.SkillModel {
            override val ready = !(key.isBlank() && !preset.allowsEmptyKey() && !hasBackup(preset))
            override suspend fun request(system: String,user: String): app.resound.skills.core.SkillReply {
                val reply = try { call(preset,key,system,user) }
                catch (e: LlmException) { throw app.resound.skills.core.SkillCallException(e.type.name,e.type.human,e.detail) }
                // 先归还成功调用以记用量；坏 JSON 保留给共用解析器报格式错。
                val content = runCatching { org.json.JSONObject(reply.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).toString() }.getOrDefault(reply.content)
                return app.resound.skills.core.SkillReply(content,reply.promptTokens,reply.completionTokens)
            }
        }
    }
    private val execution = app.resound.skills.core.SkillExecution(
        files, RoomSkillRunStore(dao), app.resound.platform.NowMillis(now),
        models = { presetId -> model(presetId, request) }, hotwords = hotwords, appName = { app.resound.appDisplayName },
        usage = { date,prompt,completion -> memory.addUsage(date,prompt,completion,UsagePurpose.Skill) },
        push = { card -> notifier.send(SkillCardPush.title(card),SkillCardPush.body(card),"resound://card?open=card&id=${card.id}",PushTopic.SkillCard) },
        decorate = app.resound.envsound.EnvSoundPresentation::decorate,
        beforeWeeklyReview = beforeWeeklyReview,
        running = { id,active -> running.value = if(active) running.value + id else running.value - id },
    )
    suspend fun runDue() = execution.runDue()
    suspend fun afterDigest(manual: Boolean = false) = execution.afterDigest(manual)
    private suspend fun run(id: String,date: LocalDate,kind: String,promptOverride: String? = null,background: Boolean = false,canRetry: Boolean = true) =
        execution.run(id,date.toKotlinLocalDate(),kind,promptOverride,background,canRetry)
}
