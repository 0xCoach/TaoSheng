/*
 * [INPUT]: 用户主动连接意图与 SonicloudRecorder 状态。
 * [OUTPUT]: connectedDevice 前台连接生命周期和断开操作。
 * [POS]: 保障锁屏后的蓝牙连接，独立于手机麦克风权限。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "录音卡连接", NotificationManager.IMPORTANCE_LOW))
        scope.launch { AppGraph.recorder.status.collectLatest { state ->
            manager.notify(ID, notification(state))
        } }
    }
    private fun notification(state: RecorderStatus): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = state.downloading?.let { "正在下载 $it · ${state.percent}%" }
            ?: if (state.connected) "录音卡已连接" else state.error ?: "正在连接录音卡"
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(state.device?.name ?: "录音卡").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { AppGraph.recorder.disconnect(); stopSelf(); return START_NOT_STICKY }
        try {
            startForeground(ID, notification(AppGraph.recorder.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) { AppGraph.recorder.serviceStopped(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        AppGraph.recorder.serviceStopped()
        getSystemService(NotificationManager::class.java).cancel(ID)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "sonicloud-connection"
        private const val ID = 7301
        private const val STOP = "app.resound.sonicloud.DISCONNECT"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, RecorderService::class.java)) }
    }
}
