package app.resound.envsound

/** 16k PCM 的非人声段：先筛原长 ≥0.5秒，再两端各收0.1秒；收边后累计不足2秒不推理。 */
internal object EnvSoundGaps {
    const val MIN_SAMPLES = 2 * 16000
    private const val LIMIT = 30 * 16000
    data class Result(val pcm: ShortArray, val availableSamples: Int)

    class Collector {
        private var offset = 0
        private var start = -1
        private val ranges = mutableListOf<IntRange>()
        fun accept(size: Int, speech: Boolean) {
            require(size > 0)
            if (speech && start >= 0) {
                ranges += start until offset
                start = -1
            } else if (!speech && start < 0) start = offset
            offset += size
        }
        fun finish(pcm: ShortArray): Result {
            check(offset == pcm.size)
            val all = if (start >= 0) ranges + listOf(start until offset) else ranges
            return extract(pcm, all)
        }
    }

    fun extract(pcm: ShortArray, nonSpeech: List<IntRange>): Result {
        var previousEnd = -1
        val trimmed = nonSpeech.mapNotNull { range ->
            require(!range.isEmpty() && range.first > previousEnd && range.last < pcm.size)
            previousEnd = range.last
            if (range.last - range.first + 1 >= 8000) range.first + 1600..range.last - 1600 else null
        }
        val total = trimmed.sumOf { it.last - it.first + 1 }
        if (total < MIN_SAMPLES) return Result(ShortArray(0), total)
        val result = ShortArray(minOf(total, LIMIT))
        // 在拼接时间轴上均匀取30个连续1秒窗，不做逐点抽样，以免改变音高。
        val windows = if (total <= LIMIT) listOf(0 until total) else (0 until 30).map {
            val start = (it.toLong() * (total - 16000) / 29).toInt()
            start until start + 16000
        }
        var target = 0
        var index = 0
        var logicalStart = 0
        for (window in windows) {
            var at = window.first
            while (at <= window.last) {
                val range = trimmed[index]
                val length = range.last - range.first + 1
                if (at >= logicalStart + length) {
                    logicalStart += length
                    index++
                    continue
                }
                val count = minOf(window.last - at + 1, logicalStart + length - at)
                val source = range.first + at - logicalStart
                pcm.copyInto(result, target, source, source + count)
                target += count
                at += count
            }
        }
        check(target == result.size)
        return Result(result, total)
    }
}
