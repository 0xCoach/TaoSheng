package app.resound.capture

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.resound.audio.PlaybackConfigSnapshot
import app.resound.audio.PlaybackState
import app.resound.audio.playbackState
import app.resound.audio.speakerMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

object PlaybackPolicy {
    fun skip(durationMillis: Long, playbackMillis: Long, enabled: Boolean) =
        enabled && durationMillis > 0 && playbackMillis >= (durationMillis + 1) / 2
    /** 判定在共用层 speakerMedia；这里保留原签名，既有测试用真实安卓常量锁住同一条路径。 */
    fun speaker(usage: Int, routedType: Int?, outputs: List<Int>) = speakerMedia(usage, routedType, outputs)
}
/**
 * 公共回调列出的是“还没释放”的播放器，不只是正在出声的：很多应用暂停、切走后播放器仍挂着，
 * 只看列表会一直当成在外放。所以列表只用来判断“会不会走扬声器”，
 * 真正在不在出声另问系统 isMusicActive。本 App 当前没有音频播放器。
 *
 * 对外发布 [PlaybackState]：系统回调与设备变化立即重算；有活动播放配置时每 500 毫秒
 * 轮询 isMusicActive 并发布，停播后主动发布 false。重算由上游这一个协程串行完成；
 * 系统回调只在有订阅者时注册，最后一个订阅者离开即注销并停轮询，无订阅者时不占资源。
 */
class PlaybackMonitor(context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) { changes.trySend(Unit) }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { changes.trySend(Unit) }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { changes.trySend(Unit) }
    }
    val state: StateFlow<PlaybackState> = flow {
        manager.registerAudioPlaybackCallback(callback, handler)
        manager.registerAudioDeviceCallback(devices, handler)
        try {
            while (true) {
                emit(compute())
                // 有活动播放配置才按 ACTIVE_CHECK_MS 轮询；没有就挂起等下一次系统回调，不空转。
                if (manager.activePlaybackConfigurations.isEmpty()) changes.receive() else delay(ACTIVE_CHECK_MS)
            }
        } finally {
            manager.unregisterAudioPlaybackCallback(callback)
            manager.unregisterAudioDeviceCallback(devices)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(0), PlaybackState(false, false))

    @Suppress("DEPRECATION")
    private fun compute(): PlaybackState {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val configs = manager.activePlaybackConfigurations.map {
            PlaybackConfigSnapshot(it.audioAttributes.usage, if (Build.VERSION.SDK_INT >= 33) it.audioDeviceInfo?.type else null)
        }
        return playbackState(configs, outputs, manager.isMusicActive, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    private companion object { const val ACTIVE_CHECK_MS = 500L }
}
