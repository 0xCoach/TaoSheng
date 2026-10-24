package app.resound.audio

/** 连续流箱形积分重采样：跨 read 保留相位、声道和帧余量；降采样先平均，避免直接抽点。 */
internal class PcmInputConverter(private val rate: Int, private val channels: Int) {
    init { require(rate > 0 && channels in 1..2) }
    private var channelSum = 0L
    private var channelCount = 0
    private var weighted = 0.0
    private var weight = 0
    private var frame = ShortArray(AudioSource.FRAME_SAMPLES)
    private var filled = 0
    fun reset() { channelSum = 0; channelCount = 0; weighted = 0.0; weight = 0; filled = 0 }
    fun accept(pcm: ShortArray, count: Int): List<ShortArray> {
        require(count in 0..pcm.size)
        val result = mutableListOf<ShortArray>()
        for (i in 0 until count) {
            channelSum += pcm[i]; channelCount++
            if (channelCount != channels) continue
            val mono = channelSum.toDouble() / channels
            channelSum = 0; channelCount = 0
            var remaining = AudioSource.SAMPLE_RATE
            while (remaining > 0) {
                val part = minOf(remaining, rate - weight)
                weighted += mono * part; weight += part; remaining -= part
                if (weight == rate) {
                    frame[filled++] = (weighted / rate).toInt().coerceIn(-32768, 32767).toShort()
                    weighted = 0.0; weight = 0
                    if (filled == frame.size) { result += frame; frame = ShortArray(AudioSource.FRAME_SAMPLES); filled = 0 }
                }
            }
        }
        return result
    }
}
