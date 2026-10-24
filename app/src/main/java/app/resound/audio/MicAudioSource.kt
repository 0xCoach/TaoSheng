package app.resound.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.Flow

/** 显式指定内置麦克风，避免插入 USB / 耳机后系统默认路由冒充手机录音。 */
class MicAudioSource(private val context: Context, private val onOpenChanged: (Boolean) -> Unit = {}) : AudioSource {
    override val id = "mic"
    override val displayName = "手机麦克风"
    override fun events(): Flow<AudioEvent> = RoutedRecording(context, {
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }, InputKind.Phone, onOpenChanged).events()
}
