package app.resound.skills

import app.resound.data.MemoryDao
import app.resound.digest.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate

/** 安卓文件、模型、任务与旧日期入口。 */
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
    now: () -> Long,
    private val generation: SkillGeneration? = null,
    private val beforeWeeklyReview: suspend () -> Unit = {},
    private val hasBackup: (LlmPreset) -> Boolean = { false },
    private val draftRequest: suspend (LlmPreset, String, String, String) -> LlmReply = request,
    io: CoroutineDispatcher,
) : SkillRepository {
    private val store = SkillStoreRepository(files, dao, memory, scope,
        { model(it, request) }, { model(null, draftRequest) }, RoomSkillRunStore(dao), hotwords, { app.resound.appDisplayName },
        { title, body, link -> notifier.send(title, body, link, PushTopic.SkillCard) }, SkillCardPush::body, schedule, now,
        generation?.let { g -> { id, date -> g.generate(id, date.toJavaLocalDate()) } },
        generation?.generating ?: MutableStateFlow(emptySet<Pair<String, kotlinx.datetime.LocalDate>>()).asStateFlow(),
        generation?.generated ?: MutableStateFlow(emptyMap<Pair<String, kotlinx.datetime.LocalDate>, SkillRunResult>()).asStateFlow(),
        beforeWeeklyReview, app.resound.envsound.EnvSoundPresentation::changes, { it.card() },
        app.resound.envsound.EnvSoundPresentation::decorate, io)
    override val skills get() = store.skills
    override val status get() = store.status
    override val generating get() = store.generating
    override val generated get() = store.generated
    override val skillsWithCards get() = store.skillsWithCards
    override fun cards(date: kotlinx.datetime.LocalDate) = store.cards(date)
    fun cards(date: LocalDate) = store.cards(date.toKotlinLocalDate())
    override fun cardsOf(skillId: String) = store.cardsOf(skillId)
    override fun card(id: Long) = store.card(id)
    override fun generate(skillId: String, date: kotlinx.datetime.LocalDate) = store.generate(skillId, date)
    fun generate(skillId: String, date: LocalDate) = store.generate(skillId, date.toKotlinLocalDate())
    override suspend fun runNow(skillId: String, date: kotlinx.datetime.LocalDate) = store.runNow(skillId, date)
    suspend fun runNow(skillId: String, date: LocalDate) = store.runNow(skillId, date.toKotlinLocalDate())
    override suspend fun trial(skillId: String, date: kotlinx.datetime.LocalDate, promptOverride: String?) = store.trial(skillId, date, promptOverride)
    suspend fun trial(skillId: String, date: LocalDate, promptOverride: String? = null) = store.trial(skillId, date.toKotlinLocalDate(), promptOverride)
    internal suspend fun runGeneration(skillId: String, date: LocalDate, canRetry: Boolean = true) = store.runGeneration(skillId, date.toKotlinLocalDate(), canRetry)
    suspend fun runDue() = store.runDue()
    suspend fun afterDigest(manual: Boolean = false) = store.afterDigest(manual)
    suspend fun reschedule(runningWorker: Boolean = false) = store.reschedule(runningWorker)
    override fun setEnabled(skillId: String, enabled: Boolean) = store.setEnabled(skillId, enabled)
    override fun setSchedule(skillId: String, minutes: Int, weekdays: Set<Int>) = store.setSchedule(skillId, minutes, weekdays)
    override fun setPush(skillId: String, push: Boolean) = store.setPush(skillId, push)
    override fun setPreset(skillId: String, presetId: String?) = store.setPreset(skillId, presetId)
    override fun prompt(skillId: String) = store.prompt(skillId)
    override fun defaultPrompt(skillId: String) = store.defaultPrompt(skillId)
    override fun savePrompt(skillId: String, text: String) = store.savePrompt(skillId, text)
    override fun resetPrompt(skillId: String) = store.resetPrompt(skillId)
    override fun copyAsMine(skillId: String) = store.copyAsMine(skillId)
    override fun saveMine(skill: Skill, prompt: String) = store.saveMine(skill, prompt)
    override fun deleteMine(skillId: String) = store.deleteMine(skillId)
    override fun deleteCard(id: Long) = store.deleteCard(id)
    override suspend fun draft(conversation: List<DraftTurn>) = store.draft(conversation)
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
}
