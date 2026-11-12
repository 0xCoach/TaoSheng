package app.resound.audio

/** 声波可见时维持交互节奏；无人订阅时合并为每秒约 5 次阻塞读。
 * 200 ms 小于最短的 2 秒健康超时，也把恢复声波/让麦的额外等待限制在一圈内。
 */
object CaptureReadPolicy {
    const val INTERACTIVE_MS = 20
    const val BATCH_MS = 200
    fun durationMillis(levelObserved: Boolean) = if (levelObserved) INTERACTIVE_MS else BATCH_MS
    fun samples(rate: Int, channels: Int, durationMillis: Int): Int =
        // 下限 960 是改前的读取长度（16 kHz 单声道约 60 毫秒）：有人看声波时与改前完全一样，不比原来更勤
        maxOf(960, rate * channels * durationMillis.coerceIn(INTERACTIVE_MS, BATCH_MS) / 1000)
}

/** 手机 PCM 的样本时钟：首次成功读取的末尾为时钟锚点，减去本批原生样本时长。
 * rawSamples 包含交错声道；输出是 16 kHz 单声道。转换器保留的不足 480 样本的尾巴
 * 仍属于同一条时间轴，不能只减本批已输出帧的长度。后续用累计样本推进，避免墙钟抖动。
 * 路由不匹配/让麦时与转换器一起 reset。时钟由平台注入，不猜回调次数。
 */
class PcmBatchClock(private val rate: Int, private val channels: Int, private val nowMillis: () -> Long) {
    private var epoch: Long? = null
    private var emitted = 0L
    init { require(rate > 0 && channels in 1..2) }
    fun reset() { epoch = null; emitted = 0 }
    fun readCompleted(rawSamples: Int) {
        require(rawSamples > 0)
        if (epoch == null) epoch = nowMillis() - rawSamples * 1000L / (rate.toLong() * channels)
    }
    fun nextFrame(samples: Int): Long {
        require(samples > 0)
        val start = checkNotNull(epoch) + emitted * 1000 / 16_000
        emitted += samples
        return start
    }
}
