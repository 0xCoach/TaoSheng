package app.resound.recordings

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import kotlinx.coroutines.CancellationException

class RecordingPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val packer = AppGraph.recordingPacker as AndroidRecordingPacker
        return try {
            setForeground(PackNotifications.foreground(applicationContext, id, packer.current.value?.second))
            val ok = packer.run(id) { setForeground(PackNotifications.foreground(applicationContext, id, packer.current.value?.second)) }
            PackNotifications.finished(applicationContext, id, packer.current.value?.takeIf { it.first == id }?.second)
            if (ok) Result.success() else Result.failure()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else {
                packer.fail(id, "无法启动后台打包，请打开应用后重试")
                PackNotifications.finished(applicationContext, id, packer.current.value?.second)
                Result.failure()
            }
        }
    }
}
class CancelRecordingPackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { intent.getStringExtra("id")?.let { AppGraph.recordingPacker.cancel(it) } }
}
internal object PackNotifications {
    private const val CHANNEL = "recording-pack"
    private fun manager(c: Context) = c.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "录音打包", NotificationManager.IMPORTANCE_LOW))
    }
    private fun number(id: String) = 0x40000000 or (id.hashCode() and 0x0fffffff)
    fun foreground(c: Context, id: String, state: RecordingPackState?): ForegroundInfo {
        manager(c)
        val percent = (((state as? RecordingPackState.Running)?.fraction ?: 0f) * 100).toInt()
        val cancel = PendingIntent.getBroadcast(c, number(id), Intent(c, CancelRecordingPackReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return ForegroundInfo(number(id), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("正在打包录音 $percent%").setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "取消", cancel).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    fun finished(c: Context, id: String, state: RecordingPackState?) {
        if (state !is RecordingPackState.Done && state !is RecordingPackState.Failed) return
        val open = PendingIntent.getActivity(c, number(id), Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager(c).notify(number(id) + 0x10000000, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (state is RecordingPackState.Done) "录音包已保存" else "录音打包失败")
            .setContentText(if (state is RecordingPackState.Done) state.name else (state as RecordingPackState.Failed).message)
            .setContentIntent(open).setAutoCancel(true).build()) }
    }
    fun clear(c: Context, id: String) { manager(c).cancel(number(id)); manager(c).cancel(number(id) + 0x10000000) }
}
