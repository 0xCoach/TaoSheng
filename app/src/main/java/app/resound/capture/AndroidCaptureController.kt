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
    private val currentLevel = MutableStateFlow(0f)
    override val level = currentLevel.asStateFlow()
    internal fun update(state: CaptureState, pausedUntil: Long? = null, busyDetail: String? = null, quietName: String? = null) {
        val old = current.value
        current.value = old.copy(state = state, since = if (old.state == state) old.since else System.currentTimeMillis(), pausedUntil = pausedUntil,
            busyDetail = busyDetail.takeIf { state == CaptureState.MicBusy }, quietName = quietName.takeIf { state == CaptureState.Paused })
        // 不在收音的状态下声波归零（原来每次状态更新都会把音量清零）
        if (state != CaptureState.Listening && state != CaptureState.Recording) currentLevel.value = 0f
        if (CaptureTilePolicy.action(old.state) != CaptureTilePolicy.action(state) || old.pausedUntil != pausedUntil) {
            CaptureTileService.refresh(context)
        }
    }
    /** 没有界面订阅时只是改一个值，不会叫醒任何人。 */
    internal fun level(level: Float) { currentLevel.value = level }
    override fun start() = dispatch(CaptureService.START)
    override fun resume() = dispatch(CaptureService.RESUME)
    override fun pause(minutes: Int) { require(minutes > 0); dispatch(CaptureService.PAUSE, minutes) }
    override fun stop() {
        store.prefs.edit().putBoolean("enabled", false).putLong("pausedUntil", 0).putLong("manualPausedUntil", 0).remove("quietName").commit()
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
