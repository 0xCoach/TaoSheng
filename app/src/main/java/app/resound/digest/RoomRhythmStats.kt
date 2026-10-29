package app.resound.digest

import app.resound.data.MemoryDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray

class RoomRhythmStats(
    private val dao: MemoryDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RhythmStats {
    override fun baseline(date: LocalDate): Flow<RhythmBaseline?> {
        val start = date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.atStartOfDay(zone).toInstant().toEpochMilli()
        fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return combine(dao.rhythmLines(start, end), dao.events(start, end)) { lines, events ->
            val recorded = lines.groupBy { day(it.startAt) }
            if (recorded.size < 3) return@combine null
            val life = events.filter { it.scene != "媒体" }.groupBy { day(it.startAt) }
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
            RhythmBaseline(days.size, median(days.map { it.speechMinutes }), median(days.map { it.events }),
                median(days.map { it.people }), median(days.map { it.meetings }))
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1].toLong() + sorted[middle] + 1) / 2).toInt()
    }
}
