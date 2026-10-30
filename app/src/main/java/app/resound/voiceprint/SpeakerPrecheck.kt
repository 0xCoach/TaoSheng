package app.resound.voiceprint

/** 073 审核候选：比较五分钟内所有有人声的重叠窗；质量门禁未过，产品禁用。 */
internal object SpeakerPrecheck {
    // 073 审核复测：M1 命中后改变原 ASR 切片并产生较大文字差异，产品暂不启用。
    const val ENABLED = false
    private const val RATE = 16000
    private const val WINDOW = 3 * RATE
    private const val STEP = WINDOW / 2
    private const val SIMILARITY = 0.30f

    fun windows(samples: Int): List<IntRange> {
        // 编码尾部允许一个 32ms VAD 帧，包含实测 M3 的 300.018 秒。
        if (samples !in 6 * RATE..(300 * RATE + 512)) return emptyList()
        val starts = (0..samples - WINDOW step STEP).toMutableList()
        if (starts.last() != samples - WINDOW) starts += samples - WINDOW
        return starts.map { it until it + WINDOW }
    }

    fun single(pcm: ShortArray, speech: List<IntRange>, embedding: SpeakerEmbedding): Boolean {
        // speech 为 VAD 顺序输出的互不重叠区间；不拿静音窗参与相似度比较。
        val windows = windows(pcm.size).filter { window ->
            speech.sumOf { (minOf(it.last, window.last) - maxOf(it.first, window.first) + 1).coerceAtLeast(0) } >= WINDOW * 0.80
        }
        if (windows.size < 3) return false
        val vectors = mutableListOf<FloatArray>()
        for (window in windows) {
            val vector = VoiceprintMath.unit(embedding.compute(pcm.copyOfRange(window.first, window.last + 1)))
            if (vector.size != 192 || vectors.any { VoiceprintMath.cosine(it, vector) < SIMILARITY }) return false
            vectors += vector
        }
        val center = VoiceprintMath.representative(vectors)
        return vectors.all { VoiceprintMath.cosine(it, center) >= SIMILARITY }
    }
}
