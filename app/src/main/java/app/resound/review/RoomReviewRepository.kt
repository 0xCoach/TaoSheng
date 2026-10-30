package app.resound.review

import app.resound.digest.allowsEmptyKey
import app.resound.usage.UsagePurpose

import android.content.SharedPreferences
import app.resound.data.MemoryDao
import app.resound.data.ReviewDao
import app.resound.data.ReviewEntity
import app.resound.data.ReviewInput
import app.resound.digest.LlmException
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.push.Notifier
import app.resound.push.ChannelNotifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

class RoomReviewRepository(
    private val dao: ReviewDao,
    private val memory: MemoryDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val credentials: () -> Pair<LlmPreset, String>,
    private val chat: suspend (LlmPreset, String, String, String) -> LlmReply,
    private val template: () -> String,
    private val appName: String,
    private val notifier: Notifier,
    private val reschedule: (Int) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val dailyMemory: suspend (LocalDate) -> Unit = {},
    private val beforeReview: suspend () -> Boolean = { true },
    private val work: androidx.work.WorkManager? = null,
) : ReviewRepository {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(ReviewState(false, prefs.getString("lastError", null),
        prefs.getInt("dailyAtMinutes", 22 * 60).coerceIn(0, 1439), prefs.getBoolean("pushEnabled", true),
        lastErrorDate = prefs.getString("lastErrorDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }))
    override val state = mutable.asStateFlow()
    override fun daily(date: LocalDate): Flow<DailyReview?> = combine(dao.daily(date.toString()), app.resound.envsound.EnvSoundPresentation.changes()) { row, _ -> row?.toModel() }.flowOn(Dispatchers.IO)
    override fun recent(): Flow<List<DailyReview>> = combine(dao.recent(), app.resound.envsound.EnvSoundPresentation.changes()) { rows, _ -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)
    @Volatile private var running = false
    private val generation = work?.let { manager ->
        ManualReviewGeneration(manager, scope,
            changed = { active ->
                mutable.update { it.copy(generating = running || active,
                    stage = if (running) it.stage else if (active) "排队中…" else null) }
            },
            failed = { date, message -> error(message, date) })
    }
    override fun generateNow(date: LocalDate) {
        if (generation == null) {
            error("生成失败：IllegalStateException", date)
            return
        }
        generation.generate(date)
    }

    override fun hasMaterial(date: LocalDate): Flow<Boolean> =
        combine(state.map { it.dailyAtMinutes }.distinctUntilChanged(),
            dao.materialChanges(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1"))) { minutes, _ ->
            material(date, minutes, zone()).second != null
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun windowText(date: LocalDate): String {
        val minutes = state.value.dailyAtMinutes
        fun label(day: LocalDate) = "${day.monthValue}月${day.dayOfMonth}日 ${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
        return "${label(date.minusDays(1))} – ${label(date)}"
    }

    /** 空值即无材料；窗口和三类材料的规则供观察与生成共用。 */
    private suspend fun material(date: LocalDate, minutes: Int, timezone: ZoneId): Pair<Long, ReviewInput?> {
        val start = ReviewTime.cutoff(date.minusDays(1), minutes, timezone).toInstant().toEpochMilli()
        val end = ReviewTime.cutoff(date, minutes, timezone).toInstant().toEpochMilli()
        val source = dao.reviewInput(start, end)
        return end to source.takeIf { it.events.isNotEmpty() || it.items.isNotEmpty() || it.starredStale.isNotEmpty() }
    }
    override fun setDailyAt(minutes: Int) {
        val value = minutes.coerceIn(0, 1439)
        prefs.edit().putInt("dailyAtMinutes", value).apply()
        mutable.update { it.copy(dailyAtMinutes = value) }
        reschedule(value)
    }
    override fun setPushEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pushEnabled", enabled).apply()
        mutable.update { it.copy(pushEnabled = enabled) }
    }

    /** 手动强制重生成且不推送；定时只复用截止时刻后生成的回顾，避免重试重复扣用量。返回是否需要重试。 */
    internal suspend fun run(date: LocalDate, scheduled: Boolean): Boolean = gate.withLock {
        running = true
        mutable.update { it.copy(generating = true, stage = "正在转写和整理还没处理的录音…") }
        try {
            val prepared = try {
                withTimeoutOrNull(10 * 60_000L) { beforeReview() } ?: false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            mutable.update { it.copy(stage = "正在写回顾…") }
            val timezone = zone()
            val minutes = state.value.dailyAtMinutes
            val (end, source) = material(date, minutes, timezone)
            if (source == null) {
                error(if (scheduled) null else "${windowText(date)} 没有可写进回顾的事", date)
                return@withLock false
            }
            var row = if (scheduled) dao.daily(date.toString()).first()?.takeIf { it.generatedAt >= end } else null
            if (row == null) {
                val model: String
                val result = if (source.events.isEmpty() && source.items.isEmpty()) {
                    model = "local-starred-followup"
                    ReviewPrompt.staleOnly(source, timezone)
                } else {
                    val (preset, key) = credentials()
                    if (key.isBlank() && !preset.allowsEmptyKey()) { error("还没填写整理模型的密钥", date); return@withLock false }
                    val reply = chat(preset, key, ReviewPrompt.system(template(), appName), ReviewPrompt.input(date, source, timezone, minutes))
                    // 格式错误的成功调用也实际消耗了 token。
                    memory.addUsage(java.time.Instant.ofEpochMilli(now()).atZone(timezone).toLocalDate().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Review)
                    model = preset.model
                    try { ReviewPrompt.parse(reply.content, source, timezone) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        error("模型返回的格式不对，换个模型或稍后再试", date)
                        return@withLock false
                    }
                }
                val (brief, sections) = result
                row = ReviewEntity(startDate = date.toString(), endDate = date.toString(), brief = brief,
                    sectionsJson = ReviewPrompt.encode(sections), generatedAt = now(), model = model)
                dao.save(row)
            }
            if (scheduled) dailyMemory(date)
            if (scheduled && state.value.pushEnabled) {
                try {
                    val deepLink = "resound://review?open=review&date=$date"
                    if (notifier is ChannelNotifier) {
                        notifier.send("今日回顾", row.brief, deepLink, PushTopic.DailyReview, remoteReview = row.toModel())
                    } else {
                        notifier.send("今日回顾", row.brief, deepLink, PushTopic.DailyReview)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error("回顾已生成，通知发送失败，请检查通知权限", date); return@withLock false }
            }
            error(if (prepared) null else "已用现有内容生成；还有录音没整理完", date)
            false
        } catch (e: CancellationException) { throw e }
        catch (e: LlmException) { error(e.type.human + if (e.type.retryable) "，稍后再试" else "", date); e.type.retryable }
        catch (e: Exception) { error("生成失败：${e.javaClass.simpleName}", date); false }
        finally {
            running = false
            val active = generation?.isActive() == true
            mutable.update { it.copy(generating = active, stage = if (active) "排队中…" else null) }
        }
    }

    private fun error(message: String?, date: LocalDate) {
        val errorDate = date.takeIf { message != null }
        prefs.edit().putString("lastError", message).putString("lastErrorDate", errorDate?.toString()).apply()
        mutable.update { it.copy(lastError = message, lastErrorDate = errorDate) }
    }
    private fun ReviewEntity.toModel() = app.resound.envsound.EnvSoundPresentation.decorate(DailyReview(LocalDate.parse(startDate), brief, ReviewPrompt.decode(sectionsJson), generatedAt))
}
