package app.resound.night

import kotlinx.datetime.*
import kotlinx.serialization.Serializable

@Serializable enum class SleepWindowKind { Night, Nap, Custom }
@Serializable data class SleepWindow(
    val id: String, val kind: SleepWindowKind, val name: String, val enabled: Boolean,
    val auto: Boolean, val startMinute: Int, val endMinute: Int,
) {
    val recordKind get() = if (kind == SleepWindowKind.Nap) "nap" else "night"
    fun contains(minute: Int) = NightRules.inWindow(minute, startMinute, endMinute)
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun bounds(wakeDate: LocalDate, zone: TimeZone): NightSpan {
        val startDate = if (endMinute <= startMinute) wakeDate.minus(1, DateTimeUnit.DAY) else wakeDate
        fun at(date: LocalDate, minute: Int) = date.atTime(minute / 60, minute % 60).toInstant(zone).toEpochMilliseconds()
        return NightSpan(at(startDate, startMinute), at(wakeDate, endMinute))
    }
    fun wakeDate(date: LocalDate, minute: Int) =
        if (endMinute <= startMinute && minute >= startMinute) date.plus(1, DateTimeUnit.DAY) else date
}

object SleepWindows {
    fun legacy(start: Int, end: Int) = listOf(
        SleepWindow("night", SleepWindowKind.Night, "夜里", true, start == 1260 && end == 720, start, end),
        SleepWindow("nap", SleepWindowKind.Nap, "午睡", false, false, 720, 900),
    )
    val fallback = legacy(1260, 720).first().copy(startMinute = 1200)

    /** 两个钟点在午夜两侧时仍取相邻值。把一天在最大空档处切开。 */
    private fun medianMinute(minutes: List<Int>): Int {
        val sorted = minutes.sorted()
        val cut = sorted.indices.maxBy { i ->
            (if (i == sorted.lastIndex) sorted.first() + 1440 else sorted[i + 1]) - sorted[i]
        }
        val first = sorted[(cut + 1) % sorted.size]
        return NightRules.median(sorted.map { (if (it < first) it + 1440 else it).toLong() }).toInt() % 1440
    }

    fun automatic(samples: List<Pair<Int, Int>>): SleepWindow {
        if (samples.size < 7) return fallback
        val onset = medianMinute(samples.map { it.first })
        val wake = medianMinute(samples.map { it.second })
        fun round(minute: Int) = (minute + 15) / 30 * 30
        val start = round(onset + 1440 - 120)
        val sleep = (wake - onset + 1440) % 1440
        val end = round(onset + 1440 + sleep + 240)
        val duration = (end - start).coerceIn(480, 1080)
        return fallback.copy(startMinute = start % 1440, endMinute = (start + duration) % 1440)
    }

    fun resolved(windows: List<SleepWindow>, auto: SleepWindow) = windows.map {
        if (it.auto) it.copy(startMinute = auto.startMinute, endMinute = auto.endMinute) else it
    }

    /** 保存之后作息仍可能变化。遇到新交叠，以用户手动开启的时段为准。 */
    fun current(settings: NightSettings, auto: SleepWindow, minute: Int): SleepWindow? {
        if (!settings.enabled) return null
        return resolved(settings.windows, auto).filter { it.enabled && it.contains(minute) }
            .minByOrNull { if (it.auto) 1 else 0 }
    }

    fun error(windows: List<SleepWindow>, auto: SleepWindow): String? {
        if (windows.count { it.kind == SleepWindowKind.Night } != 1 ||
            windows.count { it.kind == SleepWindowKind.Nap } != 1 ||
            windows.count { it.kind == SleepWindowKind.Custom } > 3) return "请保留夜里和午睡，自定义时段最多三条"
        if (windows.distinctBy { it.id }.size != windows.size || windows.any { it.id.isBlank() || it.name.isBlank() })
            return "时段需要名称和唯一编号"
        if (windows.any { it.auto && it.kind != SleepWindowKind.Night }) return "只有夜里可以使用自动时段"
        if (windows.any { it.startMinute !in 0..1439 || it.endMinute !in 0..1439 }) return "时段分钟超出范围"
        val active = resolved(windows, auto).filter { it.enabled }
        for ((index, a) in active.withIndex()) for (b in active.drop(index + 1)) {
            if (a.contains(b.startMinute) || b.contains(a.startMinute)) return "“${a.name}”与“${b.name}”时段重叠"
        }
        return null
    }
}

object NapRules {
    private const val TEN_MINUTES = 600_000L

    fun wake(session: NightSession): Long? {
        val since = session.confirmedAt ?: return null
        val firstUse = session.signals.filter { it.start >= since && it.kind in setOf("pickup", "unlock") }.minOfOrNull { it.start }
        var remaining = 30_000L
        for (span in NightRules.merge(session.signals.filter { it.kind == "screen" && it.end > since }
            .map { NightSpan(maxOf(since, it.start), it.end) })) {
            if (span.millis > remaining) return minOf(firstUse ?: Long.MAX_VALUE, span.start + remaining)
            remaining -= span.millis
        }
        return firstUse
    }

    fun transition(inside: Boolean, session: NightSession?, input: NightInput): NightTransition = with(input) {
        if (session == null) {
            val resting = screenOffAt != null && stillSince != null && at - maxOf(screenOffAt, stillSince) >= TEN_MINUTES
            return if (inside && resting && !pickedUp && (lastVoiceAt == null || at - lastVoiceAt >= TEN_MINUTES))
                NightTransition.Enter else NightTransition.Wait
        }
        if (session.confirmedAt == null) {
            if (!inside || screenOnAt != null || pickedUp || session.signals.any {
                    it.start >= session.provisionalAt && it.kind in setOf("unlock", "screen", "pickup")
                }) return NightTransition.Cancel
            if (at - session.provisionalAt >= TEN_MINUTES) return NightTransition.Confirm
        } else if (!inside || wake(session) != null) {
            return if ((wake(session) ?: at) - session.provisionalAt < TEN_MINUTES) NightTransition.Cancel else NightTransition.Finish
        }
        NightTransition.Continue
    }
}
