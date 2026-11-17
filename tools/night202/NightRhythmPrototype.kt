package app.resound.night

import kotlin.math.sqrt

/** 0.5 秒 RMS 包络的去均值归一化相关，滞后 2～6 秒；输入为线性幅度而非分贝。 */
object NightRhythm {
    // 先固定 0.5，评测只用调参源选值；未通过留出评测前不得启用。
    const val THRESHOLD = .5
    fun correlation(envelope: List<Double>): Double {
        if (envelope.size < 12) return 0.0
        val values = envelope.takeLast(60)
        val mean = values.average()
        val centered = values.map { it - mean }
        var best = 0.0
        for (lag in 4..minOf(12, centered.size / 2)) {
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in lag until centered.size) {
                val a = centered[i - lag]; val b = centered[i]
                dot += a * b; aa += a * a; bb += b * b
            }
            if (aa > 1e-15 && bb > 1e-15) best = maxOf(best, dot / sqrt(aa * bb))
        }
        return best
    }
    fun accepts(positiveWindows: Int, correlation: Double): Boolean = positiveWindows >= 3 && correlation >= THRESHOLD
}
