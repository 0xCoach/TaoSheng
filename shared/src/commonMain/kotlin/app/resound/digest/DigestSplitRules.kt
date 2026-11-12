package app.resound.digest

import kotlin.math.abs

const val MIN_SPLIT_LINES = 40
/** 被拒收时切得更细，只跳过出问题的那几句。 */
const val MIN_REJECT_SPLIT_LINES = 8

/** 原话已按时间排序；只在中间一半挑最长停顿，并列时优先靠近中点。 */
fun <T> splitDigestForRetry(lines: List<T>, minLines: Int = MIN_SPLIT_LINES, start: (T) -> Long, end: (T) -> Long): Pair<List<T>, List<T>>? {
    if (lines.size < maxOf(minLines, 2)) return null
    val firstCut = (lines.size + 3) / 4
    val lastCut = lines.size * 3 / 4
    var prefixEnd = Long.MIN_VALUE
    var bestGap = Long.MIN_VALUE
    var cut = -1
    for (i in 1..lastCut) {
        prefixEnd = maxOf(prefixEnd, end(lines[i - 1]))
        if (i < firstCut) continue
        val gap = start(lines[i]) - prefixEnd
        if (cut < 0 || gap > bestGap || gap == bestGap && abs(2 * i - lines.size) < abs(2 * cut - lines.size)) {
            bestGap = gap
            cut = i
        }
    }
    return lines.subList(0, cut) to lines.subList(cut, lines.size)
}
