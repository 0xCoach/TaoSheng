package app.resound.audio

import kotlinx.coroutines.flow.Flow

/**
 * 音频来源：所有来源统一输出 16kHz、单声道、16 位 PCM，每帧 [FRAME_SAMPLES] 个采样（30 毫秒）。
 * 后面的人声检测、切段、转写只认这个格式，不关心声音来自哪个设备。
 *
 * 第一版实现：手机麦克风（默认）、导入录音文件。以后的蓝牙耳机、USB 麦、蓝牙录音设备各写一个实现。
 */
interface AudioSource {
    /** 稳定标识，写进每段录音，例如 "mic"、"import"。 */
    val id: String

    /** 给人看的名字，例如 "手机麦克风"。 */
    val displayName: String

    /**
     * 开始采集。收集方取消时停止并释放设备。
     * 设备被别的 App 或通话占用时，来源自己保持等待（不反复重开去抢），先发 [AudioEvent.Busy]，能用了再发 [AudioEvent.Available]。
     * 没权限、设备失效等无法自行恢复的情况以 [AudioSourceException] 结束。
     */
    fun events(): Flow<AudioEvent>

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 480
    }
}

sealed interface AudioEvent {
    class Frame(val samples: ShortArray) : AudioEvent

    /** 暂时收不到声音，[reason] 给人看，例如“别的应用在用麦克风” */
    data class Busy(val reason: String) : AudioEvent

    /** 从 Busy 恢复，接下来的帧是真实声音 */
    data object Available : AudioEvent
}

class AudioSourceException(val reason: Reason, message: String? = null) : Exception(message) {
    enum class Reason { NoPermission, Busy, Disconnected, Unknown }
}
