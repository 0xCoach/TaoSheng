package app.resound.transcribe

/**
 * 低置信转写复核：本地 SenseVoice 不出置信分，改用可观测信号推“值得人工过一眼”的原话行。
 * 纯规则、与平台无关；UI/存储层只消费 flag 集合与权重。
 */
enum class ReviewFlag(val label: String) {
    /** 行内有字但速率高得不像人话（噪声粘连/引擎幻觉）。 */
    SuspiciousDensity("语速异常"),

    /** 该段重试过：首次识别失败过的段落出错率更高。 */
    RetriedSegment("多次重试"),

    /** 分了人但声纹匹配分低：归错人的概率高。 */
    LowSpeakerScore("说话人存疑"),

    /** 同音纠正改写过原文：纠正本身可能改错。 */
    CorrectedText("已同音纠正"),
}

object TranscriptReviewRules {
    /** 中文正常语速约 4～6 字/秒；>7 判异常。 */
    const val MAX_CHARS_PER_SECOND = 7.0

    /** 时长 ≥4s 却只出一两个字，大概率漏听。 */
    const val LONG_LINE_MILLIS = 4_000L
    const val MIN_CHARS_FOR_LONG_LINE = 2

    const val LOW_SPEAKER_SCORE = 0.35f

    fun flagsOf(text: String, millis: Long, attempts: Int, speakerScore: Float?, speakerTag: String?, corrected: Boolean): Set<ReviewFlag> {
        val flags = linkedSetOf<ReviewFlag>()
        val chars = text.trim().length
        if (millis > 0 && chars > 0) {
            val rate = chars / (millis / 1000.0)
            if (rate > MAX_CHARS_PER_SECOND || (millis >= LONG_LINE_MILLIS && chars <= MIN_CHARS_FOR_LONG_LINE)) {
                flags += ReviewFlag.SuspiciousDensity
            }
        }
        if (attempts > 1) flags += ReviewFlag.RetriedSegment
        if (speakerTag != null && speakerScore != null && speakerScore < LOW_SPEAKER_SCORE) flags += ReviewFlag.LowSpeakerScore
        if (corrected) flags += ReviewFlag.CorrectedText
        return flags
    }

    /** 排序权重：越可疑越靠前；CorrectedText 只是弱信号。 */
    fun weight(flags: Set<ReviewFlag>): Int = flags.sumOf {
        when (it) {
            ReviewFlag.SuspiciousDensity -> 4
            ReviewFlag.RetriedSegment -> 3
            ReviewFlag.LowSpeakerScore -> 2
            ReviewFlag.CorrectedText -> 1
        }
    }
}
