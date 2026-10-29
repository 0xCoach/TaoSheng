package app.resound.importer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.audio.SileroVad
import app.resound.capture.SpeechSegmenter
import kotlinx.coroutines.*
import java.io.File

class ImportWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun foreground(entry: ImportEntry): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("import", "导入录音", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getBroadcast(applicationContext, 0,
            Intent(applicationContext, CancelImportReceiver::class.java).setAction(entry.pending.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "import")
            .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("正在导入 ${entry.pending.info.displayName}")
            .setOngoing(true).addAction(0, "取消", cancel).build()
        return ForegroundInfo(3028, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val importer = AppGraph.importer as AndroidAudioImporter
        val app = applicationContext as ResoundApp
        try {
            while (true) {
                val entry = importer.next() ?: break
                val id = entry.pending.id
                val temporary = File(applicationContext.cacheDir, "import-pcm/$id")
                var segments = 0
                var error: String? = null
                try {
                    if (importer.isCancelled(id)) throw ImportFailure("已取消导入")
                    setForeground(foreground(entry))
                    importer.update(id, 0, 0)
                    if (!importer.file(id).isFile) throw ImportFailure("临时录音已被系统清理，请重新分享")
                    // 进程中断可能留下半段，重放原缓存时从头生成。
                    temporary.deleteRecursively()
                    ImportSegments(temporary, id, requireNotNull(entry.startAt)).use { sink ->
                        val segmenter = SpeechSegmenter(sink)
                        var samples = 0L
                        SileroVad(applicationContext).use { vad ->
                            ImportDecoder().decode(importer.file(id)) { pcm ->
                                currentCoroutineContext().ensureActive()
                                if (importer.isCancelled(id)) throw ImportFailure("已取消导入")
                                if (pcm.isNotEmpty()) {
                                    vad.accept(pcm) { frame, speech, _ -> segmenter.accept(frame, speech) }
                                    samples += pcm.size
                                    segments += sink.save(app.database, File(applicationContext.filesDir, "import-segments"), entry.asr)
                                    importer.update(id, (samples * 100 / 16 / entry.pending.info.durationMillis).toInt().coerceIn(0, 99), segments)
                                }
                            }
                            vad.finish { frame, speech, _ -> segmenter.accept(frame, speech) }
                        }
                        segmenter.finish()
                        segments += sink.save(app.database, File(applicationContext.filesDir, "import-segments"), entry.asr)
                    }
                    importer.update(id, 100, segments)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = (e as? ImportFailure)?.message ?: "导入失败，请检查空间或换个音频格式重试" }
                finally { temporary.deleteRecursively() }
                importer.finish(id, error)
                app.runTranscriptionNow() // 部分成功后失败或取消的片段也进入正常队列。
            }
            Result.success()
        } catch (e: CancellationException) { importer.interrupted(); throw e }
        catch (_: Exception) { importer.interrupted(); Result.retry() }
    }
}

/** 只取消通知对应的文件，后面的导入继续；不取消整个 WorkManager 链。 */
class CancelImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = goAsync()
        (context.applicationContext as ResoundApp).scope.launch {
            try { (AppGraph.importer as AndroidAudioImporter).cancel(intent.action ?: return@launch) }
            finally { token.finish() }
        }
    }
}
