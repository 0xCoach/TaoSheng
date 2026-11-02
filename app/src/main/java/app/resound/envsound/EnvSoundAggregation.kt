package app.resound.envsound

import app.resound.data.MemoryEventEntity
import app.resound.data.SegmentEntity
import java.time.*

internal inline fun <reified T: Enum<T>> names(value: String): Set<T> = value.split(',').mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()
internal fun Iterable<Enum<*>>.encoded() = joinToString(",") { it.name }
internal fun EnvSoundRow.view() = SegmentSound(segmentId, startAt, endAt, SegmentVerdict.valueOf(verdict), reason,
    names(tags), names(sounds), restored, reportedWrong, java.io.File(path).isFile)
internal fun mergeSounds(events: List<MemoryEventEntity>, rows: List<EnvSoundRow>): Map<Long, EventSounds> {
    val ordered = rows.sortedBy { it.startAt }
    val active = mutableListOf<EnvSoundRow>()
    var next = 0
    return buildMap {
        for (e in events.sortedBy { it.startAt }) {
            while (next < ordered.size && ordered[next].startAt < e.endAt) active += ordered[next++]
            active.removeAll { it.endAt <= e.startAt }
            val matches = active.filter { it.startAt < e.endAt }
            if (matches.isNotEmpty()) put(e.id, EventSounds(e.id,
                matches.flatMap { names<EnvTag>(it.tags) }.toSet(),
                matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(),
                matches.any { it.verdict == "Media" && !it.reportedWrong }))
        }
    }
}
internal fun hourlySounds(date: LocalDate, segments: List<SegmentEntity>, rows: List<EnvSoundRow>, zone: ZoneId): List<HourSound> {
    val start = date.atStartOfDay(zone); val end = date.plusDays(1).atStartOfDay(zone)
    val bySegment = rows.associateBy { it.segmentId }
    val result = mutableListOf<HourSound>(); var at = start
    while(at < end) {
        val from = at.toInstant().toEpochMilli(); val to = at.plusHours(1).toInstant().toEpochMilli()
        val clips = segments.filter { it.sourceId != "voice-input" && it.startAt < to && it.endAt > from }
        if(clips.isNotEmpty()) {
            val matches = clips.mapNotNull { bySegment[it.id] }
            // 环境按带这个标签的录音段落在这小时里的时长算，不按段数，也不是“出现过就算一整小时”
            val tagMs = matches.flatMap { r -> names<EnvTag>(r.tags).map { it to minOf(to,r.endAt)-maxOf(from,r.startAt) } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val millis = clips.filter { s -> bySegment[s.id]?.let { !it.skipped || it.restored } ?: true }.sumOf { minOf(to,it.endAt)-maxOf(from,it.startAt) }
            result += HourSound(at.hour, tagMs.maxByOrNull { it.value }?.key, matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(), (millis/60000).toInt(), tagMs)
        }
        at = at.plusHours(1)
    }
    return result
}

/** 节奏卡环境图标的门槛：这小时说话至少 5 分钟，某种环境至少 3 分钟、并占说话时间的四分之一 */
internal const val ENV_SPEECH_MIN_SEC = 5 * 60
internal const val ENV_TAG_MIN_MS = 3 * 60_000L

/** 一小时里够得上画图标的环境；够不上为 null */
internal fun hourTag(speechSec: Int, hour: HourSound?): Pair<EnvTag, Long>? {
    if(hour == null || speechSec < ENV_SPEECH_MIN_SEC) return null
    return hour.tagMs.filter { it.value >= ENV_TAG_MIN_MS && it.value * 4 >= speechSec * 1000L }.maxByOrNull { it.value }?.toPair()
}

/** 0～23 点按小时取环境，连着几个小时相同的合成一段 */
internal fun envSpans(speechSecByHour: List<Int>, hours: Map<Int, HourSound>): List<EnvSpan> {
    val out = mutableListOf<EnvSpan>()
    for(h in 0..23) {
        val (tag, ms) = hourTag(speechSecByHour.getOrElse(h) { 0 }, hours[h]) ?: continue
        val last = out.lastOrNull()
        if(last != null && last.tag == tag && last.to == h - 1) out[out.lastIndex] = last.copy(to = h, ms = last.ms + ms)
        else out += EnvSpan(tag, h, h, ms)
    }
    return out
}
