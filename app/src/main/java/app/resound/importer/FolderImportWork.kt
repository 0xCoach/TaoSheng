package app.resound.importer

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.capture.CaptureService
import java.util.concurrent.TimeUnit

class FolderImportWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (CaptureService.alive) return Result.success() // 运行中的录音服务用原有心跳扫描。
        return try {
            (AppGraph.folderImports as AndroidFolderImports).scan()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        internal const val PERIODIC = "folder-import-periodic"
        internal fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled || CaptureService.alive) { work.cancelUniqueWork(PERIODIC); return }
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FolderImportWork>(15, TimeUnit.MINUTES)
                    .setInitialDelay(15, TimeUnit.MINUTES).build())
        }
        internal fun notify(context: Context, id: String, name: String, count: Int) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("import", "导入录音", NotificationManager.IMPORTANCE_LOW))
            // 复用现有“回到今天”入口；该入口只请求切第 0 页，不打开寻物面板。
            val intent = Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("resound://folder-import/${Uri.encode(id)}")).putExtra("open", "things_pending")
            val pending = PendingIntent.getActivity(context, 117, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                manager.notify(id, 3117, NotificationCompat.Builder(context, "import")
                    .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("从${name}导入了 $count 个录音")
                    .setContentText("已加入转写整理队列").setContentIntent(pending).setAutoCancel(true).build())
            } catch (_: SecurityException) { /* 通知权限被撤回不影响已提交的导入。 */ }
        }
    }
}

/** 回前台也扫描一次；不修改 MainActivity，屏幕旋转不重复扫描。 */
internal class FolderImportForeground(private val folders: FolderImports) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var changingConfiguration = false
    override fun onActivityStarted(activity: Activity) { if (started++ == 0 && !changingConfiguration) folders.scanNow() }
    override fun onActivityStopped(activity: Activity) { started--; changingConfiguration = activity.isChangingConfigurations }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
