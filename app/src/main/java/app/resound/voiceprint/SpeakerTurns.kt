package app.resound.voiceprint

/** 将可能重叠的说话人区间转换成不重复的音频切片。重叠区间不强行指定身份。 */
object SpeakerTurns {
    data class Turn(val start: Int, val end: Int, val speaker: Int?)
    fun partition(input: List<Turn>, length: Int, maxSamples: Int = 28 * 16000): List<Turn> {
        val valid = input.map { it.copy(start = it.start.coerceIn(0, length), end = it.end.coerceIn(0, length)) }.filter { it.end > it.start }
        if (valid.isEmpty()) return (0 until length step maxSamples).map { Turn(it, it + minOf(maxSamples, length - it), null) }
        val edges = (listOf(0, length) + valid.flatMap { listOf(it.start, it.end) }).distinct().sorted()
        // 静音/低置信度区间也保留，不能以分段模型的漏检丢弃转写音频。
        val withGaps = (edges + edges.zipWithNext().mapNotNull { (a,b) ->
            if (valid.none { it.start < b && it.end > a }) (a+b)/2 else null
        }).distinct().sorted()
        val pieces = withGaps.zipWithNext().map { (a,b) ->
            val active = valid.filter { it.start < b && it.end > a }
            val speaker = if (active.isNotEmpty()) active.map { it.speaker }.distinct().singleOrNull()
                else valid.minByOrNull { minOf(kotlin.math.abs((a+b)/2-it.start),kotlin.math.abs((a+b)/2-it.end)) }?.speaker
            Turn(a,b,speaker)
        }
        val merged = mutableListOf<Turn>()
        for (part in pieces) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == part.speaker && last.end == part.start)
                merged[merged.lastIndex] = last.copy(end = part.end)
            else merged += part
        }
        return merged.flatMap { turn -> (turn.start until turn.end step maxSamples).map {
            turn.copy(start = it, end = it + minOf(maxSamples, turn.end - it))
        } }
    }
}
