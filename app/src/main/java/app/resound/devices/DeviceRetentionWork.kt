package app.resound.devices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.devices.android.DeviceInputs
import app.resound.update.UpdateDiagnostics
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class DeviceRetentionWork(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground()

    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("device-archives","设备原声归档与存储维护",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext,"device-archives")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("正在维护设备录音归档")
            .setContentText("独立备份会读回校验；仅清理已满足保留条件的设备原件").setOngoing(true).build()
        return ForegroundInfo(17043,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result {
        val available = inputData.getBoolean("backup_available",false)
        if (!AppGraph.isDevicesReady || !AppGraph.isBackupReady) return defer(available)
        val devices = AppGraph.devices as? DeviceInputs ?: return Result.success()
        // Expedited requests cannot express BatteryNotLow. Preserve it at execution time.
        if (!phoneBatteryNotLow(applicationContext.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
            return defer(available)
        return try {
            setForeground(foreground())
            UpdateDiagnostics.record(applicationContext,"device_maintenance start available=$available attempt=$runAttemptCount")
            android.util.Log.i("ResoundDeviceMaintenance","phase=Started available=$available attempt=$runAttemptCount")
            devices.maintainDeviceStorage()
            UpdateDiagnostics.record(applicationContext,"device_maintenance completed")
            android.util.Log.i("ResoundDeviceMaintenance","phase=Completed")
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            UpdateDiagnostics.record(applicationContext,"device_maintenance failed=${e.javaClass.simpleName} attempt=$runAttemptCount")
            android.util.Log.i("ResoundDeviceMaintenance","phase=Failed type=${e.javaClass.simpleName}")
            defer(available)
        }
    }
    private fun defer(available: Boolean): Result {
        if (available) {
            // Leave this opportunity terminal so the next real connection event is fresh.
            // Ordinary backoff remains durable; it never cancels an in-flight deletion.
            DeviceRetentionScheduler.runNow(applicationContext)
            return Result.failure()
        }
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

internal fun phoneBatteryNotLow(battery: Intent?): Boolean {
    battery ?: return false
    if (battery.hasExtra(BatteryManager.EXTRA_BATTERY_LOW))
        return !battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW,true)
    if (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) != 0) return true
    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
    return level >= 0 && scale > 0 && level.toDouble()/scale > .15
}

internal object DeviceRetentionScheduler {
    fun configure(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<DeviceRetentionWork>(24,TimeUnit.HOURS)
                .setInitialDelay(24,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
            work.enqueueUniquePeriodicWork("device-archive-maintenance",ExistingPeriodicWorkPolicy.KEEP,request)
        } else {
            work.cancelUniqueWork("device-archive-maintenance")
            work.cancelUniqueWork("device-archive-maintenance-now")
            work.cancelUniqueWork("device-archive-maintenance-available")
        }
    }
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DeviceRetentionWork>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork("device-archive-maintenance-now",ExistingWorkPolicy.KEEP,request)
    }
    fun backupAvailable(context: Context): Operation {
        val request = OneTimeWorkRequestBuilder<DeviceRetentionWork>()
            .setInputData(workDataOf("backup_available" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        UpdateDiagnostics.record(context,"device_maintenance queued backup_available=true")
        android.util.Log.i("ResoundDeviceMaintenance","phase=Queued backup_available=true")
        return WorkManager.getInstance(context).enqueueUniqueWork("device-archive-maintenance-available",ExistingWorkPolicy.KEEP,request)
    }
}
