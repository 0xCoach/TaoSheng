package app.resound.capture

/** 音频规则全部按采样数计算，不受系统时间调整影响。 */
object CaptureTuning {
    const val SAMPLE_RATE = 16_000
    const val PRE_ROLL_SAMPLES = SAMPLE_RATE
    const val END_SILENCE_SAMPLES = 2 * SAMPLE_RATE
    const val MIN_SPEECH_SAMPLES = SAMPLE_RATE
    const val MAX_SEGMENT_SAMPLES = 5 * 60 * SAMPLE_RATE
    const val VAD_SAMPLES = 512
    /**
     * 低于这个音量（约 -54 dBFS）的帧直接当安静，不跑人声检测。
     * 小米 14 Pro 的 VOICE_RECOGNITION 音源增益很低：本人近讲中位约 -43 dBFS，原先 0.008（-42 dBFS）会筛掉一半人声。
     * 用真机录音离线对比：-42 → -54 dBFS 后本人语音检出率 25% → 67%。
     */
    const val RMS_GATE = 0.002f
    const val SPEECH_THRESHOLD = 0.5f
    const val BIT_RATE = 24_000
    const val HEARTBEAT_MS = 60_000L
    const val BATTERY_MS = 600_000L
    const val MIC_RETRY_MS = 3_000L
}
