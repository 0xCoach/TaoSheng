package app.resound.devices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.devices.android.DeviceInputs
import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** User-started transfer owns a foreground worker; selected IDs survive process recreation. */
class DeviceSyncWork(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        if (!AppGraph.isDevicesReady) return if (runAttemptCount < 3) Result.retry() else Result.failure()
        val devices = AppGraph.devices as? DeviceInputs ?: return Result.failure()
        val address = inputData.getString("device") ?: return Result.failure()
        val selected = inputData.getStringArray("files")?.toSet()
        if (selected?.isEmpty() == true) return Result.failure()
        return try {
            FailureNotes.note("devices/DeviceSyncWork:Started")
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("device-transfer","设备录音补传",NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(applicationContext,"device-transfer")
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("正在补传设备录音")
                .setContentText("锁屏后继续；暂停会保留已确认的进度").setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause,"暂停",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
            setForeground(ForegroundInfo(17044,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val completed = devices.runManualSync(address,selected)
            if (completed) FailureNotes.note("devices/DeviceSyncWork:Completed")
            else FailureNotes.note("devices/DeviceSyncWork:Incomplete")
            if (completed) Result.success() else Result.failure()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            FailureNotes.record("devices/DeviceSyncWork:Failed",e)
            Result.failure()
        }
    }
}

internal object DeviceSyncScheduler {
    private fun name(address: String) = "device-transfer-" + MessageDigest.getInstance("SHA-256")
        .digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
    fun enqueue(context: Context, address: String, fileIds: Set<String>?) {
        require(fileIds == null || fileIds.isNotEmpty())
        require(fileIds == null || fileIds.sumOf { it.encodeToByteArray().size } <= 6000)
        val data = Data.Builder().putString("device",address).apply {
            if (fileIds != null) putStringArray("files",fileIds.toTypedArray())
        }.build()
        val request = OneTimeWorkRequestBuilder<DeviceSyncWork>().setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(address),ExistingWorkPolicy.KEEP,request)
    }
    fun cancel(context: Context, address: String) { WorkManager.getInstance(context).cancelUniqueWork(name(address)) }
}
