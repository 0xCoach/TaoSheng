package app.resound.review

import android.content.SharedPreferences
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toJavaLocalDate

/** 安卓系统任务、模型、通知、偏好与旧日期调用的适配。 */
class RoomReviewRepository(
    dao: ReviewDao, memory: MemoryDao, prefs: SharedPreferences, scope: CoroutineScope,
    credentials: () -> Pair<LlmPreset, String>, chat: suspend (LlmPreset, String, String, String) -> LlmReply,
    template: () -> String, appName: String, private val notifier: Notifier, reschedule: (Int) -> Unit,
    now: () -> Long, zone: () -> ZoneId,
    dailyMemory: suspend (LocalDate) -> Unit = {}, beforeReview: suspend () -> Boolean = { true },
    work: androidx.work.WorkManager? = null, hasBackup: (LlmPreset) -> Boolean = { false },
    io: CoroutineDispatcher,
) : ReviewRepository {
    private var generation: ManualReviewGeneration? = null
    private val store = ReviewStoreRepository(dao, memory,
        ReviewStoredSettings(prefs.getString("lastError", null), prefs.getInt("dailyAtMinutes", 22 * 60),
            prefs.getBoolean("pushEnabled", true), prefs.getString("lastErrorDate", null)),
        credentials, { preset, key, system, input -> chat(preset, key, system, input).let { ReviewReply(it.content, it.promptTokens, it.completionTokens, it.actualModel) } },
        template, appName, ::notify, reschedule, now, ZoneProvider { zone().id }, io,
        { dailyMemory(it.toJavaLocalDate()) }, beforeReview, hasBackup, LlmPreset::allowsEmptyKey, LlmInputBudget::inputChars,
        { (it as? LlmException)?.let { e -> ReviewFailure(e.detail, e.type.retryable) } },
        if (work == null) null else { date -> generation!!.generate(date.toJavaLocalDate()) },
        { generation?.isActive() == true }, app.resound.envsound.EnvSoundPresentation::changes,
        app.resound.envsound.EnvSoundPresentation::decorate,
        { prefs.edit().putInt("dailyAtMinutes", it).apply() }, { prefs.edit().putBoolean("pushEnabled", it).apply() },
        { message, date -> prefs.edit().putString("lastError", message).putString("lastErrorDate", date).apply() })
    init {
        generation = work?.let { ManualReviewGeneration(it, scope, store::generationChanged,
            { date, message -> store.generationFailed(date.toKotlinLocalDate(), message) }) }
    }
    override val state get() = store.state
    override fun daily(date: kotlinx.datetime.LocalDate) = store.daily(date)
    fun daily(date: LocalDate) = store.daily(date.toKotlinLocalDate())
    override fun recent() = store.recent()
    override fun generateNow(date: kotlinx.datetime.LocalDate) = store.generateNow(date)
    fun generateNow(date: LocalDate) = store.generateNow(date.toKotlinLocalDate())
    override fun hasMaterial(date: kotlinx.datetime.LocalDate) = store.hasMaterial(date)
    fun hasMaterial(date: LocalDate) = store.hasMaterial(date.toKotlinLocalDate())
    override fun windowText(date: kotlinx.datetime.LocalDate) = store.windowText(date)
    fun windowText(date: LocalDate) = store.windowText(date.toKotlinLocalDate())
    override fun setDailyAt(minutes: Int) = store.setDailyAt(minutes)
    override fun setPushEnabled(enabled: Boolean) = store.setPushEnabled(enabled)
    internal suspend fun run(date: LocalDate, scheduled: Boolean) = store.run(date.toKotlinLocalDate(), scheduled)
    private suspend fun notify(title: String, body: String, link: String, row: ReviewEntity) {
        if (notifier is ChannelNotifier) notifier.send(title, body, link, PushTopic.DailyReview, remoteReview = store.review(row))
        else notifier.send(title, body, link, PushTopic.DailyReview)
    }
}
