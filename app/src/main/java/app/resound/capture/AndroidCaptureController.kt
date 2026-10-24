package app.resound.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidCaptureController(private val context: Context, private val store: CaptureStore) : CaptureController {
    private val current = MutableStateFlow(CaptureStatus(CaptureState.Stopped, System.currentTimeMillis()))
    override val status = current.asStateFlow()
    internal fun update(state: CaptureState, pausedUntil: Long? = null, level: Float = 0f) {
        val old = current.value
        current.value = old.copy(state = state, since = if (old.state == state) old.since else System.currentTimeMillis(), pausedUntil = pausedUntil, level = level)
        if (CaptureTilePolicy.action(old.state) != CaptureTilePolicy.action(state) || old.pausedUntil != pausedUntil) {
            CaptureTileService.refresh(context)
        }
    }
    internal fun level(level: Float) { current.value = current.value.copy(level = level) }
    override fun start() = dispatch(CaptureService.START)
    override fun resume() = dispatch(CaptureService.RESUME)
    override fun pause(minutes: Int) { require(minutes > 0); dispatch(CaptureService.PAUSE, minutes) }
    override fun stop() {
        store.prefs.edit().putBoolean("enabled", false).putLong("pausedUntil", 0).commit()
        // 服务在运行时由它负责收尾，未运行时绝不为 stop 新建麦克风服务。
        if (CaptureService.alive) context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP))
        else update(CaptureState.Stopped)
    }
    private fun dispatch(action: String, minutes: Int = 60) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !CaptureNotifications.permitted(context)) {
            update(CaptureState.NoPermission); return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java).setAction(action)
                .putExtra("minutes", minutes).putExtra("route", "controller"))
        } catch (_: RuntimeException) {
            update(CaptureState.Stopped)
            CaptureNotifications.interrupted(context)
        }
    }
}
