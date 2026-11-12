package app.resound.audio

import org.junit.Assert.*
import org.junit.Test

class StreamingVad173Test {
    // 确定性、带递归状态的假模型；记录每一位输入/前状态/后状态/概率。
    private class Model {
        val calls = mutableListOf<List<Int>>()
        fun infer(input: FloatArray, state: FloatArray): Float {
            val before = input.map { it.toRawBits() } + state.map { it.toRawBits() }
            for (i in state.indices) state[i] = state[i] * 0.25f + input[i * 2] * 0.5f
            val probability = ((input[100] + state[7] + 1f) / 2).coerceIn(0f, 1f)
            calls += before + state.map { it.toRawBits() } + probability.toRawBits()
            return probability
        }
    }
    private data class Frame(val pcm: List<Short>, val speech: Boolean, val rms: Int)
    private fun signal() = ShortArray(512 * 70 + 137) { i ->
        when ((i / 512) % 7) {
            0 -> 0
            1 -> (i % 7 - 3).toShort()
            2 -> (i % 101 - 50).toShort()
            3 -> (i % 999 - 499).toShort()
            4 -> if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            else -> (i * 79).toShort()
        }
    }
    private fun compare(sensitivity: VadSensitivity, reset: Boolean = false) {
        val oldModel = Model(); val model = Model()
        val old = LegacySilero173(sensitivity, oldModel::infer)
        val vad = StreamingVad(sensitivity, model::infer)
        val expected = mutableListOf<Frame>(); val actual = mutableListOf<Frame>()
        val oldConsume: (ShortArray, Boolean, Float) -> Unit = { p, s, r -> expected += Frame(p.toList(), s, r.toRawBits()) }
        val consume: (ShortArray, Boolean, Float) -> Unit = { p, s, r -> actual += Frame(p.toList(), s, r.toRawBits()) }
        repeat(if (reset) 2 else 1) { round ->
            val input = signal()
            var offset = 0; var chunk = 0
            val sizes = intArrayOf(1, 319, 3200, 513, 7, 480)
            while (offset < input.size) {
                val end = minOf(input.size, offset + sizes[chunk++ % sizes.size])
                val block = input.copyOfRange(offset, end)
                old.accept(block, oldConsume); vad.accept(block, consume)
                offset = end
            }
            if (reset && round == 0) {
                // 丢掉未满的尾帧，验证离线下一文件不带入旧状态。
                old.reset(); vad.reset()
            } else {
                old.finish(oldConsume); vad.finish(consume)
                old.finish(oldConsume); vad.finish(consume) // finish 幂等
            }
        }
        assertTrue(model.calls.isNotEmpty())
        assertEquals(oldModel.calls, model.calls)
        assertEquals(expected, actual)
        assertEquals(137, actual.last().pcm.size)
    }
    @Test fun defaultInputStateProbabilityLevelAndPcmAreBitExact() = compare(VadSensitivity.DEFAULT)
    @Test fun externalGainAndClippingAreBitExact() = compare(VadSensitivity.EXTERNAL)
    @Test fun resetAndPaddedFinishAreBitExact() = compare(VadSensitivity.EXTERNAL, reset = true)

    @Test fun fullFramesReuseBuffersAndTailHasItsActualLength() {
        val inputs = mutableListOf<FloatArray>(); val states = mutableListOf<FloatArray>()
        val pcm = mutableListOf<ShortArray>()
        val vad = StreamingVad(VadSensitivity.DEFAULT) { x, s -> inputs += x; states += s; 1f }
        vad.accept(ShortArray(512 * 3 + 7) { 1000 }) { p, _, _ -> pcm += p }
        vad.finish { p, _, _ -> pcm += p }
        assertEquals(4, pcm.size)
        assertTrue(inputs.all { it === inputs.first() })
        assertTrue(states.all { it === states.first() })
        assertSame(pcm[0], pcm[1]); assertSame(pcm[1], pcm[2])
        assertEquals(7, pcm.last().size)
        assertNotSame(pcm.first(), pcm.last())
        assertTrue(pcm.last().all { it == 1000.toShort() })
    }
}
