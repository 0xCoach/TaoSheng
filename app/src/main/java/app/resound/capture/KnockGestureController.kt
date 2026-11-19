package app.resound.capture

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import app.resound.diagnostics.FailureNotes

/**
 * 敲敲手势总控：开「敲敲控制」后注册加速度计监听；
 * 命中时按当前状态发命令——录音中→暂停(7天≈直到再敲)，暂停中→恢复，未录→启动。
 * START/RESUME 走服务授权闸（未授权不启动/安全收尾）；PAUSE 是停动作永远放行。
 * 进程活着才监听得到；息屏+Doze 下送达可能延迟（尽力而为，不夸大可靠性）。
 */
class KnockGestureController(
    private val context: Context,
    private val store: CaptureStore,
) {
    private val sensor = KnockSensor(context) { onKnock() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(KEY_ENABLED, KEY_TAPS, KEY_SENSITIVITY)) sync()
    }

    fun start() {
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        sync()
    }

    fun stop() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensor.stop()
    }

    private fun sync() {
        val p = store.prefs
        if (p.getBoolean(KEY_ENABLED, false)) sensor.start(p.getInt(KEY_TAPS, 2), p.getInt(KEY_SENSITIVITY, 1))
        else sensor.stop()
    }

    private fun onKnock() {
        val p = store.prefs
        val paused = p.getLong("manualPausedUntil", 0) > System.currentTimeMillis()
        val action = when {
            !CaptureService.alive || !p.getBoolean("enabled", false) -> CaptureService.START
            paused -> CaptureService.RESUME
            else -> CaptureService.PAUSE
        }
        val intent = Intent(context, CaptureService::class.java)
            .setAction(action).putExtra("minutes", MAX_PAUSE_MINUTES).putExtra("route", "knock")
        // 后台启动前台服务受系统限制：录音/暂停中（FGS 存活）总成功；全停+后台可能被拒，如实记录不冒充。
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { FailureNotes.record("capture/KnockGesture.kt:knock-$action", it) }
    }

    companion object {
        const val KEY_ENABLED = "knockEnabled"
        const val KEY_TAPS = "knockTaps"
        const val KEY_SENSITIVITY = "knockSensitivity"
        /** PAUSE 的上限是 10080 分钟（7 天）——敲停语义就是"停到我再敲"。 */
        private const val MAX_PAUSE_MINUTES = 10080
    }
}
