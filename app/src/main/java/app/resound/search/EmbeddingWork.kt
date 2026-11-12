package app.resound.search

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.*
import app.resound.data.writeTransaction
import app.resound.ResoundApp
import app.resound.data.CaptureDatabase
import app.resound.scheduling.DemandWorkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

object EmbeddingScheduler {
    private val scheduling = Mutex()
    fun start(context: Context) {
        val app = context.applicationContext as ResoundApp
        observe(context, app.database, app.scope)
        // 等电量不靠广播：低电量时排的是带“充电中”约束的任务（见 waitForPower），电量只有充电才会回升。
    }
    internal fun observe(context: Context, db: CaptureDatabase, scope: CoroutineScope) = scope.launch {
        WorkManager.getInstance(context).cancelUniqueWork("search-embedding-catchup")
        // Room 事务提交触发，覆盖整理、手动编辑、导入、重做等全部写入点；首个 emission 补查历史。
        db.invalidationTracker.createFlow("memory_events", "memory_items").collect {
            reconcile(context, db)
        }
    }
    @Suppress("UNUSED_PARAMETER") // 保持现有调用兼容，所有变更都追加检查以免 KEEP 吞掉末尾写入。
    fun enqueue(context: Context, continuation: Boolean = false) {
        val app = context.applicationContext as ResoundApp
        app.scope.launch { catchUp(context) }
    }
    internal suspend fun catchUp(context: Context) {
        val db = (context.applicationContext as ResoundApp).database
        reconcile(context, db)
    }
    internal suspend fun reconcile(context: Context, db: CaptureDatabase, modelReady: Boolean? = null, powerAllowed: Boolean? = null) = scheduling.withLock {
        val work = WorkManager.getInstance(context)
        when (DemandWorkPolicy.embedding(EmbeddingStore(db).pending(SearchModels.modelId).isNotEmpty(),
                modelReady ?: SearchModels.ready(context), powerAllowed ?: powerAllowed(context))) {
            DemandWorkPolicy.Embedding.None -> work.cancelUniqueWork("search-embeddings-power").await()
            DemandWorkPolicy.Embedding.DownloadModel -> work.enqueueUniqueWork("search-model", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SearchModelWork>().setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build()).await()
            DemandWorkPolicy.Embedding.WaitForPower -> waitForPower(context)
            DemandWorkPolicy.Embedding.Compute -> {
                // KEEP 会丢掉执行末尾新写入的内容，追加一次检查消除这段竞态。
                work.enqueueUniqueWork("search-embeddings", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<EmbeddingWork>().build()).await()
                // 先持久化补算，再取消充电等待；从充电任务自身唤起时也不会漏排。
                work.cancelUniqueWork("search-embeddings-power").await()
            }
        }
    }
    internal suspend fun waitForPower(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("search-embeddings-power", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EmbeddingWork>().setInputData(workDataOf("kickOnly" to true))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build()).build()).await()
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
            EmbeddingScheduler.catchUp(applicationContext) // 首次就绪补算全部历史。
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class EmbeddingWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (inputData.getBoolean("kickOnly", false)) {
            EmbeddingScheduler.catchUp(applicationContext)
            return@withContext Result.success()
        }
        val db = (applicationContext as ResoundApp).database
        val store = EmbeddingStore(db)
        if (store.pending(SearchModels.modelId).isEmpty()) return@withContext Result.success()
        if (!SearchModels.ready(applicationContext)) {
            EmbeddingScheduler.catchUp(applicationContext)
            return@withContext Result.success()
        }
        if (!EmbeddingScheduler.powerAllowed(applicationContext)) {
            EmbeddingScheduler.waitForPower(applicationContext)
            return@withContext Result.success()
        }
        try {
            LocalEmbedder(applicationContext).use { embedder ->
                // 一个 Work 只处理一批；有积压时串行追加下一批，避免成功批次触发指数退避。
                val batch = store.pending(embedder.model)
                val computed = mutableListOf<Pair<EmbeddingStore.Document, FloatArray>>()
                var waitingForPower = false
                for (document in batch) {
                    coroutineContext.ensureActive()
                    if (!EmbeddingScheduler.powerAllowed(applicationContext)) {
                        waitingForPower = true
                        break
                    }
                    computed += document to embedder.embed(document.text)
                }
                // 向量与灵感合并一起提交；去掉周期兜底后不能留下“向量已齐、合并未做”的崩溃窗口。
                // 推理放在事务外，最多暂存 64 条向量；提交时仍由 save 校验内容快照。
                db.writeTransaction {
                    for ((document, vector) in computed) store.save(document, embedder.model, vector)
                    IdeaMerger(db).merge(embedder.model)
                }
                if (waitingForPower) EmbeddingScheduler.waitForPower(applicationContext)
                else if (store.pending(embedder.model).isNotEmpty()) EmbeddingScheduler.catchUp(applicationContext)
                Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
