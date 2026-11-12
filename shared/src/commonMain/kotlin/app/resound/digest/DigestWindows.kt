package app.resound.digest

import app.resound.digest.core.TranscriptLineData
import app.resound.platform.NowMillis

/** 仅查询准备状态；时间单位 Unix 毫秒，编号为本地存储的不透明行/段编号。 */
interface DigestWindowStore {
    suspend fun voiceprintPending(segmentIds: List<Long>): Int
    suspend fun unfinishedSegments(start: Long, end: Long): Int
}
class DigestWindows(private val store: DigestWindowStore, private val now: NowMillis) {
    suspend fun closed(session: List<TranscriptLineData>, force: Boolean, gap: Long, voiceprintReady: Boolean): Boolean {
        if (voiceprintReady && store.voiceprintPending(session.map { it.segmentId }.distinct()) > 0) return false
        val start = session.first().startAt
        val end = session.maxOf { it.endAt }
        val quietLongEnough = force || now.now() - end >= gap
        return quietLongEnough && store.unfinishedSegments(start - gap, if (force) end else end + gap) == 0
    }
    /** 返回原列表前缀长度，避免丢失平台实体的额外字段。 */
    suspend fun settledPrefixSize(session: List<TranscriptLineData>, gap: Long, maxWait: Long, voiceprintReady: Boolean): Int {
        val start = session.first().startAt
        val settled = now.now() - gap
        if (settled - start < maxWait) return 0
        var cut = -1
        var bestGap = -1L
        var prefixEnd = Long.MIN_VALUE
        for (i in 0 until session.size - 1) {
            prefixEnd = maxOf(prefixEnd, session[i].endAt)
            if (prefixEnd > settled) break
            if (prefixEnd - start < gap) continue
            val pause = session[i + 1].startAt - prefixEnd
            if (pause >= bestGap) { bestGap = pause; cut = i }
        }
        if (cut < 0) return 0
        val prefix = session.subList(0, cut + 1)
        if (voiceprintReady && store.voiceprintPending(prefix.map { it.segmentId }.distinct()) > 0) return 0
        val end = prefix.maxOf { it.endAt }
        return if (store.unfinishedSegments(start - gap, end) == 0) cut + 1 else 0
    }
}
