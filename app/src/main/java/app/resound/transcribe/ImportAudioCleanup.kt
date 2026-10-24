package app.resound.transcribe

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import app.resound.data.TranscribeDao
import java.io.File
import java.util.concurrent.TimeUnit

internal const val IMPORT_RETENTION = 3 * 24 * 60 * 60 * 1000L

/** 年代久远的导入录音也从本次生成临时片段的时间算 3 天，不从录制时间算。 */
internal suspend fun cleanupImportedAudio(dao: TranscribeDao, now: Long, waitForVoiceprint: Boolean = false, reading: Set<Long> = emptySet()) {
    for (segment in dao.importedAudio()) {
        if (segment.id in reading) continue
        val file = File(segment.path)
        if (!file.exists()) continue
        val expired = now - file.lastModified() >= IMPORT_RETENTION
        val completed = segment.status in setOf("Done", "Empty", "SkippedPlayback")
        val processed = segment.status != "Done" || !waitForVoiceprint || segment.voiceprintProcessedAt != null
        if (expired || (completed && processed)) {
            if (file.delete() && !completed) {
                // 不再对已过期音频进行自动重试；保留失败原因供界面展示。
                dao.update(segment.copy(status = "Failed", attempts = 5, nextRetryAt = null,
                    error = "导入音频已满 3 天并删除，请重新分享"))
            }
        }
    }
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
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("import-audio-cleanup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ImportAudioCleanupWork>(1, TimeUnit.HOURS).build())
        }
    }
}
