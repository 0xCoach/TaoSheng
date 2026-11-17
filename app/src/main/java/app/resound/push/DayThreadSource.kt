package app.resound.push

import app.resound.AppGraph
import app.resound.context.*
import app.resound.data.CaptureDatabase
import app.resound.platform.ZoneHistory
import app.resound.screentime.ScreenTimeResult
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate

internal data class DayThreadSource(val input: ThreadInput, val segmentIds: Set<Long>, val recordingRanges: Map<Long, RecordingSpan> = emptyMap())

internal suspend fun loadDayThread(database: CaptureDatabase, date: LocalDate, snapshot: PushSnapshot,
    subscription: PushSubscription, zones: ZoneHistory, frozen: Boolean): DayThreadSource {
    val day = date.toKotlinLocalDate()
    val spans = zones.zonesOn(day)
    val start = spans.first().from; val end = spans.last().until
    val segments = database.capture().segments(start, end).first()
    val watchdog = if (frozen) emptyList() else database.capture().events(start - 86_400_000L, end).first()
    val gaps = recordingGaps(start, end, segments.map { RecordingSpan(it.startAt, it.endAt) },
        watchdog.filter { it.type in setOf("Paused", "Resumed", "ServiceStarted", "ServiceStopped") }.map {
            RecordingState(it.at, it.type == "Paused", if (it.detail?.startsWith("停录时段") == true) it.detail!!.substringBefore('，') else "已暂停录音")
        }).toMutableList()
    val screen = if (!frozen && subscription.screenTime) AppGraph.screenTimeReader?.sessions(start, end) ?: ScreenTimeResult.DISABLED else ScreenTimeResult.DISABLED
    gaps += screen.gaps.map { ThreadGap(it.from, it.until, "屏幕时间：${it.fix}", screen = true) }
    val calls = if (frozen) emptyList() else database.imports().day(start, end).first().filter { it.row.kind == "Call" }
    val callEvents = calls.flatMap { database.imports().lines(it.row.id).mapNotNull { line -> line.eventId } }.toSet()
    val events = snapshot.events.filter { !it.deleted && it.startAt < end && (it.endAt > start || it.startAt == it.endAt && it.startAt >= start) && (!subscription.excludeMedia || it.scene != "媒体") }
        .filter { RecordKind.Timeline in subscription.records || RecordKind.Starred in subscription.records && it.starred }
        .map { ThreadEvent(it.id, it.startAt, it.endAt, it.title, it.place, org.json.JSONArray(it.peopleJson).strings(), it.scene, it.id in callEvents) }
    val night = ((if(AppGraph.isNightReady) AppGraph.night else null) as? app.resound.night.AndroidNight)?.let { owner -> owner.store.dao.onDate(day.toString())?.let { owner.store.detail(it.id) }?.let { app.resound.night.NightThreads.build(it, owner.settings.value, zones) } }
    return DayThreadSource(ThreadInput(day, start, end,
        // 与首页 CaptureStatsQueries.dayStats 相同：每个录音段在当天的交集总和。
        segments.sumOf { (minOf(end, it.endAt) - maxOf(start, it.startAt)).coerceAtLeast(0) }, events,
        if (!frozen && subscription.footprint) AppGraph.situation.dayStays(start, end) else emptyList(),
        screen.sessions, if (!frozen && subscription.screenTime) screen.devicePlayback else emptyList(), gaps,
        subscription.footprint, subscription.screenTime, night), segments.map { it.id }.toSet(), segments.associate { it.id to RecordingSpan(it.startAt, it.endAt) })
}
