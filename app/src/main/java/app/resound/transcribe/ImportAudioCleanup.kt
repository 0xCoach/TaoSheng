package app.resound.transcribe

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import app.resound.AppGraph
import app.resound.data.TranscribeDao
import app.resound.data.CaptureDatabase
import app.resound.scheduling.DemandWorkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

internal const val IMPORT_RETENTION = 3 * 24 * 60 * 60 * 1000L

/** 年代久远的导入录音也从本次生成临时片段的时间算 3 天，不从录制时间算。 */
internal suspend fun cleanupImportedAudio(dao: TranscribeDao, now: Long, reading: Set<Long> = emptySet()) {
    for (segment in dao.importedAudio()) {
        // 成功/环境音导入沿用原声保留策略；失败、已放弃和待处理导入最多暂存三天。
        if (segment.status in setOf("Done", "Empty", "SkippedPlayback", "SkippedEnv")) continue
        if (segment.id in reading) continue
        val file = File(segment.path)
        if (!file.exists()) continue
        if (now - file.lastModified() < IMPORT_RETENTION) continue
        if (file.delete()) {
            // 先收尾再刷新失败状态，避免老失败转成 Quiet 后覆盖原始原因。
            dao.update(segment.copy(status = "Abandoned", completedAt = segment.completedAt ?: now,
                attempts = 5, nextRetryAt = null,
                error = segment.error ?: "导入音频已满 3 天并删除，请重新分享"))
        }
    }
    dao.refreshFailures(now)
}

/** 清理不受转写密钥、WiFi 或云端可用性约束。 */
class ImportAudioCleanupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ResoundApp
        app.transcribeRunner.withIdleAudio {
            app.voiceprintRepository.cleanupAudio()
            // 兜底回收进程恰好在编码完成、入库前死亡留下的孤立文件。
            File(applicationContext.filesDir, "import-segments").listFiles()?.filter {
                System.currentTimeMillis() - it.lastModified() >= IMPORT_RETENTION
            }?.forEach { file ->
                if (!app.database.transcribe().hasImportPath(file.path, file.path)) file.delete()
            }
        }
        reconcile(applicationContext, app.database, AppGraph.originalAudio as LocalOriginalAudio)
        return Result.success()
    }
    companion object {
        private val scheduling = Mutex()
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun observe(context: Context, db: CaptureDatabase, original: LocalOriginalAudio, scope: CoroutineScope) = scope.launch {
            merge(db.invalidationTracker.createFlow("segments", "memory_events").map { Unit },
                original.retentionDays.map { Unit }, original.keepStarred.map { Unit })
                .collect { reconcile(context, db, original) }
        }
        internal suspend fun reconcile(context: Context, db: CaptureDatabase, original: LocalOriginalAudio) = scheduling.withLock {
            val imports = db.transcribe().importedAudio().any {
                DemandWorkPolicy.importNeedsCleanup(it.status) && File(it.path).isFile
            }
            val orphans = File(context.filesDir, "import-segments").listFiles().orEmpty().any {
                it.isFile && !db.transcribe().hasImportPath(it.path, it.path)
            }
            schedule(context, imports || original.hasExpiringAudio(), orphans)
        }
        internal fun schedule(context: Context, hasExpiringAudio: Boolean, hasOrphans: Boolean) {
            val work = WorkManager.getInstance(context)
            if (DemandWorkPolicy.audioCleanup(hasExpiringAudio, hasOrphans))
                work.enqueueUniquePeriodicWork("import-audio-cleanup", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ImportAudioCleanupWork>(1, TimeUnit.HOURS)
                        .setInitialDelay(1, TimeUnit.HOURS).build())
            else work.cancelUniqueWork("import-audio-cleanup")
        }
    }
}
