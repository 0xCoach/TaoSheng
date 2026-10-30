package app.resound.transcribe

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16k PCM 的保留区间。坐标使用样本数，落库前一次性恢复原录音毫秒。 */
internal class AsrSilencePlan private constructor(val originalSamples: Int, val spans: List<Span>) {
    data class Span(val originalStart: Int, val originalEnd: Int, val trimmedStart: Int) {
        val trimmedEnd: Int get() = trimmedStart + originalEnd - originalStart
    }
    val trimmedSamples: Int get() = spans.lastOrNull()?.trimmedEnd ?: 0
    val changed: Boolean get() = trimmedSamples != originalSamples

    fun originalMillis(millis: Long, end: Boolean = false): Long {
        val sample = millis.coerceIn(0, trimmedSamples / 16L) * 16
        // 接缝处：句首取右侧，句尾取左侧，不能把句尾指向停顿后的下一句话。
        val span = spans.firstOrNull { if (end) sample <= it.trimmedEnd else sample < it.trimmedEnd }
            ?: return originalSamples / 16L
        return (span.originalStart + sample - span.trimmedStart) / 16
    }

    fun restore(result: AsrResult, originalDuration: Long): AsrResult = result.copy(
        sentences = result.sentences.map { line ->
            val start = originalMillis(line.startMillis).coerceIn(0, originalDuration)
            line.copy(startMillis = start,
                endMillis = originalMillis(line.endMillis, end = true).coerceIn(start, originalDuration))
        }, durationMillis = originalDuration,
    )

    companion object {
        private const val FRAME = 320
        private const val MIN_PAUSE = 32_000 // 严格超过 2 秒。
        private const val PADDING = 4_000 // 两边各 250ms；保留约 0.5 秒。
        // 与既有声纹静音门限一致；弱声/背景声有疑问时整段保留。
        private const val QUIET_RMS = 0.002

        fun analyze(pcm: ShortArray): AsrSilencePlan = analyze(pcm.size) { start, end ->
            var sum = 0.0
            for (i in start until end) { val n = pcm[i] / 32768.0; sum += n * n }
            sum / (end - start) < QUIET_RMS * QUIET_RMS
        }

        /** 长录音只用一个 20ms 缓冲，云端两路不会额外常驻两份整段 PCM。 */
        fun analyze(file: File): AsrSilencePlan {
            require(file.length() > 0 && file.length() % 2 == 0L && file.length() / 2 <= Int.MAX_VALUE)
            return DataInputStream(file.inputStream().buffered()).use { input ->
                val bytes = ByteArray(FRAME * 2)
                analyze((file.length() / 2).toInt()) { start, end ->
                    val count = end - start
                    input.readFully(bytes, 0, count * 2)
                    val buffer = ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var sum = 0.0
                    repeat(count) { val n = buffer.short / 32768.0; sum += n * n }
                    sum / count < QUIET_RMS * QUIET_RMS
                }
            }
        }

        private fun analyze(samples: Int, isQuiet: (Int, Int) -> Boolean): AsrSilencePlan {
            val cuts = mutableListOf<Pair<Int, Int>>()
            var quietStart = -1
            for (start in 0 until samples step FRAME) {
                val quiet = isQuiet(start, minOf(start + FRAME, samples))
                if (quiet && quietStart < 0) quietStart = start
                if (!quiet && quietStart >= 0) {
                    // 不剪首尾静音；只缩短有前后声音的段内停顿。
                    if (quietStart > 0 && start - quietStart > MIN_PAUSE)
                        cuts += (quietStart + PADDING) to (start - PADDING)
                    quietStart = -1
                }
            }
            var original = 0
            var trimmed = 0
            val spans = buildList {
                for ((from, to) in cuts) {
                    add(Span(original, from, trimmed))
                    trimmed += from - original
                    original = to
                }
                if (samples > original) add(Span(original, samples, trimmed))
            }
            return AsrSilencePlan(samples, spans)
        }
    }
}
