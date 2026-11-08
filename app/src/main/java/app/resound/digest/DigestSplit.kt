package app.resound.digest

import app.resound.data.TranscriptLineEntity
import kotlin.math.abs

internal const val MIN_SPLIT_LINES = 40

/** 原话已按时间排序；只在中间一半挑最长停顿，并列时优先靠近中点。 */
fun splitForRetry(lines: List<TranscriptLineEntity>): Pair<List<TranscriptLineEntity>, List<TranscriptLineEntity>>? {
    if (lines.size < MIN_SPLIT_LINES) return null
    val firstCut = (lines.size + 3) / 4
    val lastCut = lines.size * 3 / 4
    var prefixEnd = Long.MIN_VALUE
    var bestGap = Long.MIN_VALUE
    var cut = -1
    for (i in 1..lastCut) {
        prefixEnd = maxOf(prefixEnd, lines[i - 1].endAt)
        if (i < firstCut) continue
        val gap = lines[i].startAt - prefixEnd
        if (cut < 0 || gap > bestGap || gap == bestGap && abs(2 * i - lines.size) < abs(2 * cut - lines.size)) {
            bestGap = gap
            cut = i
        }
    }
    return lines.subList(0, cut) to lines.subList(cut, lines.size)
}
