package app.resound.envsound

import app.resound.data.MemoryEventEntity
import app.resound.data.SegmentEntity
import java.time.*

internal inline fun <reified T: Enum<T>> names(value: String): Set<T> = value.split(',').mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()
internal fun Iterable<Enum<*>>.encoded() = joinToString(",") { it.name }
internal fun EnvSoundRow.view() = SegmentSound(segmentId, startAt, endAt, SegmentVerdict.valueOf(verdict), reason,
    names(tags), names(sounds), restored, reportedWrong, java.io.File(path).isFile)
internal fun mergeSounds(events: List<MemoryEventEntity>, rows: List<EnvSoundRow>): Map<Long, EventSounds> = events.mapNotNull { e ->
    val matches = rows.filter { it.startAt < e.endAt && it.endAt > e.startAt }
    if(matches.isEmpty()) null else e.id to EventSounds(e.id, matches.flatMap { names<EnvTag>(it.tags) }.toSet(),
        matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(), matches.any { it.verdict == "Media" && !it.reportedWrong })
}.toMap()
internal fun hourlySounds(date: LocalDate, segments: List<SegmentEntity>, rows: List<EnvSoundRow>, zone: ZoneId): List<HourSound> {
    val start = date.atStartOfDay(zone); val end = date.plusDays(1).atStartOfDay(zone)
    val result = mutableListOf<HourSound>(); var at = start
    while(at < end) {
        val from = at.toInstant().toEpochMilli(); val to = at.plusHours(1).toInstant().toEpochMilli()
        val clips = segments.filter { it.sourceId != "voice-input" && it.startAt < to && it.endAt > from }
        if(clips.isNotEmpty()) {
            val matches = rows.filter { r -> clips.any { it.id == r.segmentId } }
            val tags = matches.flatMap { names<EnvTag>(it.tags) }.groupingBy { it }.eachCount()
            val millis = clips.filter { s -> matches.none { it.segmentId == s.id && it.skipped && !it.restored } }.sumOf { minOf(to,it.endAt)-maxOf(from,it.startAt) }
            result += HourSound(at.hour, tags.maxByOrNull { it.value }?.key, matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(), (millis/60000).toInt())
        }
        at = at.plusHours(1)
    }
    return result
}
