package app.resound.search

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.*
import app.resound.ResoundApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object EmbeddingScheduler {
    fun start(context: Context) {
        val work = WorkManager.getInstance(context)
        // CONNECTED 包含移动网络；本地向量计算没有网络约束。
        work.enqueueUniqueWork("search-model", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SearchModelWork>().setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        work.enqueueUniquePeriodicWork("search-embedding-catchup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EmbeddingWork>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("kickOnly" to true)).build())
        enqueue(context)
    }
    fun enqueue(context: Context, continuation: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("search-embeddings", if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EmbeddingWork>().build())
    }
    fun powerAllowed(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val charging = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
            setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return charging || (level >= 0 && scale > 0 && level.toDouble() / scale > 0.30)
    }
}

class SearchModelWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            SearchModels.download(applicationContext)
            EmbeddingScheduler.enqueue(applicationContext) // 首次就绪补算全部历史。
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class EmbeddingWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (inputData.getBoolean("kickOnly", false)) {
            EmbeddingScheduler.enqueue(applicationContext)
            return@withContext Result.success()
        }
        if (!SearchModels.ready(applicationContext)) return@withContext Result.success()
        if (!EmbeddingScheduler.powerAllowed(applicationContext)) return@withContext Result.retry()
        val store = EmbeddingStore((applicationContext as ResoundApp).database)
        try {
            LocalEmbedder(applicationContext).use { embedder ->
                // 一个 Work 只处理一批；有积压时串行追加下一批，避免成功批次触发指数退避。
                val batch = store.pending(embedder.model)
                for (document in batch) {
                    coroutineContext.ensureActive()
                    if (!EmbeddingScheduler.powerAllowed(applicationContext)) return@withContext Result.retry()
                    store.save(document, embedder.model, embedder.embed(document.text))
                }
                IdeaMerger((applicationContext as ResoundApp).database).merge(embedder.model)
                if (store.pending(embedder.model).isNotEmpty()) EmbeddingScheduler.enqueue(applicationContext, continuation = true)
                Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
