package app.resound.voiceprint

/** 073 负责人定案：五分钟内最多均匀抽八个人声窗；不确定时完整分人。 */
internal object SpeakerPrecheck {
    const val ENABLED = true
    private const val RATE = 16000
    private const val WINDOW = 3 * RATE
    private const val STEP = WINDOW
    private const val SIMILARITY = 0.50f

    fun windows(samples: Int): List<IntRange> {
        // 编码尾部允许一个 32ms VAD 帧，包含实测 M3 的 300.018 秒。
        if (samples !in 6 * RATE..(300 * RATE + 512)) return emptyList()
        val starts = (0..samples - WINDOW step STEP).toMutableList()
        if (starts.last() != samples - WINDOW) starts += samples - WINDOW
        return starts.map { it until it + WINDOW }
    }

    fun single(pcm: ShortArray, speech: List<IntRange>, embedding: SpeakerEmbedding): Boolean {
        // speech 为 VAD 顺序输出的互不重叠区间；不拿静音窗参与相似度比较。
        val candidates = windows(pcm.size).filter { window ->
            speech.sumOf { (minOf(it.last, window.last) - maxOf(it.first, window.first) + 1).coerceAtLeast(0) } >= WINDOW * 0.80
        }
        if (candidates.size < 3) return false
        val windows = if (candidates.size <= 8) candidates else
            (0 until 8).map { candidates[it * (candidates.size - 1) / 7] }
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
