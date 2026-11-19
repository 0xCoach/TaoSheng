package app.resound.capture

/**
 * 敲敲检测：加速度计采样 → 识别"连敲 N 下"。平台无关纯逻辑，可单测。
 *
 * 算法：对加速度模长做滑动基线（慢速指数平均），瞬时模长超出基线 [spikeAbove]
 * 记一次"敲"；相邻两敲间隔须在 [tapGapMs] 窗口内（太近算同一次余震，太远算两次独立敲），
 * 凑满 [tapsRequired] 次触发一次；触发后 [cooldownMs] 内不再触发，防连发。
 *
 * 阈值按 sensitivity 缩放：越大越迟钝（防误触），越小越灵敏（敲轻点也能识别）。
 */
class KnockDetector(
    val tapsRequired: Int = 2,
    /** 0=迟钝 1=标准 2=灵敏 */
    sensitivity: Int = 1,
) {
    init { require(tapsRequired in 2..3); require(sensitivity in 0..2) }

    /** 敲击判定的模长增量（m/s²）。阈值高于放口袋/走路的低频晃动，低于真敲手机的尖峰。 */
    private val spikeAbove = when (sensitivity) { 0 -> 14f; 1 -> 10f; else -> 7f }
    /** 相邻敲击的最大间隔；两敲超过它就重新开始计数。 */
    private val tapGapMs = 550L
    /** 同一次敲击的余震抑制窗口——低于这个间隔的尖峰不另计。 */
    private val refractoryMs = 90L
    /** 触发后的冷却时间。 */
    private val cooldownMs = 1500L
    /** 基线跟踪速度：越小越慢，保证手正常握持变化不会被当敲击。 */
    private val baselineAlpha = 0.02f

    private var baseline = Float.NaN
    private var lastSpikeAt = 0L
    private var counted = 0
    private var lastFireAt = Long.MIN_VALUE

    /**
     * 喂一个采样。[at] 单调毫秒（elapsedRealtime）。
     * 返回 true = 本采样凑满敲击次数，触发一次。
     */
    fun onSample(at: Long, x: Float, y: Float, z: Float): Boolean {
        val mag = kotlin.math.sqrt(x * x + y * y + z * z)
        if (baseline.isNaN()) baseline = mag
        val delta = mag - baseline
        // 基线始终向当前模长慢速收敛：握持姿态/放置面的差异被自动吸收。
        baseline += baselineAlpha * delta

        if (delta > spikeAbove && at - lastSpikeAt > refractoryMs) {
            if (at - lastSpikeAt > tapGapMs) counted = 0
            lastSpikeAt = at; counted++
            // 用加法比较：at - MIN_VALUE 会溢出，导致首次永不触发。
            if (counted >= tapsRequired && at > lastFireAt + cooldownMs) {
                lastFireAt = at; counted = 0; return true
            }
        }
        return false
    }

    fun reset() { baseline = Float.NaN; lastSpikeAt = 0; counted = 0; lastFireAt = Long.MIN_VALUE }
}
