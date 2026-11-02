package app.resound.digest

import app.resound.data.CaptureDatabase
import app.resound.data.RhythmSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray

class RoomRhythmStats(
    private val db: CaptureDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RhythmStats {
    override fun baseline(date: LocalDate): Flow<RhythmBaseline?> {
        val start = date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return db.invalidationTracker.createFlow("transcript_lines", "memory_events")
            .conflate()
            .map { db.memory().rhythmSnapshot(start, end) }
            // 今天的删除也会触发表级失效；历史区间快照相同就不再匹配、分组和计算。
            .distinctUntilChanged()
            .map(::calculate)
            .distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    internal fun calculate(snapshot: RhythmSnapshot): RhythmBaseline? {
        fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val media = snapshot.media.sortedBy { it.startAt }
        val maxEnds = LongArray(media.size)
        media.forEachIndexed { i, span -> maxEnds[i] = maxOf(span.endAt, if (i == 0) Long.MIN_VALUE else maxEnds[i - 1]) }
        val recorded = snapshot.lines.filterNot { line ->
            // 找到 startAt < line.endAt 的媒体前缀，前缀最大 endAt > line.startAt 即重叠。
            // 保留严格边界和整句排除语义；长句、嵌套媒体也无需重复扫描事件表。
            var low = 0; var high = media.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (media[mid].startAt < line.endAt) low = mid + 1 else high = mid
            }
            low > 0 && maxEnds[low - 1] > line.startAt
        }.groupBy { day(it.startAt) }
        if (recorded.size < 3) return null
        val life = snapshot.events.groupBy { day(it.startAt) }
        val days = recorded.map { (date, sentences) ->
            val dailyEvents = life[date].orEmpty()
            val people = dailyEvents.flatMap { event ->
                val names = JSONArray(event.peopleJson)
                (0 until names.length()).map { names.optString(it).trim() }
            }.filter { it.isNotEmpty() && it != "我" }.toSet()
            RhythmBaseline(1,
                ((sentences.sumOf { (it.endAt - it.startAt).coerceAtLeast(0) } + 59_999) / 60_000).toInt(),
                dailyEvents.size, people.size, dailyEvents.count { it.scene == "会议" })
        }
        return RhythmBaseline(days.size, median(days.map { it.speechMinutes }), median(days.map { it.events }),
            median(days.map { it.people }), median(days.map { it.meetings }))
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1].toLong() + sorted[middle] + 1) / 2).toInt()
    }
}
