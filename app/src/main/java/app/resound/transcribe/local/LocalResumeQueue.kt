package app.resound.transcribe.local

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import app.resound.data.SegmentEntity
import app.resound.transcribe.AsrException
import app.resound.transcribe.PipelineTimes
import app.resound.transcribe.TranscribeSource
import kotlinx.coroutines.CancellationException
import java.io.File

/** 主进程队列的文件/电源适配；所有调用处于 TranscribeRunner 的 gate 内。 */
internal class LocalResumeQueue(private val context: Context) {
    private val files = LocalResumeFiles(File(context.noBackupFilesDir, "asr-progress")) {
        PipelineTimes.record(context, "192续转 原因=$it")
    }
    private fun key(id: Long) = "segment:$id"
    fun prepare(rows: List<SegmentEntity>, default: TranscribeSource?) {
        val local = rows.filter { (it.asrSource?.let(TranscribeSource::valueOf) ?: default) == TranscribeSource.Local &&
            it.status in setOf("Pending", "Running", "Failed") && File(it.path).isFile }
        files.sweep(local.map { key(it.id) }.toSet(), System.currentTimeMillis())
        for (row in local) {
            val state = files.state(key(row.id))
            if (state.active) {
                files.state(key(row.id), state.interrupted())
                note(row.id, "local-process-death")
            }
        }
    }
    fun readyToResume(): Boolean = files.hasDeferred() &&
        (context.getSystemService(PowerManager::class.java).isInteractive || context.getSystemService(BatteryManager::class.java).isCharging)
    fun deferred(id: Long): Boolean {
        val deferred = files.state(key(id)).deferred(context.getSystemService(PowerManager::class.java).isInteractive,
            context.getSystemService(BatteryManager::class.java).isCharging)
        if (deferred) note(id, "deferred-screen-or-charge")
        return deferred
    }
    fun begin(id: Long) { files.state(key(id), files.state(key(id)).copy(active = true)) }
    fun success(id: Long) { files.clear(key(id)) }
    /** 返回 true 才走续转等待，普通错误保持原来的失败重试语义。 */
    fun failed(id: Long, error: Exception): Boolean {
        val reason = (error as? AsrException)?.detail?.takeIf { it in LOCAL_INTERRUPTION_REASONS }
            ?: if (error is CancellationException) "local-cancelled" else null
        if (reason == null) { files.state(key(id), LocalInterruptionState()); return false }
        val state = files.state(key(id)).interrupted()
        files.state(key(id), state)
        note(id, reason)
        return files.load(key(id)) != null
    }
    private fun note(id: Long, reason: String) {
        val progress = files.load(key(id))
        PipelineTimes.record(context, "192续转 id=$id 原因=$reason 总块=${progress?.chunks?.size ?: 0} 已完成=${progress?.decoded?.size ?: 0} 连续打断=${files.state(key(id)).count}")
    }
}
