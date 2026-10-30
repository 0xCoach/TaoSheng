package app.resound.voiceprint

/** 少量离散抽样会漏掉短插话；这里只接受短段、全覆盖重叠窗的强一致证据。 */
internal object SpeakerPrecheck {
    private const val RATE = 16000
    private const val WINDOW = 3 * RATE
    private const val STEP = WINDOW / 2
    private const val SIMILARITY = 0.90f

    fun windows(samples: Int): List<IntRange> {
        if (samples !in 6 * RATE..60 * RATE) return emptyList()
        val starts = (0..samples - WINDOW step STEP).toMutableList()
        if (starts.last() != samples - WINDOW) starts += samples - WINDOW
        return starts.map { it until it + WINDOW }
    }

    fun single(pcm: ShortArray, speech: List<IntRange>, embedding: SpeakerEmbedding): Boolean {
        val windows = windows(pcm.size)
        if (windows.isEmpty()) return false
        // speech 为 VAD 顺序输出的互不重叠区间；有长静音、噪声或弱声时不作单人结论。
        if (speech.sumOf { it.last.toLong() - it.first + 1 } < pcm.size * 0.90) return false
        val vectors = mutableListOf<FloatArray>()
        for (window in windows) {
            val voiced = speech.sumOf { (minOf(it.last, window.last) - maxOf(it.first, window.first) + 1).coerceAtLeast(0) }
            if (voiced < WINDOW * 0.80) return false
            val vector = VoiceprintMath.unit(embedding.compute(pcm.copyOfRange(window.first, window.last + 1)))
            if (vector.size != 192 || vectors.any { VoiceprintMath.cosine(it, vector) < SIMILARITY }) return false
            vectors += vector
        }
        return true
    }
}
