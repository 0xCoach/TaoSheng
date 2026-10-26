package app.resound.capture

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PlaybackSkipSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}
class AndroidPlaybackSkipSettings(context: Context) : PlaybackSkipSettings {
    private val prefs = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    override val enabled = MutableStateFlow(prefs.getBoolean("skipPlayback", true))
    override fun setEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("skipPlayback", enabled).commit())
        this.enabled.value = enabled
    }
}
object PlaybackPolicy {
    fun skip(durationMillis: Long, playbackMillis: Long, enabled: Boolean) =
        enabled && durationMillis > 0 && playbackMillis >= (durationMillis + 1) / 2
    fun speaker(usage: Int, routedType: Int?, outputs: List<Int>): Boolean {
        if (usage !in setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) return false
        if (routedType != null) return routedType in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        // 普通 App 常拿不到播放路由；保守地把所有外接输出排除，避免误跳过耳机时的人声。
        val external = outputs.any { it !in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY) }
        return !external && outputs.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
    }
}
/**
 * 公共回调列出的是“还没释放”的播放器，不只是正在出声的：很多应用暂停、切走后播放器仍挂着，
 * 只看列表会一直当成在外放。所以列表只用来判断“会不会走扬声器”，
 * 真正在不在出声另问系统 isMusicActive（每 500 毫秒最多问一次）。本 App 当前没有音频播放器。
 */
class PlaybackMonitor(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    @Volatile private var speakerPlayer = false
    @Volatile private var musicActive = false
    @Volatile private var checkedAt = 0L
    @Volatile private var closed = false
    val speakerPlaying: Boolean get() {
        if (closed || !speakerPlayer) return false
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt >= ACTIVE_CHECK_MS) { musicActive = manager.isMusicActive; checkedAt = now }
        return musicActive
    }
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = update(configs)
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    init {
        manager.registerAudioPlaybackCallback(callback, handler)
        manager.registerAudioDeviceCallback(devices, handler)
        refresh()
    }
    private fun refresh() = update(manager.activePlaybackConfigurations)
    @Suppress("DEPRECATION")
    private fun update(configs: List<AudioPlaybackConfiguration>) {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        speakerPlayer = configs.any {
            val routed = if (Build.VERSION.SDK_INT >= 33) it.audioDeviceInfo?.type else null
            PlaybackPolicy.speaker(it.audioAttributes.usage, routed, outputs)
        }
    }
    override fun close() {
        manager.unregisterAudioPlaybackCallback(callback)
        manager.unregisterAudioDeviceCallback(devices)
        closed = true
    }
    private companion object { const val ACTIVE_CHECK_MS = 500L }
}
