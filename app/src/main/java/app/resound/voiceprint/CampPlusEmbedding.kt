package app.resound.voiceprint

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

fun interface SpeakerEmbedding {
    fun compute(pcm: ShortArray): FloatArray
}

/** 复用项目的 ONNX Runtime，不新增原生 .so；每轮资源在 use/finally 中释放。 */
class CampPlusEmbedding(context: Context) : SpeakerEmbedding, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(VoiceprintThreads.count); options.setInterOpNumThreads(1)
        env.createSession(context.assets.open("voiceprint/campplus.onnx").use { it.readBytes() }, options)
    }
    private val fbank = KaldiFbank()
    override fun compute(pcm: ShortArray): FloatArray {
        require(pcm.size >= VoiceprintAudio.MIN_SAMPLES) { "有效人声不足 1.5 秒" }
        val frames = fbank.compute(pcm)
        val flat = FloatArray(frames.size * 80)
        frames.forEachIndexed { i, frame -> frame.copyInto(flat, i * 80) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, frames.size.toLong(), 80)).use { input ->
            session.run(mapOf("x" to input)).use { outputs ->
                val buffer = (outputs[0] as OnnxTensor).floatBuffer
                check(buffer.remaining() == 192)
                return VoiceprintMath.unit(FloatArray(192).also { buffer.get(it) })
            }
        }
    }
    override fun close() = session.close()
}

/** 分人、声纹只能跑在 CPU 上：按核数用一半（8 核用 4 个，4 核用 2 个，2 核以下用 1 个），给录音和界面留余量 */
internal object VoiceprintThreads {
    val count = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
}
