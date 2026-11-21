package app.resound.rules

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.auth.Capability
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 定时规则驱动：每小时检查一次有没有到点的 Time 规则（分以内的精度足够"每天早上提醒"）。 */
class WorkflowWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppGraph.isAssistantReady) return Result.success()
        // AUTH：规则驱动可能发通知/建待办/派任务，未授权不跑。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.ReadPersonalData)) return Result.success()
        val cal = Calendar.getInstance()
        val dayKey = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        AppGraph.workflowEngine.tickTime(dayKey, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
        return Result.success()
    }

    companion object {
        private const val NAME = "workflow-tick"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WorkflowWork>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build())
        }
    }
}
