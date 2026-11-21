@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.context

import app.resound.platform.ZoneHistory
import app.resound.screentime.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable enum class ThreadKind { Place, Event, App, Playback, Gap, Zone }
@Serializable data class ThreadEntry(val kind: ThreadKind, val start: Long, val end: Long, val text: String, val eventIds: List<Long> = emptyList(), val hourly: Boolean = false, val eventTitle: String? = null, val place: String? = null, val inUse: String? = null)
@Serializable data class DayOverview(val speechMillis: Long, val phoneMillis: Long?, val outsideMillis: Long?, val places: Int?, val events: Int)
@Serializable data class DayThread(val date: String, val overview: DayOverview, val entries: List<ThreadEntry>, val night: app.resound.night.NightThread? = null)
data class ThreadEvent(val id: Long, val start: Long, val end: Long, val title: String, val place: String? = null, val people: List<String> = emptyList(), val scene: String = "", val call: Boolean = false)
data class ThreadGap(val start: Long, val end: Long, val reason: String = "没有录音", val screen: Boolean = false, val qualified: Boolean = false)
data class ThreadInput(val date: LocalDate, val start: Long, val end: Long, val speechMillis: Long,
    val events: List<ThreadEvent> = emptyList(), val places: List<PlaceAt> = emptyList(), val apps: List<NamedAppSession> = emptyList(),
    val playback: List<DevicePlayback> = emptyList(), val gaps: List<ThreadGap> = emptyList(), val location: Boolean = true, val screen: Boolean = true, val night: app.resound.night.NightThread? = null)

object DayThreads {
    private const val MIN = 60_000L
    fun build(input: ThreadInput, zones: ZoneHistory): DayThread = with(input) {
        val rows = mutableListOf<ThreadEntry>()
        fun add(kind: ThreadKind, a: Long, b: Long, text: String, ids: List<Long> = emptyList(), hourly: Boolean = false) {
            if (a < end && (b > start || a == b && a >= start)) rows += ThreadEntry(kind, maxOf(start, a), minOf(end, b), text.replace('\n', ' '), ids, hourly)
        }
        val stays = if (location) places.map { it.copy(start = maxOf(start, it.start), end = minOf(end, it.end)) }.filter { it.end > it.start }.sortedBy { it.start } else emptyList()
        stays.forEach { add(ThreadKind.Place, it.start, it.end, "📍 ${it.name}") }
        stays.zipWithNext().filter { (a, b) -> a.end < b.start }.forEach { (a, b) -> add(ThreadKind.Place, a.end, b.start, "📍 ${a.name} → ${b.name}（路上）") }
        val sessions = if (screen) apps.map { it.copy(from = maxOf(start, it.from), until = minOf(end, it.until)) }.filter { it.until > it.from } else emptyList()
        val visible = events.filter { it.start < end && (it.end > start || it.start == it.end && it.start >= start) }.sortedBy { it.start }
        visible.forEach { e ->
            val place = if (location) e.place?.takeIf { it.isNotBlank() } ?: stays.maxByOrNull { minOf(it.end, e.end) - maxOf(it.start, e.start) }?.takeIf { minOf(it.end, e.end) > maxOf(it.start, e.start) }?.name else null
            val app = ScreenTimeView.inUse(sessions, e.start, e.end)
            val eventTitle = "${if (e.call) "📞" else "💬"} ${e.title}（E${e.id}）"
            add(ThreadKind.Event, e.start, e.end, "${if (e.call) "📞" else "💬"} ${e.title}（E${e.id}）" + (place?.let { " · $it" } ?: "") + (app?.let { " · 当时在用 ${it.name} ${it.minutes} 分" } ?: ""), listOf(e.id))
            rows[rows.lastIndex] = rows.last().copy(eventTitle = eventTitle, place = place, inUse = app?.let { "当时在用 ${it.name} ${it.minutes} 分" })
        }
        // 卡 200 门槛严格小于 2 分；按包合并，时长只累计实际使用段。
        val short = mutableListOf<NamedAppSession>()
        sessions.groupBy { it.pkg }.values.forEach { group ->
            val batches = mutableListOf<MutableList<NamedAppSession>>()
            group.sortedBy { it.from }.forEach { s ->
                val last = batches.lastOrNull()
                if (last != null && s.from - last.maxOf { it.until } < 2 * MIN) last += s else batches += mutableListOf(s)
            }
            batches.forEach { batch ->
                val a = batch.minOf { it.from }; val b = batch.maxOf { it.until }
                val used = batch.sumOf { it.until - it.from }
                if (used >= 5 * MIN) add(ThreadKind.App, a, b, "📱 ${batch.first().name} ${used / MIN} 分") else short += batch
            }
        }
        val hours = mutableMapOf<Long, MutableList<NamedAppSession>>()
        short.forEach { s ->
            var at = s.from
            while (at < s.until) {
                val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zones.zoneAt(at))
                val bucket = at - (local.minute * MIN + local.second * 1000L + local.nanosecond / 1_000_000)
                val until = minOf(s.until, bucket + 60 * MIN)
                hours.getOrPut(bucket) { mutableListOf() } += s.copy(from = at, until = until)
                at = until
            }
        }
        hours.forEach { (hour, parts) ->
            val top = ScreenTimeView.appsDuring(parts, hour, hour + 60 * MIN)
            if (top.isNotEmpty()) add(ThreadKind.App, maxOf(start, hour), minOf(end, hour + 60 * MIN), "📱 " + top.take(3).joinToString("、") { "${it.name} ${it.minutes} 分" } + if (top.size > 3) "等" else "", hourly = true)
        }
        if (screen) {
            val merged = mutableListOf<DevicePlayback>()
            playback.sortedBy { it.from }.forEach { p ->
                val last = merged.lastOrNull()
                if (last != null && last.pkg == p.pkg && last.title == p.title && last.subtitle == p.subtitle && p.from <= last.until)
                    merged[merged.lastIndex] = last.copy(until = maxOf(last.until, p.until)) else merged += p
            }
            merged.forEach { add(ThreadKind.Playback, it.from, it.until, "🎧 ${it.name}${it.title?.let { t -> "《$t》" }.orEmpty()}") }
        }
        gaps.filter { if (it.screen) screen else it.qualified || minOf(end, it.end) - maxOf(start, it.start) >= 30 * MIN }.forEach { add(ThreadKind.Gap, it.start, it.end, it.reason) }
        zones.zonesOn(date).drop(1).forEach { add(ThreadKind.Zone, it.from, it.from, "${zones.clock(it.from)} 起为 ${zones.label(it.from)}") }
        val ordered = rows.sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
        // 仅与实际上一行比较，不能跨其他线索省略地点。
        val deduped = ordered.mapIndexed { i, row ->
            val previous = ordered.getOrNull(i - 1)
            if (row.kind == ThreadKind.Event && previous?.kind == ThreadKind.Place) row.copy(text = row.text.replace(" · " + previous.text.removePrefix("📍 "), ""), place = row.place.takeUnless { it == previous.text.removePrefix("📍 ") }) else row
        }
        DayThread(date.toString(), DayOverview(speechMillis, if (screen) sessions.sumOf { it.until - it.from } else null,
            if (location) stays.filter { !it.home }.sumOf { it.end - it.start } + stays.zipWithNext().sumOf { (a, b) -> (b.start - a.end).coerceAtLeast(0) } else null,
            if (location) stays.map { it.name }.distinct().size else null, visible.size), deduped, night)
    }
    fun duration(ms: Long): String { val m = ms / MIN; return if (m >= 60) "${m / 60} 小时" + if (m % 60 > 0) " ${m % 60} 分" else "" else "$m 分" }
    fun markdown(thread: DayThread, zones: ZoneHistory): String = buildString {
        thread.night?.let { appendLine(it.markdown); appendLine() }
        appendLine("## 今天概览")
        with(thread.overview) { appendLine(listOfNotNull("说话 ${duration(speechMillis)}", phoneMillis?.let { "手机 ${duration(it)}" }, outsideMillis?.let { "外出 ${duration(it)}" }, places?.let { "去了 $it 个地方" }, "$events 件事").joinToString(" · ")) }
        appendLine("\n## 一天的线索\n本节时间为 ${zones.caption(LocalDate.parse(thread.date))}")
        thread.entries.forEach { e ->
            val time = if (e.hourly) zones.clock(e.start).take(2).toInt().toString() + " 点 · " else zones.clock(e.start) + (if (e.end > e.start) "–${zones.clock(e.end)}" else "") + " "
            appendLine("- " + (if (e.kind == ThreadKind.Zone) "" else time) + e.text)
        }
    }.trimEnd()
    fun restrict(thread: DayThread, location: Boolean, screen: Boolean): DayThread = thread.copy(
        overview = thread.overview.copy(phoneMillis = thread.overview.phoneMillis.takeIf { screen },
            outsideMillis = thread.overview.outsideMillis.takeIf { location }, places = thread.overview.places.takeIf { location }),
        entries = thread.entries.filter { (screen || it.kind !in setOf(ThreadKind.App, ThreadKind.Playback)) && (location || it.kind != ThreadKind.Place) }.map { e ->
            if (e.kind != ThreadKind.Event || e.eventTitle == null) e else {
                val place = e.place.takeIf { location }; val app = e.inUse.takeIf { screen }
                e.copy(text = listOfNotNull(e.eventTitle, place, app).joinToString(" · "), place = place, inUse = app)
            }
        })
    /** 封存后只清除消失的事件与可能泄露删除区间的缺口；App/播放保持原数据。 */
    fun redact(old: DayThread, liveIds: Set<Long>, removedRecordings: List<RecordingSpan> = emptyList()): DayThread {
        val deleted = old.entries.filter { it.kind == ThreadKind.Event && it.eventIds.any { id -> id !in liveIds } }
        val affected = removedRecordings + deleted.map { RecordingSpan(it.start, maxOf(it.start + 1, it.end)) }
        val entries = old.entries.filter { row -> row !in deleted && (row.kind != ThreadKind.Gap || affected.none { it.start < row.end && it.end > row.start }) }
        return old.copy(overview = old.overview.copy(events = entries.count { it.kind == ThreadKind.Event }), entries = entries)
    }
}

data class RecordingSpan(val start: Long, val end: Long)
data class RecordingState(val at: Long, val paused: Boolean, val reason: String = "已暂停录音")
/** 录音覆盖取并集；未知时段不猜测关机。暂停边界把长缺口分段，整体未录满 30 分才展示。 */
fun recordingGaps(start: Long, end: Long, recordings: List<RecordingSpan>, states: List<RecordingState>): List<ThreadGap> {
    val missing = mutableListOf<RecordingSpan>()
    var cursor = start
    recordings.sortedBy { it.start }.forEach { s ->
        if (s.start > cursor) missing += RecordingSpan(cursor, minOf(end, s.start))
        cursor = maxOf(cursor, minOf(end, s.end))
    }
    if (cursor < end) missing += RecordingSpan(cursor, end)
    return missing.filter { it.end - it.start >= 30 * 60_000L }.flatMap { gap ->
        val points = listOf(gap.start) + states.map { it.at }.filter { it > gap.start && it < gap.end }.distinct().sorted() + gap.end
        points.zipWithNext { a, b -> ThreadGap(a, b, states.lastOrNull { it.at <= a }?.takeIf { it.paused }?.reason ?: "没有录音", qualified = true) }
    }
}
