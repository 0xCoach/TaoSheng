package app.resound.voiceprint

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** 独立本地任务，不串起云端转写/整理。重复点击排队，不吞掉用户后来的天数。 */
class VoiceprintRecomputeWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val days = inputData.getInt(DAYS, 7)
        if (days !in 1..30) return Result.failure()
        return try {
            val repository = (applicationContext as ResoundApp).voiceprintRepository
            if (repository.recomputeRecent(days)) {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            } else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }

    companion object {
        const val NAME = "voiceprint-recompute-v2"
        private const val DAYS = "days"
        fun enqueue(context: Context, days: Int = 7): java.util.UUID {
            require(days in 1..30)
            val request = OneTimeWorkRequestBuilder<VoiceprintRecomputeWork>()
                .setInputData(workDataOf(DAYS to days))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return request.id
        }
    }
}
