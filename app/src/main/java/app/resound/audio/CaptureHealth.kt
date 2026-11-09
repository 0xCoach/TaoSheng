package app.resound.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration

/**
 * 卡 112：采集健康。11-09 下午麦克风“假运行”两小时：服务在跑、读取一直有返回，但声音全是零，原来的超时只看“读没读到”。
 * 真实麦克风再安静也有底噪，连续数字全零只在被静音或设备/系统出错时出现。
 */
internal object CaptureHealth {
    /** 连续全零多久判故障。被系统静音（让麦）期间不计时 */
    const val ZERO_LIMIT_MS = 20_000L
    /** 读取多久没返回判卡死；让麦期间系统仍会送静音，给宽一点但不能无限等 */
    const val STALL_MS = 2_000L
    const val STALL_YIELDING_MS = 10_000L
    /** 连续几次因全零重开仍没有声音，就提醒用户并放慢重试 */
    const val ZERO_ALERT_AFTER = 3
    const val ZERO_BACKOFF_MS = 60_000L
    /** 抛出的说明文字，采集服务据此识别全零故障 */
    const val ZERO_PCM = "输入设备送来的全是静音"

    /** AOSP MediaRecorder.AudioSource.HOTWORD 是隐藏常量，公开 SDK 不导出 */
    const val HOTWORD = 1999

    /** 最近一次收到非零声音的时间（elapsedRealtime）；采集服务据此判断重开有没有用 */
    @Volatile var lastSoundAt = 0L

    class ZeroWatch(private val limitMs: Long = ZERO_LIMIT_MS) {
        private var since: Long? = null
        fun reset() { since = null }
        /** 返回 true：已连续全零超过上限 */
        fun accept(buffer: ShortArray, n: Int, now: Long): Boolean {
            for (i in 0 until minOf(n, buffer.size)) if (buffer[i].toInt() != 0) { since = null; lastSoundAt = now; return false }
            if (n <= 0) return false
            val start = since ?: now.also { since = it }
            return now - start >= limitMs
        }
    }

    /** 别的应用在录音：不是本应用的录音，也不是系统唤醒词（小爱同学等常驻监听，不能因它让麦） */
    fun others(configs: List<AudioRecordingConfiguration>): Boolean = configs.any {
        !OwnRecordingSessions.contains(it.clientAudioSessionId) && it.clientAudioSource != HOTWORD
    }

    fun othersRecording(manager: AudioManager): Boolean = runCatching { others(manager.activeRecordingConfigurations) }.getOrDefault(false)
}
