package app.resound.audio

import kotlin.math.sqrt

/** 卡 173 改前 accept/finish/reset 原样保留；仅替换 Android 模型调用。 */
internal class LegacySilero173(
    private val sensitivity: VadSensitivity,
    private val inference: (FloatArray, FloatArray) -> Float,
) {
    private var state = FloatArray(256)
    private var history = FloatArray(64)
    private val pending = ShortArray(512)
    private var size = 0

    /** 离线批处理复用模型，但不同录音不能共享循环状态、上下文或待处理尾帧。 */
    fun reset() {
        state.fill(0f)
        history.fill(0f)
        pending.fill(0)
        size = 0
    }

    fun accept(frame: ShortArray, consume: (ShortArray, Boolean, Float) -> Unit) {
        var offset = 0
        while (offset < frame.size) {
            val count = minOf(512 - size, frame.size - offset)
            frame.copyInto(pending, size, offset, offset + count)
            offset += count; size += count
            if (size == 512) {
                val pcm = pending.copyOf()
                val rms = sqrt(pcm.sumOf { val n = it / 32768.0; n * n } / pcm.size).toFloat()
                val input = FloatArray(576)
                history.copyInto(input)
                val gain = sensitivity.detectionGain(rms)
                pcm.forEachIndexed { i, value -> input[64 + i] = (value / 32768f * gain).coerceIn(-1f, 1f) }
                val probability = if (rms < sensitivity.rmsGate) {
                    // 被门限跳过的时间不能留下陈旧的循环网络状态。
                    state.fill(0f)
                    0f
                } else infer(input)
                history = input.copyOfRange(512, 576)
                consume(pcm, probability >= sensitivity.speechThreshold, rms.coerceIn(0f, 1f))
                size = 0
            }
        }
    }

    /** 离线文件尾部补零只用于 VAD 推理，写出的仍是实际样本，避免丢尾音或延长时间。 */
    fun finish(consume: (ShortArray, Boolean, Float) -> Unit) {
        if (size == 0) return
        val actual = size
        accept(ShortArray(512 - size)) { frame, speech, rms -> consume(frame.copyOf(actual), speech, rms) }
    }

    private fun infer(input: FloatArray) = inference(input, state)
}
