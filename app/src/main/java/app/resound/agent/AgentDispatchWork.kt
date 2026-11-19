package app.resound.agent

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.auth.Capability
import java.util.concurrent.TimeUnit

/** 后台兜底：周期把到期的 Agent 任务发出（应用内的 60 秒驱动在前台已覆盖）。 */
class AgentDispatchWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppGraph.isAgentReady) return Result.success()
        // AUTH：后台 Worker 同样过门禁——未授权不派发。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.AgentAccess)) return Result.success()
        val sent = AppGraph.agentBridge.dispatchDue()
        return Result.success(workDataOf("sent" to sent))
    }

    companion object {
        private const val NAME = "agent-dispatch"

        /** 15 分钟是 WorkManager 周期的下限；联网才跑。 */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AgentDispatchWork>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        }
    }
}
