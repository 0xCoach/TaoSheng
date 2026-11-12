package app.resound.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 官方 v6.2 流式协议：512 新样本 + 64 上下文，state=[2,1,128]。 */
class SileroVad(context: Context, private val sensitivity: VadSensitivity = VadSensitivity.DEFAULT) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
        env.createSession(context.assets.open("silero_vad.onnx").use { it.readBytes() }, options)
    }
    private val inputBuffer = ByteBuffer.allocateDirect(576 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val stateBuffer = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val rateBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer().apply { put(0, 16000L) }
    private val tensors = mutableListOf<OnnxTensor>()
    private val inputs: Map<String, OnnxTensor>
    init {
        try {
            fun tensor(buffer: java.nio.FloatBuffer, shape: LongArray) =
                OnnxTensor.createTensor(env, buffer, shape).also { tensors += it }
            val x = tensor(inputBuffer, longArrayOf(1, 576))
            val h = tensor(stateBuffer, longArrayOf(2, 1, 128))
            val sr = OnnxTensor.createTensor(env, rateBuffer, longArrayOf()).also { tensors += it }
            inputs = mapOf("input" to x, "state" to h, "sr" to sr)
        } catch (e: Throwable) {
            tensors.forEach { runCatching { it.close() } }
            session.close()
            throw e
        }
    }
    private val stream = StreamingVad(sensitivity, ::infer)

    fun reset() = stream.reset()
    /** PCM 为同步借用；当前切段/导入消费者均在返回前完成读取，不保留数组引用。 */
    fun accept(frame: ShortArray, consume: (ShortArray, Boolean, Float) -> Unit) = stream.accept(frame, consume)
    fun finish(consume: (ShortArray, Boolean, Float) -> Unit) = stream.finish(consume)

    private fun infer(input: FloatArray, state: FloatArray): Float {
        inputBuffer.clear(); inputBuffer.put(input); inputBuffer.rewind()
        stateBuffer.clear(); stateBuffer.put(state); stateBuffer.rewind()
        // 直接缓冲由输入张量引用；不再逐帧创建输入张量和 Map。
        // 输出由本次 Result 管理，读取后立即释放，不与下一轮输入状态别名。
        session.run(inputs).use { output ->
            (output.get("stateN").get() as OnnxTensor).floatBuffer.get(state)
            return (output.get("output").get() as OnnxTensor).floatBuffer.get(0)
        }
    }
    override fun close() {
        try { tensors.forEach { it.close() } } finally { session.close() }
    }
}
