package app.resound.capture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import app.resound.AppGraph
import app.resound.diagnostics.FailureNotes

/**
 * 敲敲手势总控：开「敲敲控制」且（服务活着 || App 在前台）时注册加速度计监听；
 * 后台且无录音服务时释放传感器——息屏+Doze 本来就收不到事件，空转只费电。
 * 命中时按 KnockActionRules 判定：隐私暂停是硬锁（敲敲一律无效），
 * 录音中→暂停，手动暂停→恢复，未录→启动。
 * START/RESUME 走服务授权闸；每次命中给震动反馈（启动/恢复=1 短振，暂停=2 短振，被拦=长振）。
 */
class KnockGestureController(
    private val context: Context,
    private val store: CaptureStore,
) {
    private val sensor = KnockSensor(context) { onKnock() }
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    @Volatile private var foregroundActivities = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // "enabled"/"manualPausedUntil" 由录音服务改写：服务启停→重新评估要不要监听。
        if (key in setOf(KEY_ENABLED, KEY_TAPS, KEY_SENSITIVITY, "enabled", AndroidScene.KEY_PRIVACY)) sync()
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) { foregroundActivities++; if (foregroundActivities == 1) sync() }
        override fun onActivityStopped(a: Activity) { foregroundActivities--; if (foregroundActivities == 0) sync() }
        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    fun start() {
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        sync()
    }

    fun stop() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        sensor.stop()
    }

    /** 只在"敲敲开着 && 能收到事件"时挂传感器：App 前台，或服务活着且录音开关还开着。 */
    private fun sync() {
        val p = store.prefs
        val canReceive = foregroundActivities > 0 ||
            (CaptureService.alive && p.getBoolean("enabled", false))
        if (p.getBoolean(KEY_ENABLED, false) && canReceive)
            sensor.start(p.getInt(KEY_TAPS, 2), p.getInt(KEY_SENSITIVITY, 1))
        else sensor.stop()
    }

    private fun buzz(pattern: LongArray) {
        runCatching {
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else vibrator?.vibrate(pattern.sum())
        }
    }

    private fun onKnock() {
        val p = store.prefs
        val action = KnockActionRules.decide(
            serviceAlive = CaptureService.alive,
            enabled = p.getBoolean("enabled", false),
            manualPaused = p.getLong("manualPausedUntil", 0) > System.currentTimeMillis(),
            privacyPaused = p.getBoolean(AndroidScene.KEY_PRIVACY, false),
            topicPaused = AppGraph.isTopicsReady && AppGraph.topics.current.value?.status == app.resound.topic.TopicStatus.Paused,
        )
        // 启动/恢复也要过录音授权闸：预查一次，会被拒就给"拒绝"振，不派发注定失败的启动
        if (action == KnockActionRules.Action.Start || action == KnockActionRules.Action.Resume) {
            if (AppGraph.isAuthReady && !AppGraph.auth.allowed(app.resound.auth.Capability.RecordAudio)) {
                buzz(longArrayOf(0, 80, 80, 80, 80, 500)); return
            }
        }
        when (action) {
            KnockActionRules.Action.Blocked -> { buzz(longArrayOf(0, 400)); return } // 隐私暂停：敲敲不解锁
            KnockActionRules.Action.Start -> buzz(longArrayOf(0, 300))                // 单长振=开始干活
            KnockActionRules.Action.Resume -> buzz(longArrayOf(0, 120, 140, 120))     // 双短振=回来了
            KnockActionRules.Action.Pause -> buzz(longArrayOf(0, 250, 160, 250))      // 双长振=停下了
        }
        val serviceAction = when (action) {
            KnockActionRules.Action.Start -> CaptureService.START
            KnockActionRules.Action.Resume -> CaptureService.RESUME
            KnockActionRules.Action.Pause -> CaptureService.PAUSE
            KnockActionRules.Action.Blocked -> return
        }
        val intent = Intent(context, CaptureService::class.java)
            .setAction(serviceAction).putExtra("minutes", MAX_PAUSE_MINUTES).putExtra("route", "knock")
        // 后台启动前台服务受系统限制：录音/暂停中（FGS 存活）总成功；全停+后台可能被拒，如实记录不冒充。
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { FailureNotes.record("capture/KnockGesture.kt:knock-$serviceAction", it) }
    }

    companion object {
        const val KEY_ENABLED = "knockEnabled"
        const val KEY_TAPS = "knockTaps"
        const val KEY_SENSITIVITY = "knockSensitivity"
        /** PAUSE 的上限是 10080 分钟（7 天）——敲停语义就是"停到我再敲"。 */
        private const val MAX_PAUSE_MINUTES = 10080
    }
}
