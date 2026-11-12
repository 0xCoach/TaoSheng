package app.resound.audio

import kotlin.math.sqrt

/** Silero v6.2 的平台无关分帧与输入组装：16 kHz 单声道，512 新样本 + 64 上下文。
 * inference 同步读取 input[576]、读写 state[256]，不得保留引用。
 * consume 的完整帧 PCM 只借用到回调结束；需要保留的调用者须复制。
 * 每个实例串行使用，reset 清除跨文件状态，推理线程与资源由平台适配器管理。
 */
class StreamingVad(
    private val sensitivity: VadSensitivity,
    private val inference: (FloatArray, FloatArray) -> Float,
) {
    private val state = FloatArray(256)
    private val history = FloatArray(64)
    private val pending = ShortArray(512)
    private var size = 0
    private val input = FloatArray(576)

    /** 离线批处理复用模型，但不同录音不能共享循环状态、上下文或待处理尾帧。 */
    fun reset() {
        state.fill(0f)
        history.fill(0f)
        pending.fill(0)
        size = 0
        input.fill(0f)
    }

    fun accept(frame: ShortArray, consume: (ShortArray, Boolean, Float) -> Unit) {
        accept(frame, 0, frame.size, consume)
    }

    private fun accept(frame: ShortArray, from: Int, until: Int, consume: (ShortArray, Boolean, Float) -> Unit) {
        var offset = from
        while (offset < until) {
            val count = minOf(512 - size, until - offset)
            frame.copyInto(pending, size, offset, offset + count)
            offset += count; size += count
            if (size == 512) {
                val pcm = pending
                val rms = sqrt(pcm.sumOf { val n = it / 32768.0; n * n } / pcm.size).toFloat()
                history.copyInto(input)
                val gain = sensitivity.detectionGain(rms)
                pcm.forEachIndexed { i, value -> input[64 + i] = (value / 32768f * gain).coerceIn(-1f, 1f) }
                val probability = if (rms < sensitivity.rmsGate) {
                    // 被门限跳过的时间不能留下陈旧的循环网络状态。
                    state.fill(0f)
                    0f
                } else inference(input, state)
                input.copyInto(history, 0, 512, 576)
                consume(pcm, probability >= sensitivity.speechThreshold, rms.coerceIn(0f, 1f))
                size = 0
            }
        }
    }

    /** 离线文件尾部补零只用于 VAD 推理，写出的仍是实际样本，避免丢尾音或延长时间。 */
    fun finish(consume: (ShortArray, Boolean, Float) -> Unit) {
        if (size == 0) return
        val actual = size
        pending.fill(0, actual)
        // 从同一个缓冲补齐；尾帧回调仍交付独立的实际长度数组。
        accept(pending, actual, 512) { frame, speech, rms -> consume(frame.copyOf(actual), speech, rms) }
    }

}
