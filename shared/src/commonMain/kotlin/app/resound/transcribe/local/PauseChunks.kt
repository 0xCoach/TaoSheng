package app.resound.transcribe.local

import app.resound.voiceprint.SpeakerTurns
import kotlin.math.sqrt

/**
 * 把一段录音切成交给识别模型的小块。
 *
 * 卡 222：不再按说话人切（一换人就切会碎成平均 4 秒一块），整段只按停顿切：
 * 快到 [TARGET] 时在前 5 秒里找最后一个停顿；找不到就往后找，最晚到 [LIMIT]；
 * 还没有才在最安静处硬切，并让后一块重叠 1 秒防丢字。说话人由 [TurnTags] 按每个字的时间贴回去。
 * 实测（AliMeeting 8 场 × 6 分钟）：错字率不变，块数 779 → 153，识别时间约减半。
 */
object PauseChunks {
    /** 目标长度：15～20 秒之间实测最好 */
    const val TARGET = 20 * 16000
    /** 硬上限：NPU 一次只能看 30 秒，留 2 秒余量 */
    const val LIMIT = 28 * 16000
    data class Chunk(val start: Int, val end: Int, val speaker: Int?, val overlapSamples: Int = 0, val groupStart: Int = start)

    /** from..until 之间每个 ≥0.2 秒停顿的中点，以及最安静的位置 */
    private fun pauses(pcm: ShortArray, from: Int, until: Int): Pair<List<Int>, Int> {
        val found = mutableListOf<Int>()
        var silenceStart = -1; var quiet = until - 320; var quietEnergy = Double.MAX_VALUE
        fun close(end: Int) { if (silenceStart >= 0 && end - silenceStart >= 3200) found += (silenceStart + end) / 2 }
        var at = from
        while (at < until) {
            val end = minOf(at + 320, until)
            var energy = 0.0
            for (i in at until end) { val x = pcm[i] / 32768.0; energy += x * x }
            val rms = sqrt(energy / (end - at))
            if (rms <= quietEnergy) { quietEnergy = rms; quiet = (at + end) / 2 }
            if (rms < 0.006) { if (silenceStart < 0) silenceStart = at }
            else { close(at); silenceStart = -1 }
            at = end
        }
        close(until)
        return found to quiet
    }

    fun split(pcm: ShortArray, turns: List<SpeakerTurns.Turn>): List<Chunk> = buildList {
        for (turn in turns) {
            var start = turn.start; var overlap = 0
            while (turn.end - start > TARGET) {
                val back = pauses(pcm, maxOf(start + 16000, start + TARGET - 5 * 16000), start + TARGET).first
                val cut: Int; val hard: Boolean
                if (back.isNotEmpty()) { cut = back.last(); hard = false }
                else if (turn.end - start <= LIMIT) break
                else {
                    val (forward, _) = pauses(pcm, start + TARGET, start + LIMIT)
                    if (forward.isNotEmpty()) { cut = forward.first(); hard = false }
                    else { cut = pauses(pcm, start + LIMIT - 5 * 16000, start + LIMIT).second; hard = true }
                }
                add(Chunk(start, cut, turn.speaker, overlap, turn.start))
                overlap = if (hard) 16000 else 0
                start = cut - overlap
            }
            if (turn.end > start) add(Chunk(start, turn.end, turn.speaker, overlap, turn.start))
        }
    }

    /** 整段录音一起切，不看说话人 */
    fun split(pcm: ShortArray): List<Chunk> = split(pcm, listOf(SpeakerTurns.Turn(0, pcm.size, null)))
}

/** 按时间查说话人：识别出的每个字落在哪段说话人区间，就标成谁 */
class TurnTags(turns: List<ResumeTurn>, private val speakers: Boolean) {
    private val turns = turns.sortedBy { it.start }
    private fun turnAt(sample: Int): ResumeTurn? {
        if (turns.isEmpty()) return null
        var lo = 0; var hi = turns.lastIndex
        while (lo < hi) { val mid = (lo + hi) / 2; if (turns[mid].end <= sample) lo = mid + 1 else hi = mid }
        return turns[lo]
    }
    private fun tag(t: ResumeTurn?): String? = t?.speaker?.toString() ?: if (speakers && t != null) "uncertain:${t.start}" else null
    /** ms：在整段录音里的毫秒位置 */
    fun at(ms: Long): String? = tag(turnAt((ms * 16).toInt()))
    /** 没有逐字时间时，整块标成占时最长的那个人 */
    fun dominant(start: Int, end: Int): String? {
        val share = linkedMapOf<ResumeTurn, Int>()
        for (t in turns) { val o = minOf(end, t.end) - maxOf(start, t.start); if (o > 0) share[t] = (share[t] ?: 0) + o }
        return tag(share.maxByOrNull { it.value }?.key ?: turnAt(start))
    }
}
