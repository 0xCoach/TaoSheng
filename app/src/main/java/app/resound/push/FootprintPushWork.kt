package app.resound.push

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 每日足迹：到点推送，再排下一次。 */
class FootprintPushWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channels = AppGraph.push as? AndroidPushChannels ?: return Result.retry()
        FootprintPushScheduler.plan(applicationContext, channels.dispatchFootprint())
        return Result.success()
    }
}

internal object FootprintPushScheduler {
    private const val NAME = "push-footprint"
    fun plan(context: Context, at: Long?) {
        val wm = WorkManager.getInstance(context)
        if (at == null) { wm.cancelUniqueWork(NAME); return }
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FootprintPushWork>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build())
    }
}

/** 通道或订阅变化时重排足迹推送；足迹内容由足迹模块生成。 */
internal fun AndroidPushChannels.attachFootprint(context: Context, scope: CoroutineScope) {
    footprintMessage = { date ->
        if (!AppGraph.isLocationReady) null
        else app.resound.location.FootprintReports.build(AppGraph.location, date)?.let { r ->
            PushMessage(r.title, r.body, PushTopic.Footprint, "resound://footprint?date=${r.date}", System.currentTimeMillis(), footprint = r.json)
        }
    }
    scope.launch(Dispatchers.IO) {
        channels.map { list -> list.filter { it.enabled && it.subscription.footprint }.map { it.id to it.subscription.footprintAt } }
            .distinctUntilChanged().collect { FootprintPushScheduler.plan(context, nextFootprintAt()) }
    }
}
