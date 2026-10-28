package app.resound.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 强提醒的“闹钟”：用系统默认闹钟铃声循环响、同时震动，连续响 [RING_MS] 后先停（不响一整天）。
 * 走闹钟音量，勿扰模式下照样响（和系统闹钟一样）。响着时按电源键灭屏就停（等于“不再响”）。
 * 同一时间只响一条；新的一条到点会接替上一条。
 */
internal object AlarmPlayer {
    const val RING_MS = 60_000L

    /** 正在响的那条待办；整屏提醒页据此决定显示什么、何时关掉 */
    private val _ringing = MutableStateFlow<Long?>(null)
    val ringing: StateFlow<Long?> = _ringing.asStateFlow()

    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var screenOff: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { silence() }

    @Synchronized
    fun start(context: Context, id: Long, onPower: (Long) -> Unit) {
        val app = context.applicationContext
        silence()
        _ringing.value = id
        wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resound:strong-reminder").apply { acquire(RING_MS + 5_000) }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        // 每一步各自兜住：铃声、震动、电源键哪一样出错都不能让整个涛声崩掉（录音也在这个进程里）
        runCatching {
            vibrator(app)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        // 按电源键灭屏 = 不再响
        screenOff = runCatching {
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) { _ringing.value?.let(onPower) }
            }.also { app.registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        }.getOrNull()
        handler.postDelayed(timeout, RING_MS)
        appContext = app
    }

    private var appContext: Context? = null

    /** 停止响铃和震动；通知和下一次再响的安排由 [StrongReminders] 管 */
    @Synchronized
    fun silence(id: Long? = null) {
        if (id != null && _ringing.value != id) return
        handler.removeCallbacks(timeout)
        player?.runCatching { stop(); release() }
        player = null
        appContext?.let { runCatching { vibrator(it)?.cancel() } }
        screenOff?.let { r -> appContext?.runCatching { unregisterReceiver(r) } }
        screenOff = null
        wake?.runCatching { if (isHeld) release() }
        wake = null
        _ringing.value = null
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
}
