package app.resound.followup

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.auth.Capability
import java.util.concurrent.TimeUnit

/** 跟进扫描兜底：周期发现落后事项并限频提醒（前台 App 打开时也会跑一次）。 */
class FollowupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppGraph.isAssistantReady) return Result.success()
        // AUTH：跟进扫描读个人记忆，未授权不跑。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.ReadPersonalData)) return Result.success()
        val found = AppGraph.followupEngine.scan()
        return Result.success(workDataOf("found" to found))
    }

    companion object {
        private const val NAME = "followup-scan"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FollowupWork>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build())
        }
    }
}
