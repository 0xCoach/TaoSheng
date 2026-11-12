package app.resound.audio

import app.resound.capture.SpeechSegmenter
import org.junit.Assert.*
import org.junit.Test

/** 真实转换器 → 与 Silero 共用的 512 分帧/组装 → 按音量假模型 → 真实切段器。 */
class BatchedRead173Test {
    private data class Part(val start: Long, val epoch: Long, val pcm: MutableList<Short> = mutableListOf(), var end: Long = -1, var keep: Boolean = false)
    private fun run(rate: Int, channels: Int, chunks: IntArray): List<Part> {
        // 66 秒，语音/不同音量/全零/低于门限，边界故意不对齐 20/200/512。
        val pcm = ShortArray(rate * channels * 66) { i ->
            val sample = i / channels
            val t = sample * 16000L / rate
            val amplitude = when {
                t in 16013L..87921L -> 5000
                t in 190007L..311333L -> 10000
                t in 450019L..488123L -> 900
                t in 620031L..799999L -> 4000
                t in 940011L..1055999L -> 16000
                else -> 0
            }
            ((sample % 97 - 48) * amplitude / 48 + if (amplitude > 0) i % channels else 0).toShort()
        }
        var received = 0
        val epoch = 1_700_000_000_000L
        val clock = PcmBatchClock(rate, channels) { epoch + received * 1000L / (rate.toLong() * channels) }
        val converter = PcmInputConverter(rate, channels)
        val vad = StreamingVad(VadSensitivity.DEFAULT) { input, state ->
            state.fill(0f)
            if (input.drop(64).sumOf { (it * it).toDouble() } / 512 > 0.0004) 1f else 0f
        }
        val parts = mutableListOf<Part>()
        var cutter: SpeechSegmenter? = null
        var block = 0
        while (received < pcm.size) {
            val n = minOf(chunks[block++ % chunks.size], pcm.size - received)
            val input = pcm.copyOfRange(received, received + n)
            received += n
            clock.readCompleted(n)
            for (frame in converter.accept(input, n)) {
                val frameTime = clock.nextFrame(frame.size)
                val segmenter = cutter ?: SpeechSegmenter(object : SpeechSegmenter.Sink {
                    override fun begin(startSample: Long) { parts += Part(startSample, frameTime + startSample * 1000 / 16000) }
                    // 与真实 sink 一样同步读完，回调缓冲之后被复用不得影响已保存样本。
                    override fun write(samples: ShortArray) { parts.last().pcm.addAll(samples.toList()) }
                    override fun end(endSample: Long, keep: Boolean) { parts.last().end = endSample; parts.last().keep = keep }
                }).also { cutter = it }
                vad.accept(frame) { p, speech, _ -> segmenter.accept(p, speech) }
            }
        }
        vad.finish { p, speech, _ -> cutter!!.accept(p, speech) }
        cutter!!.finish()
        assertTrue(parts.size >= 4)
        assertTrue(parts.all { it.end - it.start == it.pcm.size.toLong() && it.keep })
        assertEquals(66 * 16000L, parts.last().end) // 尾部说话也保留
        return parts
    }
    private fun compare(rate: Int, channels: Int, switching: Boolean = false) {
        val small = rate * channels / 50
        val large = rate * channels / 5
        val expected = run(rate, channels, intArrayOf(small))
        val other = if (switching) intArrayOf(large, large, small, small, large, 1, 997, 17) else intArrayOf(large)
        val actual = run(rate, channels, other)
        assertEquals(expected, actual) // 起止位置、epoch、keep 及每一个 PCM 样本
        if (!switching) assertEquals(expected, run(rate, channels, intArrayOf(1, 997, 17, large + 31, 480)))
    }
    @Test fun monoSixtySixSecondsAreSampleExactForAllBlockSizes() = compare(16000, 1)
    @Test fun stereoResamplingAndPartialChannelsAreSampleExact() = compare(44100, 2)
    @Test fun changingReadSizeMidUtteranceKeepsEverySampleAndTimestamp() = compare(48000, 2, switching = true)
}
