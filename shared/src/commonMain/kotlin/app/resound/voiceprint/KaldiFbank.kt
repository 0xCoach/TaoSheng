package app.resound.voiceprint

import kotlin.math.*

/** CAM++ 的 Kaldi 80 维 fbank：16k、25ms/10ms、反射补边、零 dither、Povey。 */
class KaldiFbank {
    private val window = DoubleArray(400) { (0.5 - 0.5 * cos(2 * PI * it / 399)).pow(0.85) }
    private val filters = Array(80) { m ->
        val low = mel(20.0)
        val step = (mel(7600.0) - low) / 81
        val left = low + step * m
        val center = left + step
        val right = center + step
        DoubleArray(257) { k ->
            val hz = mel(k * 16000.0 / 512)
            max(0.0, min((hz - left) / (center - left), (right - hz) / (right - center)))
        }
    }
    fun compute(pcm: ShortArray, normalize: Boolean = true): Array<FloatArray> {
        val count = (pcm.size + 80) / 160
        val features = Array(count) { frame ->
            val real = DoubleArray(512)
            val imag = DoubleArray(512)
            for (i in 0 until 400) {
                var index = frame * 160 - 120 + i
                while (index < 0 || index >= pcm.size) {
                    index = if (index < 0) -index - 1 else 2 * pcm.size - 1 - index
                }
                real[i] = pcm[index] / 32768.0
            }
            val mean = real.take(400).sum() / 400
            for (i in 0 until 400) real[i] -= mean
            for (i in 399 downTo 1) real[i] -= 0.97 * real[i - 1]
            real[0] *= 0.03
            for (i in 0 until 400) real[i] *= window[i]
            fft(real, imag)
            val power = DoubleArray(257) { real[it] * real[it] + imag[it] * imag[it] }
            FloatArray(80) { m ->
                var energy = 0.0
                for (k in 0..256) energy += power[k] * filters[m][k]
                ln(max(energy, 1.1920928955078125e-7)).toFloat()
            }
        }
        if (normalize && count > 0) for (m in 0 until 80) {
            val mean = features.sumOf { it[m].toDouble() } / count
            for (frame in features) frame[m] = (frame[m] - mean).toFloat()
        }
        return features
    }
    private fun mel(hz: Double) = 1127 * ln(1 + hz / 700)
    private fun fft(r: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until r.size) {
            var bit = r.size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = r[i]; r[i] = r[j]; r[j] = t }
        }
        var length = 2
        while (length <= r.size) {
            val angle = -2 * PI / length
            for (base in r.indices step length) {
                for (k in 0 until length / 2) {
                    val a = base + k; val b = a + length / 2
                    val c = cos(angle * k); val s = sin(angle * k)
                    val tr = c * r[b] - s * im[b]; val ti = s * r[b] + c * im[b]
                    r[b] = r[a] - tr; im[b] = im[a] - ti
                    r[a] += tr; im[a] += ti
                }
            }
            length *= 2
        }
    }
}
