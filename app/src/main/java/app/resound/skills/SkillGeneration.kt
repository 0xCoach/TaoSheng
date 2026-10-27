package app.resound.skills

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.R
import app.resound.push.LocalNotifier
import app.resound.push.Notifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 页面只提交意图；等待、恢复及结果观察均使用 Application 的 scope。 */
class SkillGeneration(
    private val work: WorkManager,
    private val dao: SkillDao,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val active = MutableStateFlow(emptySet<Pair<String, LocalDate>>())
    private val results = MutableStateFlow(emptyMap<Pair<String, LocalDate>, SkillRunResult>())
    val generating = active.asStateFlow()
    val generated = results.asStateFlow()

    init {
        scope.launch {
            work.getWorkInfosByTagFlow(TAG).collect { infos ->
                synchronized(lock) {
                    for (info in infos.filter { !it.state.isFinished }) {
                        val key = key(info) ?: continue
                        if (key in active.value) continue
                        begin(key)
                        scope.launch { observe(key, info.id) }
                    }
                }
            }
        }
    }

    fun generate(id: String, date: LocalDate) = synchronized(lock) {
        val key = id to date
        if (key in active.value) return@synchronized
        // 必须在返回前发布，且在完成时先写结果再移除 active，避免界面读到空档。
        begin(key)
        scope.launch {
            try {
                val request = request(id, date)
                work.enqueueUniqueWork(name(id, date), ExistingWorkPolicy.KEEP, request).await()
                // KEEP 可能保留重启前已在运行的任务，观察实际入队的 id。
                val info = work.getWorkInfosForUniqueWorkFlow(name(id, date)).first { it.isNotEmpty() }
                    .first()
                observe(key, info.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { finish(key, SkillRunResult.Failed("后台任务未能启动，请重试")) }
        }
        Unit
    }

    private fun begin(key: Pair<String, LocalDate>) {
        active.value = active.value + key
        results.value = results.value - key
    }

    private suspend fun observe(key: Pair<String, LocalDate>, id: java.util.UUID) {
        val info = work.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
        val result = decode(info.outputData, key) ?: SkillRunResult.Failed(
            if (info.state == WorkInfo.State.CANCELLED) "后台生成已取消，请重试" else "后台生成失败，请重试")
        finish(key, result)
    }

    private fun finish(key: Pair<String, LocalDate>, result: SkillRunResult) = synchronized(lock) {
        results.value = results.value + (key to result)
        active.value = active.value - key
    }

    private suspend fun decode(data: Data, key: Pair<String, LocalDate>): SkillRunResult? = when (data.getString("result")) {
        "cards" -> {
            val ids = data.getLongArray("cards")?.toSet().orEmpty()
            SkillRunResult.Cards(dao.dayRows(key.first, key.second.toEpochDay()).filter { it.id in ids }.map { it.card() })
        }
        "nothing" -> SkillRunResult.Nothing(data.getString("reason").orEmpty())
        "failed" -> SkillRunResult.Failed(data.getString("reason").orEmpty())
        else -> null
    }

    companion object {
        const val TAG = "skill-generation"
        const val SKILL = "skill"
        const val DATE = "date"
        private const val KEY_TAG = "skill-generation-key:"
        fun name(id: String, date: LocalDate) = "$TAG:$id:$date"
        fun request(id: String, date: LocalDate): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SkillGenerationWork>()
            .setInputData(workDataOf(SKILL to id, DATE to date.toString()))
            .addTag(TAG).addTag("$KEY_TAG$id:$date")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        private fun key(info: WorkInfo): Pair<String, LocalDate>? {
            val tag = info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG) ?: return null
            return runCatching { tag.substringBeforeLast(':') to LocalDate.parse(tag.substringAfterLast(':')) }.getOrNull()
        }

        internal fun output(result: SkillRunResult): Data = when (result) {
            is SkillRunResult.Cards -> workDataOf("result" to "cards", "cards" to result.cards.map { it.id }.toLongArray())
            is SkillRunResult.Nothing -> workDataOf("result" to "nothing", "reason" to result.reason.take(1000))
            is SkillRunResult.Failed -> workDataOf("result" to "failed", "reason" to result.reason.take(1000))
        }
    }
}

internal data class SkillGenerationNotice(val title: String, val body: String, val link: String) {
    companion object {
        fun from(name: String, date: LocalDate, result: SkillRunResult): SkillGenerationNotice {
            val dayLink = "resound://day?open=day&date=$date"
            return when (result) {
                is SkillRunResult.Cards -> SkillGenerationNotice(
                    if (result.cards.size == 1) SkillCardPush.title(result.cards.single()) + " 已生成" else "${name}已生成",
                    if (result.cards.size == 1) SkillCardPush.body(result.cards.single()) else "出了 ${result.cards.size} 张：" + result.cards.joinToString("、") { it.title },
                    if (result.cards.size == 1) "resound://card?open=card&id=${result.cards.single().id}" else dayLink)
                is SkillRunResult.Nothing -> SkillGenerationNotice("${name}没有出卡", result.reason, dayLink)
                is SkillRunResult.Failed -> SkillGenerationNotice("${name}生成失败", result.reason, dayLink)
            }
        }
    }
    suspend fun send(notifier: Notifier) = notifier.send(title, body, link)
}

class SkillGenerationWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Android 10/11 的 expedited 兼容路径需要前台通知；Android 12+ 交给加急 Job。
        LocalNotifier(applicationContext, LocalNotifier.SKILL_GENERATION_CHANNEL)
        return ForegroundInfo(200 + (id.hashCode() and 0xffff),
            NotificationCompat.Builder(applicationContext, LocalNotifier.SKILL_GENERATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle("技能生成中")
                .setContentText("完成后会通知你").setOngoing(true).build())
    }

    override suspend fun doWork(): Result {
        val skillId = inputData.getString(SkillGeneration.SKILL) ?: return Result.failure()
        val date = runCatching { LocalDate.parse(inputData.getString(SkillGeneration.DATE)) }.getOrNull() ?: return Result.failure()
        val repository = AppGraph.skills as LocalSkillRepository
        // 不吞 CancellationException：系统停止后由 WorkManager 重新排队，不发布终态。
        val attempt = repository.runGeneration(skillId, date, canRetry = runAttemptCount < 2)
        if (attempt.retryable) return Result.retry()
        val result = attempt.result
        val notice = SkillGenerationNotice.from(repository.skills.value.find { it.id == skillId }?.name ?: "技能", date, result)
        try { notice.send(LocalNotifier(applicationContext, LocalNotifier.SKILL_GENERATION_CHANNEL)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 系统通知被关闭不影响已保存的卡和界面结果，也不重复调用模型。 */ }
        val output = SkillGeneration.output(result)
        return if (result is SkillRunResult.Failed) Result.failure(output) else Result.success(output)
    }
}
