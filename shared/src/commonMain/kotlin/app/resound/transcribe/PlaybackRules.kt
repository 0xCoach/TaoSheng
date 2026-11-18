package app.resound.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

fun migratePlayback(skip: Boolean?, digest: Boolean?): PlaybackMode = when {
    skip == null && digest == null || digest == true -> PlaybackMode.Digest
    skip == false -> PlaybackMode.Transcribe
    else -> PlaybackMode.Skip
}

class PlaybackSettingsStore(
    initial: PlaybackPreferences,
    private val persist: (PlaybackPreferences) -> Unit,
) : PlaybackSettings {
    private val current = MutableStateFlow(initial)
    override val preferences = current.asStateFlow()

    override fun setDefault(mode: PlaybackMode) = save(current.value.copy(mode = mode))

    override fun setApp(pkg: String, rule: PlaybackOverride) {
        require(pkg.isNotBlank())
        val apps = current.value.apps
        save(current.value.copy(apps = if (rule == PlaybackOverride.Default) apps - pkg else apps + (pkg to rule)))
    }

    private fun save(value: PlaybackPreferences) {
        persist(value)
        current.value = value
    }
}

@kotlinx.serialization.Serializable
data class SpeakerInterval(val from: Long, val until: Long, val remembered: Boolean = false)

data class PlaybackDecision(val intervals: List<SpeakerInterval>, val millis: Long, val skip: Boolean, val digest: Int)

/** 区间为左闭右开、绝对毫秒；采样区间有序且不重叠，同一 App 的重叠记录只计一次。 */
fun decidePlayback(start: Long, end: Long, spans: List<SpeakerInterval>,
                   rows: List<app.resound.screentime.DevicePlayback>, prefs: PlaybackPreferences): PlaybackDecision {
    val durations = mutableMapOf<String, Long>()
    val kept = mutableListOf<SpeakerInterval>()
    var remembered = false
    for (span in spans) {
        val from = maxOf(start, span.from)
        val until = minOf(end, span.until)
        if (from >= until) continue
        val matches = rows.filter { it.from < until && it.until > from }
        val bounds = (listOf(from, until) + matches.flatMap { listOf(maxOf(from, it.from), minOf(until, it.until)) }).distinct().sorted()
        for ((a, b) in bounds.zipWithNext()) {
            val active = matches.filter { it.from < b && it.until > a }.map { it.pkg }.distinct()
            val audible = active.filter { prefs.rule(it) != PlaybackOverride.NotPlayback }
            if (active.isNotEmpty() && audible.isEmpty()) continue
            for (pkg in audible) durations[pkg] = (durations[pkg] ?: 0) + b - a
            remembered = remembered || span.remembered
            val last = kept.lastOrNull()
            if (last?.until == a) kept[kept.lastIndex] = last.copy(until = b)
            else kept += SpeakerInterval(a, b)
        }
    }
    val ms = kept.sumOf { it.until - it.from }
    val rule = if (remembered) PlaybackOverride.Digest else prefs.rule(durations.maxByOrNull { it.value }?.key)
    val majority = end > start && ms >= (end - start + 1) / 2
    return PlaybackDecision(kept, ms, majority && rule == PlaybackOverride.Skip,
        if (!majority) 0 else if (rule == PlaybackOverride.Digest) 2 else 1)
}

fun sentencePlayback(id: Long, from: Long, until: Long, spans: List<SpeakerInterval>?,
                     millis: Long, duration: Long, rows: List<app.resound.screentime.DevicePlayback>): SentencePlayback {
    if (millis <= 0) return SentencePlayback(id, false)
    val audible = spans?.filter { it.from < until && it.until > from }
    fun overlap(row: app.resound.screentime.DevicePlayback): Long {
        val a = maxOf(from, row.from)
        val b = minOf(until, row.until)
        return audible?.sumOf { (minOf(b, it.until) - maxOf(a, it.from)).coerceAtLeast(0) } ?: (b - a).coerceAtLeast(0)
    }
    val match = rows.maxByOrNull(::overlap)?.takeIf { overlap(it) > 0 }
    val playing = audible?.isNotEmpty() ?: (match != null || duration > 0 && millis >= duration)
    return SentencePlayback(id, playing, match?.name, match?.title)
}

@kotlinx.serialization.Serializable
data class PlaybackSnapshot(val spans: List<SpeakerInterval>, val preferences: PlaybackPreferences)
