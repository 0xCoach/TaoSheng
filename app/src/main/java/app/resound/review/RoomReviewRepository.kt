package app.resound.review

import app.resound.usage.UsagePurpose

import android.content.SharedPreferences
import app.resound.data.MemoryDao
import app.resound.data.ReviewDao
import app.resound.data.ReviewEntity
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
) : ReviewRepository {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(ReviewState(false, prefs.getString("lastError", null),
        prefs.getInt("dailyAtMinutes", 22 * 60).coerceIn(0, 1439), prefs.getBoolean("pushEnabled", true)))
    override val state = mutable.asStateFlow()
    override fun daily(date: LocalDate): Flow<DailyReview?> = dao.daily(date.toString()).map { it?.toModel() }.flowOn(Dispatchers.IO)
    override fun recent(): Flow<List<DailyReview>> = dao.recent().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)
    override fun generateNow(date: LocalDate) { scope.launch { run(date, scheduled = false) } }
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
        mutable.update { it.copy(generating = true) }
        try {
            val timezone = zone()
            val minutes = state.value.dailyAtMinutes
            val start = ReviewTime.cutoff(date.minusDays(1), minutes, timezone).toInstant().toEpochMilli()
            val end = ReviewTime.cutoff(date, minutes, timezone).toInstant().toEpochMilli()
            val source = dao.reviewInput(start, end)
            // 没有当天事件且没有历史重点需要跟进时，不推送旧回顾。
            if (source.events.isEmpty() && source.starredStale.isEmpty()) { error(null); return@withLock false }
            var row = if (scheduled) dao.daily(date.toString()).first()?.takeIf { it.generatedAt >= end } else null
            if (row == null) {
                val model: String
                val result = if (source.events.isEmpty()) {
                    model = "local-starred-followup"
                    ReviewPrompt.staleOnly(source, timezone)
                } else {
                    val (preset, key) = credentials()
                    if (key.isBlank()) { error("还没填写大模型密钥"); return@withLock false }
                    val reply = chat(preset, key, ReviewPrompt.system(template(), appName), ReviewPrompt.input(date, source, timezone, minutes))
                    // 格式错误的成功调用也实际消耗了 token。
                    memory.addUsage(java.time.Instant.ofEpochMilli(now()).atZone(timezone).toLocalDate().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Review)
                    model = preset.model
                    ReviewPrompt.parse(reply.content, source, timezone)
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
                catch (_: Exception) { error("回顾已生成，通知发送失败，请检查通知权限"); return@withLock false }
            }
            error(null)
            false
        } catch (e: CancellationException) { throw e }
        catch (e: LlmException) { error(e.type.human); e.type.retryable }
        catch (_: Exception) { error("每日回顾生成失败，请重试"); false }
        finally { mutable.update { it.copy(generating = false) } }
    }

    private fun error(message: String?) {
        prefs.edit().putString("lastError", message).apply()
        mutable.update { it.copy(lastError = message) }
    }
    private fun ReviewEntity.toModel() = DailyReview(LocalDate.parse(startDate), brief, ReviewPrompt.decode(sectionsJson), generatedAt)
}
