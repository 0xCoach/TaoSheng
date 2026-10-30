package app.resound.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.resound.AppGraph
import app.resound.R
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 课堂模式的快捷入口（下拉磁贴、通知按钮）：关着时开 2 小时，开着时结束；总开关“区分说话人”关掉时不可用 */
internal object ClassModeToggle {
    const val DEFAULT_MINUTES = 120
    fun toggle() {
        if (!AppGraph.isAsrSpeedReady) return
        val speed = AppGraph.asrSpeed
        if (speed.classMode.value.enabled) speed.stopClassMode()
        else if (speed.separateSpeakers.value) speed.startClassMode(DEFAULT_MINUTES)
    }
    fun subtitle(): String {
        if (!AppGraph.isAsrSpeedReady || !AppGraph.asrSpeed.separateSpeakers.value) return "已关区分说话人"
        val mode = AppGraph.asrSpeed.classMode.value
        return when {
            !mode.enabled -> "点一下开 2 小时"
            mode.endsAtMillis == null -> "手动结束前一直开"
            else -> "到 " + Instant.ofEpochMilli(mode.endsAtMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))
        }
    }
}

class ClassModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ClassModeToggle.toggle() }
    companion object {
        fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 31,
            Intent(context, ClassModeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ClassModeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        observer?.cancel()
        render()
        if (!AppGraph.isAsrSpeedReady) return
        observer = scope.launch { AppGraph.asrSpeed.classMode.collect { render() } }
    }
    private fun render() {
        qsTile?.apply {
            label = "课堂模式"
            icon = Icon.createWithResource(this@ClassModeTileService, R.drawable.ic_notify)
            val usable = AppGraph.isAsrSpeedReady && AppGraph.asrSpeed.separateSpeakers.value
            state = when {
                !usable -> Tile.STATE_UNAVAILABLE
                AppGraph.asrSpeed.classMode.value.enabled -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            subtitle = ClassModeToggle.subtitle()
            contentDescription = "$label，$subtitle"
            updateTile()
        }
    }
    override fun onClick() { super.onClick(); ClassModeToggle.toggle(); render() }
    override fun onStopListening() { observer?.cancel(); observer = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, ClassModeTileService::class.java))
        }
    }
}
