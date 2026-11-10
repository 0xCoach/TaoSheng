package app.resound.context

import androidx.room.InvalidationTracker
import app.resound.data.CaptureDatabase
import app.resound.envsound.EnvSoundRow
import app.resound.envsound.EnvTag
import app.resound.envsound.SpecialSound
import app.resound.envsound.names
import app.resound.location.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 按当地自然日读取；缓存最近 4 天，表更新后失效。查询和聚类均在 IO 线程。 */
class LocalSituationLookup internal constructor(
    private val available: () -> Boolean,
    private val points: suspend (Long, Long) -> List<LocationPoint>,
    private val cachedPlace: suspend (Double, Double) -> Place?,
    private val sounds: suspend (Long, Long) -> List<EnvSoundRow>,
    private val placeRevision: () -> Long = { 0 },
    private val soundRevision: () -> Long = { 0 },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** 定位记录的最早、最晚时间；没有记录为 null。用来收窄“不限日期”的查询，避免逐日遍历无限范围 */
    private val pointBounds: suspend () -> Pair<Long, Long>? = { null },
) : SituationLookup {
    private val places = DayCache<List<PlaceAt>>()
    private val environments = DayCache<List<EnvSoundRow>>()

    override suspend fun placeDuring(start: Long, end: Long): PlaceAt? = withContext(Dispatchers.IO) {
        if (end <= start) return@withContext null
        try {
            if (!available()) return@withContext null
            var best: PlaceAt? = null
            var longest = 0L
            for (day in days(start, end)) for (stay in dayPlaces(day)) {
                val duration = overlap(stay.start, stay.end, start, end)
                if (duration > longest) { best = stay; longest = duration }
            }
            // 用浮点比较避免毫秒取整把不足 1/3 的停留误收进来。
            best.takeIf { longest.toDouble() >= (end - start).toDouble() / 3 }
        } catch (_: SecurityException) { null }
    }

    override suspend fun staysAt(placeName: String, from: Long, to: Long): List<PlaceAt> = withContext(Dispatchers.IO) {
        if (to <= from || placeName.isBlank()) return@withContext emptyList()
        try {
            if (!available()) return@withContext emptyList()
            // 问问不限日期时会传很宽的范围：收窄到有定位记录的那些天
            val (lo, hi) = pointBounds()?.let { (a, b) -> maxOf(from, a) to minOf(to, b + 1) } ?: (from to to)
            if (hi <= lo) return@withContext emptyList()
            val q = placeName.trim()
            buildList {
                for (day in days(lo, hi)) for (stay in dayPlaces(day)) {
                    // “家”对应被认作家的地方；其余按名字包含
                    val match = if (q == "家") stay.home else stay.name.contains(q, ignoreCase = true)
                    if (match && overlap(stay.start, stay.end, from, to) > 0)
                        add(stay.copy(start = maxOf(from, stay.start), end = minOf(to, stay.end)))
                }
            }.sortedBy { it.start }
        } catch (_: SecurityException) { emptyList() }
    }

    override suspend fun envDuring(start: Long, end: Long, limit: Int): List<String> = withContext(Dispatchers.IO) {
        if (end <= start || limit <= 0) return@withContext emptyList()
        val durations = linkedMapOf<String, Long>()
        for (day in days(start, end)) {
            val rows = environments.get(day, soundRevision()) { sounds(day.start, day.end) }
            for (row in rows) {
                if (row.reportedWrong) continue
                // 跨午夜的录音每天只累计本日的交集，不重复计时。
                val duration = overlap(row.startAt, row.endAt, maxOf(start, day.start), minOf(end, day.end))
                if (duration <= 0) continue
                val labels = names<EnvTag>(row.tags).map { it.title } + names<SpecialSound>(row.sounds).map { it.title }
                for (label in labels.distinct()) durations[label] = durations.getOrDefault(label, 0) + duration
            }
        }
        durations.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    private suspend fun dayPlaces(day: Day): List<PlaceAt> = places.get(day, placeRevision()) {
        val track = Track.day(points(day.start, day.end))
        val groups = Timeline.groups(track.stays, day.start)
        // 用现有 resolver 的手动名优先规则；绝不调用 resolve（会联网）。
        val names = groups.associate { (it.lat to it.lng) to cachedPlace(it.lat, it.lng) }
        Timeline.groups(track.stays, day.start) { lat, lng -> names[lat to lng]?.home == true }
            .flatMap { group ->
                val place = names[group.lat to group.lng]
                val name = place?.name?.takeIf { it.isNotBlank() }
                    ?: place?.address?.takeIf { it.isNotBlank() } ?: "某地"
                group.stays.map { PlaceAt(name, group.home, it.start, it.end) }
            }.sortedBy { it.start }
    }

    private data class Day(val date: LocalDate, val zone: ZoneId) {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun days(start: Long, end: Long): Sequence<Day> {
        val timezone = zone()
        val first = Instant.ofEpochMilli(start).atZone(timezone).toLocalDate()
        return generateSequence(Day(first, timezone)) { Day(it.date.plusDays(1), timezone) }.takeWhile { it.start < end }
    }

    private class DayCache<T> {
        private val lock = Mutex()
        private val entries = LinkedHashMap<Day, Pair<Long, T>>(4, .75f, true)
        suspend fun get(day: Day, revision: Long, read: suspend () -> T): T = lock.withLock {
            entries[day]?.takeIf { it.first == revision }?.let { return@withLock it.second }
            read().also {
                entries[day] = revision to it
                if (entries.size > 4) entries.remove(entries.keys.first())
            }
        }
    }

    companion object {
        fun create(database: CaptureDatabase, location: LocationRepository): LocalSituationLookup {
            val places = AtomicLong()
            val sounds = AtomicLong()
            database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("location_points", "location_places", "env_sounds") {
                override fun onInvalidated(tables: Set<String>) {
                    if (tables.any { it == "location_points" || it == "location_places" }) places.incrementAndGet()
                    if ("env_sounds" in tables) sounds.incrementAndGet()
                }
            })
            return LocalSituationLookup(location.settings::wanted, database.location()::betweenNow,
                location.places::cached, database.envSound()::between, places::get, sounds::get,
                pointBounds = {
                    database.query("SELECT MIN(at), MAX(at) FROM location_points", emptyArray()).use {
                        if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0) to it.getLong(1)
                    }
                })
        }

        private fun overlap(a: Long, b: Long, c: Long, d: Long) = maxOf(0L, minOf(b, d) - maxOf(a, c))
    }
}
