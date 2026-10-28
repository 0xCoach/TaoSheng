package app.resound.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.capture.VoiceInputService
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UpdateWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (AppGraph.updater as UpdateEngine).checkNow()
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("app-update-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWork>(6, TimeUnit.HOURS).setInitialDelay(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

class VoiceInputAfterUpdateWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (applicationContext.getSharedPreferences("voice-input", Context.MODE_PRIVATE).getBoolean("enabled", false) &&
            !VoiceInputService.connected.value) UpdateNotifications.voice(applicationContext)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("voice-input-after-update", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VoiceInputAfterUpdateWork>().setInitialDelay(10, TimeUnit.SECONDS).build())
        }
    }
}

/** 独立监听，不改变 CaptureBootReceiver 的录音恢复行为。 */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) VoiceInputAfterUpdateWork.schedule(context)
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (session < 0 || AndroidUpdateStore(context).long("session") != session.toLong()) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 若系统限制后台弹窗，通知仍能让用户进入系统确认页。
                UpdateNotifications.confirmation(context, confirm)
                runCatching { context.startActivity(confirm) }
                UpdateDiagnostics.record(context, "install session=$session result=pending_user_action")
                return
            }
        }
        val pending = goAsync()
        (context.applicationContext as ResoundApp).scope.launch {
            try {
                UpdateNotifications.dismissConfirmation(context)
                (AppGraph.updater as UpdateEngine).installResult(session,
                    success = status == PackageInstaller.STATUS_SUCCESS,
                    cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED,
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), diagnosticStatus = status)
            } finally { pending.finish() }
        }
    }
    companion object { internal const val ACTION = "app.resound.update.INSTALL_RESULT" }
}
