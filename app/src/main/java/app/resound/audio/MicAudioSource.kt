package app.resound.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** 显式指定内置麦克风，避免插入 USB / 耳机后系统默认路由冒充手机录音。 */
class MicAudioSource(
    private val context: Context,
    private val onOpenChanged: (Boolean) -> Unit = {},
    private val readDurationMillis: () -> Int = { CaptureReadPolicy.INTERACTIVE_MS },
) : AudioSource, AudioTimeline {
    override val id = "mic"
    override val displayName = "手机麦克风"
    override var frameEpochMillis: Long? = null
        private set
    override fun events(): Flow<AudioEvent> {
        val recording = RoutedRecording(context, {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }, InputKind.Phone, readDurationMillis, onOpenChanged)
        return recording.events().onEach { frameEpochMillis = recording.frameEpochMillis }
    }
    override val frameArchiveId: String? get() = null
    override fun processed(samples: Int) {}
    override fun finished(discarded: Boolean) {}
}
