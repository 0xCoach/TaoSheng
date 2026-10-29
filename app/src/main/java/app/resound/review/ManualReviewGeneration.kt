package app.resound.review

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

/** KEEP 去重，观察实际保留的任务；重启后从 WorkManager 恢复排队状态。 */
internal class ManualReviewGeneration(
    private val work: WorkManager,
    private val scope: CoroutineScope,
    private val changed: (Boolean) -> Unit,
    private val failed: (LocalDate, String) -> Unit,
) {
    private val active = mutableSetOf<LocalDate>()
    private val lock = Any()

    init {
        scope.launch {
            work.getWorkInfosByTagFlow(TAG).collect { infos ->
                infos.filter { !it.state.isFinished }.forEach { info ->
                    val date = info.tags.firstOrNull { it.startsWith(DATE_TAG) }
                        ?.removePrefix(DATE_TAG)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: return@forEach
                    synchronized(lock) {
                        if (active.add(date)) {
                            changed(true)
                            scope.launch { observe(date, info.id) }
                        }
                    }
                }
            }
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active.isNotEmpty() }

    fun generate(date: LocalDate) = synchronized(lock) {
        if (!active.add(date)) return@synchronized
        changed(true)
        scope.launch {
            try {
                val request = request(date)
                work.enqueueUniqueWork(name(date), ExistingWorkPolicy.KEEP, request).await()
                // 优先匹配本次 id；KEEP 保留旧任务时观察实际保留项，不依赖返回列表顺序。
                val infos = work.getWorkInfosForUniqueWorkFlow(name(date)).first { it.isNotEmpty() }
                val info = infos.firstOrNull { it.id == request.id }
                    ?: infos.firstOrNull { !it.state.isFinished } ?: infos.first()
                observe(date, info.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finish(date, "生成失败：${e.javaClass.simpleName}") }
        }
        Unit
    }

    private suspend fun observe(date: LocalDate, id: java.util.UUID) {
        try {
            val info = work.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
            finish(date, when (info.state) {
                WorkInfo.State.CANCELLED -> "生成失败：CancellationException"
                WorkInfo.State.FAILED -> "生成失败：IllegalStateException"
                else -> null // 正常结束的原因已经由 repository.run 保存。
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { finish(date, "生成失败：${e.javaClass.simpleName}") }
    }

    private fun finish(date: LocalDate, message: String?) = synchronized(lock) {
        if (message != null) failed(date, message)
        active.remove(date)
        changed(active.isNotEmpty())
    }

    companion object {
        const val TAG = "manual-review"
        const val DATE = "date"
        private const val DATE_TAG = "manual-review-date:"
        fun name(date: LocalDate) = "$TAG:$date"
        fun request(date: LocalDate) = OneTimeWorkRequestBuilder<ManualReviewWork>()
            .setInputData(workDataOf(DATE to date.toString()))
            .addTag(TAG).addTag("$DATE_TAG$date")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}

class ManualReviewWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = reviewForegroundInfo()

    override suspend fun doWork(): Result {
        val date = inputData.getString(ManualReviewGeneration.DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return Result.failure()
        withReviewForeground { (AppGraph.review as RoomReviewRepository).run(date, scheduled = false) }
        // 每次点击给出一次确定结果；模型可重试错误由界面提示稍后再试。
        // 系统取消不捕获，WorkManager 可在进程恢复后重新运行。
        return Result.success()
    }
}
