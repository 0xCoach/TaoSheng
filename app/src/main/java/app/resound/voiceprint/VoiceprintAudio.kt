package app.resound.voiceprint

import app.resound.data.TranscriptLineEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** null tag 只归该句，云端未区分的人绝不合为同一人。 */
object VoiceprintAudio {
    const val RATE = 16000
    const val MIN_SAMPLES = 24000
    // 与目标手机当前采集的低增益匹配；仅用于转写句内的离线静音裁剪。
    const val SILENCE_RMS = 0.002
    fun groupKey(line: TranscriptLineEntity) = line.speakerTag?.let { "tag:$it" } ?: "line:${line.id}"

    /** 合并重叠范围，排除句间声音；裁剪到录音边界后再去静音。 */
    fun group(pcm: ShortArray, segmentStart: Long, lines: List<TranscriptLineEntity>): ShortArray {
        val ranges = lines.mapNotNull {
            val from = ((it.startAt - segmentStart).coerceAtLeast(0) * 16).coerceAtMost(pcm.size.toLong()).toInt()
            val to = ((it.endAt - segmentStart).coerceAtLeast(0) * 16).coerceAtMost(pcm.size.toLong()).toInt()
            if (to > from) from until to else null
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        // 在各自范围内切帧，避免把界外的声音混入边缘帧。
        val parts = merged.map { trimSilence(pcm.copyOfRange(it.first, it.last + 1)) }
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(out, offset); offset += part.size }
        return out
    }
    /** 固定 20ms RMS 门限，仅去静音，不改变录音服务的 Silero 行为。 */
    fun trimSilence(pcm: ShortArray): ShortArray {
        val out = ShortArray(pcm.size)
        var size = 0
        for (start in pcm.indices step 320) {
            val end = minOf(start + 320, pcm.size)
            var energy = 0.0
            for (i in start until end) { val n = pcm[i] / 32768.0; energy += n * n }
            if (sqrt(energy / (end - start)) >= SILENCE_RMS) {
                pcm.copyInto(out, size, start, end); size += end - start
            }
        }
        return out.copyOf(size)
    }
}

object VoiceprintMath {
    fun unit(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "声纹向量无效" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm > 1e-10) { "声纹向量为空" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        val x = unit(a); val y = unit(b)
        return x.indices.sumOf { x[it].toDouble() * y[it] }.toFloat().coerceIn(0f, 1f)
    }
    fun representative(samples: List<FloatArray>): FloatArray {
        require(samples.isNotEmpty())
        val normalized = samples.map(::unit)
        val size = normalized.first().size
        require(normalized.all { it.size == size })
        return unit(FloatArray(size) { i -> normalized.sumOf { it[i].toDouble() }.toFloat() / samples.size })
    }
    // 只有确定是本人才写“我”交给整理；“可能是我”只在界面显示问号，等用户确认
    fun name(score: Float?) = if (score != null && score >= VoiceprintThresholds.SURE) "我" else null
    fun encode(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { vector.forEach { putFloat(it) } }.array()
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == 192 * 4) { "声纹样本格式无效" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
