@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.review

import app.resound.data.*
import app.resound.digest.LlmPreset
import app.resound.digest.snapshot
import app.resound.digest.core.ReviewClock
import app.resound.digest.core.ReviewPrompt as CorePrompt
import app.resound.platform.ZoneProvider
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlin.time.Instant

/** 查询、生成与复用规则；系统任务、凭据、模型、通知和偏好由装配层提供。 */
class ReviewStoreRepository(
    private val dao: ReviewDao, private val memory: MemoryDao,
    initial: ReviewStoredSettings,
    private val credentials: () -> Pair<LlmPreset, String>,
    private val chat: suspend (LlmPreset, String, String, String) -> ReviewReply,
    private val template: () -> String, private val appName: String,
    private val notify: suspend (String, String, String, ReviewEntity) -> Unit,
    private val reschedule: (Int) -> Unit,
    private val now: () -> Long, private val zone: ZoneProvider, private val io: CoroutineDispatcher,
    private val dailyMemory: suspend (LocalDate) -> Unit,
    private val beforeReview: suspend () -> Boolean,
    private val hasBackup: (LlmPreset) -> Boolean,
    private val allowsEmptyKey: (LlmPreset) -> Boolean,
    private val inputChars: (LlmPreset) -> Int,
    private val failure: (Exception) -> ReviewFailure?,
    private val generate: ((LocalDate) -> Unit)?, private val generationActive: () -> Boolean,
    private val presentationChanges: () -> Flow<Unit>, private val decorate: suspend (DailyReview) -> DailyReview,
    private val saveDailyAt: (Int) -> Unit, private val savePush: (Boolean) -> Unit,
    private val saveError: (String?, String?) -> Unit,
) : ReviewRepository {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(ReviewState(false, initial.lastError?.let { if (it.startsWith("生成失败：")) REVIEW_FAILURE else it },
        initial.dailyAtMinutes.coerceIn(0, 1439), initial.pushEnabled,
        lastErrorDate = initial.lastErrorDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }))
    override val state = mutable.asStateFlow()
    override fun daily(date: LocalDate): Flow<DailyReview?> = combine(dao.daily(date.toString()), presentationChanges()) { row, _ -> row?.toModel() }.flowOn(io)
    override fun recent(): Flow<List<DailyReview>> = combine(dao.recent(), presentationChanges()) { rows, _ -> rows.map { it.toModel() } }.flowOn(io)
    private val running = MutableStateFlow(false)
    fun generationChanged(active: Boolean) {
        mutable.update { it.copy(generating = running.value || active,
            stage = if (running.value) it.stage else if (active) "排队中…" else null) }
    }
    fun generationFailed(date: LocalDate, message: String) = error(message, date)
    override fun generateNow(date: LocalDate) {
        if (generate == null) {
            error(REVIEW_FAILURE, date)
            return
        }
        generate.invoke(date)
    }

    override fun hasMaterial(date: LocalDate): Flow<Boolean> =
        combine(state.map { it.dailyAtMinutes }.distinctUntilChanged(),
            dao.materialChanges(androidx.room.RoomRawQuery("SELECT 1"))) { minutes, _ ->
            material(date, minutes, TimeZone.of(zone.zoneId())).second != null
        }.distinctUntilChanged().flowOn(io)

    override fun windowText(date: LocalDate): String {
        val minutes = state.value.dailyAtMinutes
        fun label(day: LocalDate) = "${day.monthNumber}月${day.dayOfMonth}日 ${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
        return "${label(date.minus(1, DateTimeUnit.DAY))} – ${label(date)}"
    }

    /** 空值即无材料；窗口和三类材料的规则供观察与生成共用。 */
    private suspend fun material(date: LocalDate, minutes: Int, timezone: TimeZone): Pair<Long, ReviewInput?> {
        val start = ReviewClock.cutoff(date.minus(1, DateTimeUnit.DAY), minutes, timezone).toEpochMilliseconds()
        val end = ReviewClock.cutoff(date, minutes, timezone).toEpochMilliseconds()
        val source = dao.reviewInput(start, end)
        return end to source.takeIf { it.events.isNotEmpty() || it.items.isNotEmpty() || it.starredStale.isNotEmpty() }
    }
    override fun setDailyAt(minutes: Int) {
        val value = minutes.coerceIn(0, 1439)
        saveDailyAt(value)
        mutable.update { it.copy(dailyAtMinutes = value) }
        reschedule(value)
    }
    override fun setPushEnabled(enabled: Boolean) {
        savePush(enabled)
        mutable.update { it.copy(pushEnabled = enabled) }
    }

    /** 手动强制重生成且不推送；定时只复用截止时刻后生成的回顾，避免重试重复扣用量。返回是否需要重试。 */
    suspend fun run(date: LocalDate, scheduled: Boolean): Boolean = gate.withLock {
        running.value = true
        mutable.update { it.copy(generating = true, stage = "正在转写和整理还没处理的录音…") }
        try {
            val prepared = try {
                withTimeoutOrNull(10 * 60_000L) { beforeReview() } ?: false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            mutable.update { it.copy(stage = "正在写回顾…") }
            val timezone = TimeZone.of(zone.zoneId())
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
                    CorePrompt.staleOnly(source.snapshot(), timezone)
                } else {
                    val (preset, key) = credentials()
                    if (key.isBlank() && !allowsEmptyKey(preset) && !hasBackup(preset)) { error("还没填写整理模型的密钥", date); return@withLock false }
                    val system = CorePrompt.system(template(), appName)
                    val rawInput = CorePrompt.input(date, source.snapshot(), timezone, minutes)
                    val input = try { ReviewInputBudgetRules.fit(rawInput, system, inputChars(preset)) }
                    catch (e: app.resound.digest.LlmInputLimitException) { if (hasBackup(preset)) rawInput else throw e }
                    val reply = chat(preset, key, system, input)
                    // 格式错误的成功调用也实际消耗了 token。
                    memory.addUsage(Instant.fromEpochMilliseconds(now()).toLocalDateTime(timezone).date.toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Review)
                    model = reply.actualModel ?: preset.model
                    try { CorePrompt.parse(reply.content, source.snapshot(), timezone) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        error("模型返回的格式不对，换个模型或稍后再试", date)
                        return@withLock false
                    }
                }
                val (brief, sections) = result
                row = ReviewEntity(startDate = date.toString(), endDate = date.toString(), brief = brief,
                    sectionsJson = CorePrompt.encode(sections), generatedAt = now(), model = model)
                dao.save(row)
            }
            if (scheduled) dailyMemory(date)
            if (scheduled && state.value.pushEnabled) {
                try {
                    val deepLink = "resound://review?open=review&date=$date"
                    notify("今日回顾", row.brief, deepLink, row)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error("回顾已生成，通知发送失败，请检查通知权限", date); return@withLock false }
            }
            error(if (prepared) null else "已用现有内容生成；还有录音没整理完", date)
            false
        } catch (e: CancellationException) { throw e }
        catch (e: app.resound.digest.LlmInputLimitException) { error(e.message, date); false }
        catch (e: Exception) {
            val detail = failure(e)
            error(detail?.message ?: REVIEW_FAILURE, date)
            detail?.retryable ?: false
        }
        finally {
            running.value = false
            val active = generationActive()
            mutable.update { it.copy(generating = active, stage = if (active) "排队中…" else null) }
        }
    }

    private fun error(message: String?, date: LocalDate) {
        val errorDate = date.takeIf { message != null }
        saveError(message, errorDate?.toString())
        mutable.update { it.copy(lastError = message, lastErrorDate = errorDate) }
    }
    suspend fun review(row: ReviewEntity): DailyReview = row.toModel()
    private suspend fun ReviewEntity.toModel() = decorate(DailyReview(LocalDate.parse(startDate), brief, CorePrompt.decode(sectionsJson), generatedAt))
}

data class ReviewStoredSettings(val lastError: String?, val dailyAtMinutes: Int, val pushEnabled: Boolean, val lastErrorDate: String?)
data class ReviewReply(val content: String, val promptTokens: Long, val completionTokens: Long, val actualModel: String?)
data class ReviewFailure(val message: String, val retryable: Boolean)
