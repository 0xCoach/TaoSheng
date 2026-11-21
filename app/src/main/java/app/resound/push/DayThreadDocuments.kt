package app.resound.push

import app.resound.context.*
import app.resound.platform.ZoneHistory
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.time.LocalDate

internal const val DAY_THREAD_MARKER = "<!-- resound:day-thread -->"
internal const val DAY_THREAD_END = "<!-- /resound:day-thread -->"
internal fun dayThreadSection(content: String): String? {
    val start = content.indexOf(DAY_THREAD_MARKER)
    val end = content.indexOf(DAY_THREAD_END, maxOf(0, start))
    return if (start < 0 || end < 0) null else content.substring(start, end + DAY_THREAD_END.length)
}
internal fun replaceDayThread(content: String, section: String?): String {
    val old = dayThreadSection(content)
    val clean = replaceScreenTimeSection(if (old == null) content else content.replace(old, ""), null).trim()
    if (section == null) return clean + "\n"
    val headerEnd = if (clean.startsWith("---\n")) clean.indexOf("\n---\n").takeIf { it >= 0 }?.let { clean.indexOf("\n\n", it + 5) } ?: -1 else -1
    return if (headerEnd >= 0) clean.substring(0, headerEnd) + "\n\n" + section + "\n\n" + clean.substring(headerEnd).trim() + "\n"
        else section + "\n\n" + clean + "\n"
}
internal fun dayThreadPayload(thread: DayThread, zones: ZoneHistory): JSONObject {
    val base = Json.encodeToJsonElement(thread).jsonObject
    val entries = thread.entries.map { entry -> JsonObject(Json.encodeToJsonElement(entry).jsonObject.toMutableMap().apply {
        put("start", JsonPrimitive(zones.iso(entry.start)))
        put("end", JsonPrimitive(zones.iso(entry.end)))
    }) }
    return JSONObject(JsonObject(base + ("entries" to JsonArray(entries)) + ("type" to JsonPrimitive("day_context"))).toString())
}

internal data class PreparedDay(val date: LocalDate, val thread: DayThread, val section: String, val hash: String, val segments: Set<Long>, val recordingRanges: Map<Long, RecordingSpan> = emptyMap())
internal suspend fun prepareDayThreads(
    files: MutableMap<String, String>, state: PushSyncState, channel: PushChannel, raw: PushSnapshot,
    today: LocalDate, hour: Int, zones: ZoneHistory,
    source: suspend (LocalDate, PushSnapshot, PushSubscription, ZoneHistory, Boolean) -> DayThreadSource,
): List<PreparedDay> {
    val saved = state.json.optJSONObject("dayThreads") ?: JSONObject()
    val eligible = DayThreadPolicy.eligible(channel.kind, channel.subscription)
    val dates = if (eligible) (DayThreadPolicy.dates(today.toKotlinLocalDate(), hour, !state.json.optBoolean("dayThreadInitialized"))
        .map { LocalDate.parse(it.toString()) } + saved.keys().asSequence().map(LocalDate::parse).toList()).distinct() else emptyList()
    val prepared = mutableListOf<PreparedDay>()
    for (date in dates) {
        if (date >= today) continue
        val previous = saved.optJSONObject(date.toString())
        val frozen = previous != null && !DayThreadPolicy.mutable(date.toKotlinLocalDate(), today.toKotlinLocalDate())
        val input = source(date, raw, channel.subscription, zones, frozen)
        val old = previous?.optString("thread")?.takeIf { it.isNotEmpty() }?.let { Json.decodeFromString<DayThread>(it) }
        val thread = if (frozen && old != null) {
            val live = input.input.events.map { it.id }.toSet()
            val removed = old.entries.any { it.kind == ThreadKind.Event && it.eventIds.any { id -> id !in live } } ||
                previous.getJSONArray("segments").let { ids -> (0 until ids.length()).any { ids.getLong(it) !in input.segmentIds } }
            val ranges = previous.optJSONObject("recordingRanges")
            val removedRanges = ranges?.keys()?.asSequence()?.filter { it.toLong() !in input.segmentIds }?.map { key ->
                val range = ranges.getJSONArray(key); RecordingSpan(range.getLong(0), range.getLong(1))
            }?.toList().orEmpty()
            val retained = if (removed) DayThreads.redact(old, live, removedRanges).let { it.copy(overview = it.overview.copy(speechMillis = input.input.speechMillis)) } else old
            // 关掉订阅时连历史整类也移除，不能在封存内容中泄露。
            DayThreads.restrict(retained, channel.subscription.footprint, channel.subscription.screenTime).copy(night = input.input.night)
        } else DayThreads.build(input.input, zones)
        val section = "$DAY_THREAD_MARKER\n本文时间为 ${zones.caption(date.toKotlinLocalDate())}\n\n${DayThreads.markdown(thread, zones)}\n$DAY_THREAD_END"
        val path = MemoryFolder.dailyPath(date)
        val base = files[path] ?: state.templates.optString(path).takeIf { it.isNotBlank() }?.let {
            // 没有当前正文时不能恢复已删除事件的旧正文。
            MemoryFolder.card("每日记录", "$date", "一天的线索", "$date · 一天的线索", 0, "")
        } ?: MemoryFolder.card("每日记录", "$date", "一天的线索", "$date · 一天的线索", 0, "")
        files[path] = replaceDayThread(base, section)
        prepared += PreparedDay(date, thread, section, sha256(dayThreadPayload(thread, zones).toString()), input.segmentIds, input.recordingRanges)
    }
    // 普通事件重写也保留已定稿节，同时清理旧版屏幕时间块。
    for (path in files.keys.toList().filter { it.startsWith("daily/") }) {
        val existing = files.getValue(path)
        val oldSection = if (eligible) dayThreadSection(existing) ?: dayThreadSection(state.templates.optString(path)) else null
        files[path] = replaceDayThread(existing, oldSection)
    }
    return prepared
}
internal fun acknowledgeDay(state: PushSyncState, day: PreparedDay, at: Long) {
    val saved = state.json.optJSONObject("dayThreads") ?: JSONObject().also { state.json.put("dayThreads", it) }
    val old = saved.optJSONObject(day.date.toString())
    if (old?.optString("hash") == day.hash) return
    saved.put(day.date.toString(), JSONObject().put("writtenAt", at).put("hash", day.hash)
        .put("thread", Json.encodeToString(day.thread)).put("segments", org.json.JSONArray(day.segments.toList()))
        .put("recordingRanges", JSONObject().also { ranges -> day.recordingRanges.forEach { (id, span) -> ranges.put(id.toString(), org.json.JSONArray(listOf(span.start, span.end))) } }))
    state.save()
}
