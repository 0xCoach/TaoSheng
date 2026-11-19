package app.resound.push

import app.resound.diagnostics.FailureNotes

import app.resound.digest.AppointmentReminders

import android.content.SharedPreferences
import app.resound.platform.NowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*
import java.util.concurrent.ConcurrentHashMap

/** 每通道串行；失败不推进指纹/触发游标，Worker 自身指数退避。 */
internal class SubscriptionRuntime(
    private val prefs: SharedPreferences, private val directory: File,
    private val channelById: (Long) -> PushChannel?,
    private val resolve: (Long) -> Pair<PushChannel, Map<String, String>>?, private val transport: PushTransport,
    private val schedule: (Long, Long, Boolean) -> Unit,
    private val log: (PushChannel, String, Result<Unit>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    var dayThreadSource: (suspend (LocalDate, PushSnapshot, PushSubscription, app.resound.platform.ZoneHistory, Boolean) -> DayThreadSource)? = null
    var nightWeekSource: suspend () -> List<app.resound.night.WeeklyReport> = ::currentNightWeeks
    var reminderSettings: () -> AppointmentReminders = { AppointmentReminders() }
    var automaticSource: (suspend (LocalDate?, Boolean) -> PushSnapshot)? = null
    var source: suspend (LocalDate?, Boolean) -> PushSnapshot = { _, _ -> throw PushFailure("记忆数据尚未就绪") }
    var screenTimeMarkdown: suspend (LocalDate) -> String? = { null }
    private val gates = ConcurrentHashMap<Long, Mutex>()
    private val states = ConcurrentHashMap<Long, MutableStateFlow<PushChannelStatus>>()
    private val queued = ConcurrentHashMap<Long, Long>()
    private fun meta(id: Long): JSONObject = try { JSONObject(prefs.getString("subscription-$id", "{}")!!) }
    catch (caught: org.json.JSONException) {
        FailureNotes.record("push/SubscriptionRuntime.kt:36", caught)

        File(directory, "$id.json").delete()
        check(prefs.edit().putString("subscription-$id", "{}").putBoolean("state-reset-$id", true).commit())
        JSONObject()
    }
    @Synchronized private fun change(id: Long, block: (JSONObject) -> Unit) {
        val j = meta(id); block(j)
        check(prefs.edit().putString("subscription-$id", j.toString()).commit())
    }
    fun status(id: Long): StateFlow<PushChannelStatus> = states.getOrPut(id) {
        val j = meta(id)
        MutableStateFlow(PushChannelStatus(j.optLong("lastPushAt").takeIf { it > 0 }, j.optString("lastResult").takeIf { it.isNotEmpty() }, j.optString("lastError").takeIf { it.isNotEmpty() }, false))
    }
    private fun update(id: Long, running: Boolean, result: Result<String>? = null) {
        status(id)
        if (result != null) change(id) { j ->
            if (result.isSuccess) j.put("lastPushAt", now()).put("lastResult", result.getOrThrow()).remove("lastError")
            else j.put("lastError", result.exceptionOrNull()?.message)
        }
        val j = meta(id)
        states.getValue(id).value = PushChannelStatus(j.optLong("lastPushAt").takeIf { it > 0 }, j.optString("lastResult").takeIf { it.isNotEmpty() }, j.optString(
            "lastError").takeIf { it.isNotEmpty() }, running)
    }
    fun messageResult(id: Long, result: Result<String>) = update(id, status(id).value.running, result)
    fun forget(id: Long) {
        File(directory, "$id.json").delete()
        prefs.edit().remove("subscription-$id").commit()
        queued.remove(id); states.remove(id); gates.remove(id)
    }
    fun reminderSchedule(channel: PushChannel, data: PushSnapshot) {
        change(channel.id) { j ->
            j.remove("reminderAt")
            if (channel.subscription.reminders) SubscriptionReminders.candidates(data, channel.subscription, j.optJSONObject("reminded") ?: JSONObject(), reminderSettings(), zone(), now())
                .minOfOrNull { it.at }?.let { j.put("reminderAt", maxOf(now(), it)) }
        }
        plan(channel.id)
    }
    fun reset(id: Long) {
        // 改目的地不能把旧目录的拥有权带到新目录。旧远端文件保留，不跨账号删除。
        File(directory, "$id.json").delete()
        change(id) { it.remove("batchAt"); it.remove("changeAt"); it.remove("lastPushAt") }
    }
    private fun hasRecords(s: PushSubscription) = SubscriptionPlan.hasRecords(s)
    private fun JSONObject.times() = (SubscriptionPlan.deadlines + "lastRecordsAt" + "lastSummaryAt").filter { has(it) }.associateWith { optLong(it) }
    private fun JSONObject.applyTimes(edits: Map<String, Long?>) {
        edits.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }
    fun configure(channel: PushChannel, fresh: Boolean) {
        if (fresh) queued.remove(channel.id)
        change(channel.id) { j -> j.applyTimes(SubscriptionPlan.configure(channel.subscription, fresh, j.times(), NowMillis(now)) { zone().id }) }
        plan(channel.id)
    }
    fun changed(channel: PushChannel, summary: Boolean = false) {
        if (!channel.enabled) return
        change(channel.id) { j -> j.applyTimes(SubscriptionPlan.changed(channel.subscription, summary, j.times(), { status(channel.id).value.running }, NowMillis(now))) }
        plan(channel.id)
    }
    private fun deadline(id: Long): Long? = SubscriptionPlan.deadline(meta(id).times())
    private fun plan(id: Long, continuation: Boolean = false) {
        if (channelById(id)?.enabled != true || status(id).value.running) return
        deadline(id)?.let { at ->
            if (!continuation && queued[id]?.let { it <= at } == true) return
            runCatching { schedule(id, at, continuation); queued[id] = at }.onFailure {
                FailureNotes.record("push/SubscriptionRuntime.kt:98", it)
            }.onFailure { update(id, false, Result.failure(PushFailure("调度保存失败，请重试"))) } }
    }
    suspend fun scheduled(id: Long): Boolean {
        queued.remove(id)
        if (channelById(id)?.enabled != true) return false
        if ((deadline(id) ?: Long.MAX_VALUE) > now()) { plan(id, true); return false }
        return run(id, manual = false).isFailure.also { if (it) queued[id] = 0L }
    }
    suspend fun run(id: Long, rebuild: Boolean = false, manual: Boolean = true, dayFinal: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        gates.getOrPut(id) { Mutex() }.withLock {
          PushTrace.run { trace ->
            val channel = channelById(id) ?: return@run Result.failure(PushFailure("通道不存在，请重新打开"))
            if (!manual && !channel.enabled) return@run Result.success("通道已停用")
            update(id, true)
            if (prefs.getBoolean("state-reset-$id", false)) {
                trace.notices += "订阅状态损坏，已重置该通道，下次全量重推"
                prefs.edit().remove("state-reset-$id").commit()
            }
            val started = now()
            val due = meta(id)
            var more = false
            val result = try {
                val (_, values) = resolve(id) ?: throw PushFailure("通道不存在")
                val s = channel.subscription
                val trigger = s.trigger
                val fullCheckAt = s.fullCheckAt
                if (!hasRecords(s) && !s.summaries && !s.reminders) throw PushFailure("请先选择要推送的内容")
                val state = PushSyncState(File(directory, "$id.json"))
                val identity = sha256(channel.kind.name + canonical(JSONObject(values.filterKeys { it in setOf("account", "url", "folder", "databaseId", "chatId", "to", "cloudKind", "s3") })))
                if (state.json.has("destination") && state.json.getString("destination") != identity) {
                    state.files.keys().asSequence().toList().forEach { state.files.remove(it) }
                    state.records.keys().asSequence().toList().forEach { state.records.remove(it) }
                    state.templates.keys().asSequence().toList().forEach { state.templates.remove(it) }
                    state.json.remove("dayThreads"); state.json.remove("dayThreadInitialized"); state.json.remove("since"); state.json.remove("temporary"); state.json.remove("rebuilding"); state.json.remove("directoriesReady")
                }
                state.json.put("destination", identity); state.save()
                val today = day(started, zone())
                val since = state.since(s, today, rebuild)
                if (rebuild && channel.kind == PushKind.MarkdownFolder) { state.json.put("rebuilding", true); state.save() }
                val summaryOnly = !dayFinal && !manual && !state.json.optBoolean("rebuilding") && (due.optBoolean("summaryBatch") || !due.has("batchAt") && due.optLong("summaryAt")
                    .let { it > 0 && it <= started } &&
                    listOf("scheduleAt", "fullAt", "changeAt", "batchAt").none { due.optLong(it).let { at -> at > 0 && at <= started } })
                val raw = (if (manual) source else automaticSource ?: source)(since, s.lines != LinesMode.Off)
                val reminderDue = !dayFinal && !manual && due.optLong("reminderAt").let { it > 0 && it <= started }
                val reminders = if (reminderDue) SubscriptionReminders.candidates(raw, s, due.optJSONObject("reminded") ?: JSONObject(), reminderSettings(), zone(),
                    started).filter { it.at <= started } else emptyList()
                val reminderOnly = !dayFinal && reminderDue && listOf("scheduleAt", "fullAt", "changeAt", "summaryAt", "batchAt").none { due.optLong(it).let { at -> at > 0 && at <= started } }
                if (reminders.isNotEmpty()) {
                    sendReminders(channel, values, reminders, state, started)
                    val sentDaily = state.templates.optString(MemoryFolder.dailyPath(today))
                    val reachedDocument = channel.kind !in setOf(PushKind.MarkdownFolder, PushKind.Notion) ||
                        reminders.all { it.text in sentDaily }
                    if (reachedDocument) change(id) { j ->
                        val sent = j.optJSONObject("reminded") ?: JSONObject()
                        reminders.flatMap { it.keys }.forEach { sent.put(it, started) }
                        // 保留已发身份；切换状态或开关后也不能重发同一时间版本。
                        j.put("reminded", sent)
                    }
                }
                val data = raw.select(s, since, today, zone())
                val generator = MemoryFolder(today, zone())
                val files = (if (summaryOnly) summaryFiles(data, s, state, generator) else generator.generate(data, s)).toMutableMap()
                // 封存日仍使用完整来源清理正文，不因订阅滚动范围丢掉旧线索或恢复旧事件。
                if (state.json.optJSONObject("dayThreads")?.length()?.let { it > 0 } == true) {
                    val archive = generator.generate(raw.select(s.copy(rangeDays = null), null, today, zone()), s)
                    state.json.getJSONObject("dayThreads").keys().asSequence().forEach { date ->
                        val path = MemoryFolder.dailyPath(LocalDate.parse(date))
                        if (path !in files && path in archive) files[path] = archive.getValue(path)
                    }
                }
                val historical = app.resound.platform.HistoricalZones.history()
                val preparedDays = dayThreadSource?.let { loader -> prepareDayThreads(files, state, channel, raw, today,
                    Instant.ofEpochMilli(started).atZone(zone()).hour, historical, loader) }.orEmpty()
                if (dayThreadSource == null) applyScreenTime(files, s, today)
                val nightWeeks = if(channel.kind in setOf(PushKind.MarkdownFolder,PushKind.Webhook)) nightWeekSource() else emptyList()
                val weekFiles = nightWeeks.associate { app.resound.night.NightWeeks.path(it.weekStart) to it.markdown }
                val oldWeeks = state.json.optJSONObject("nightWeeks") ?: JSONObject()
                val removedWeeks = oldWeeks.keys().asSequence().filter { old -> nightWeeks.none { it.weekStart.toString()==old } }.toList()
                weekFiles.forEach { (path,body) -> files[path]=body }
                removedWeeks.forEach { start -> files[app.resound.night.NightWeeks.path(kotlinx.datetime.LocalDate.parse(start))]="" }

                if (dayFinal) {
                    val finalized = preparedDays.map { MemoryFolder.dailyPath(it.date) }.toSet() + weekFiles.keys + removedWeeks.map { app.resound.night.NightWeeks.path(kotlinx.datetime.LocalDate.parse(it)) }
                    val output = state.templates.keys().asSequence().associateWith { state.templates.getString(it) }.toMutableMap()
                    files.filterKeys { it in finalized || it == "README.md" }.forEach { (path, body) -> output[path] = body }
                    files.clear(); files.putAll(output)
                }
                val current = RecordDelta.records(data, s)
                if (!reminderOnly && !dayFinal && nightWeeks.isEmpty() && removedWeeks.isEmpty()) trace.requireContent(if (summaryOnly) current.keys.count { it.startsWith("card:") || it.startsWith("review:") } else current.size)
                val full = !s.incremental || rebuild || due.optLong("fullAt").let { it > 0 && it <= started }
                val description = when {
                    dayFinal && channel.kind == PushKind.Webhook -> "已检查每日线索"
                    reminderOnly -> "推了 ${reminders.size} 条提醒"
                    channel.kind == PushKind.MarkdownFolder -> {
                        // 摘要即时推只刷新已有记录中的摘要，不提前发送尚未到触发点的整理结果。
                        val outgoing = files
                        val batch = transport.syncFolder(values, state, outgoing, s.layout == FolderLayout.ByCategory, started, if (reminders.isNotEmpty()) 18 else 20)
                        more = batch.more; batch.description
                    }
                    channel.kind == PushKind.Webhook -> {
                        val selected = if (summaryOnly) current.filterKeys { it.startsWith("card:") || it.startsWith("review:") } else current
                        val previous = if (summaryOnly) JSONObject().also { j -> state.records.keys().asSequence().filter { it.startsWith("card:") || it.startsWith(
                            "review:") }.forEach { j.put(it, state.records.get(it)) } } else state.records
                        if (!full || summaryOnly) {
                            val delta = RecordDelta.changes(selected, previous)
                            val count = delta.getJSONArray("changes").length()
                            if (count > 0) transport.send(channel.kind, channel.format, values, PushMessage("整理结果变化", "", PushTopic.DailyMemory, null, started, memory = delta)).getOrThrow()
                            if (summaryOnly) {
                                previous.keys().asSequence().toList().filter { it !in trace.skipped }.forEach { state.records.remove(it) }
                                selected.forEach { (key, value) -> state.records.put(key, sha256(canonical(value))) }; state.save()
                            } else RecordDelta.acknowledge(current, state)
                            "推了 $count 条变化"
                        } else {
                            sendDocuments(channel, values, data, s, files, state, false, started).also { RecordDelta.acknowledge(current, state) }
                        }
                    }
                    channel.kind in setOf(PushKind.Notion, PushKind.Telegram, PushKind.Email) -> sendDocuments(channel, values, if (dayFinal) PushSnapshot() else data, s, files, state, channel
                        .kind == PushKind.Notion && (dayFinal || s.incremental && !rebuild), started)
                    else -> throw PushFailure("这个通道只支持摘要和提醒")
                }
                if(channel.kind==PushKind.Webhook) {
                    for(report in nightWeeks) {
                        val key=report.weekStart.toString();val payload=nightWeekPayload(report);val hash=sha256(payload.toString())
                        if(oldWeeks.optString(key)!=hash) {
                            transport.send(channel.kind,PushFormat.Json,values,PushMessage("夜里周报 · $key","",PushTopic.DailyMemory,null,started,memory=payload)).getOrThrow()
                            oldWeeks.put(key,hash);state.json.put("nightWeeks",oldWeeks);state.save()
                        }
                    }
                    for(key in removedWeeks) {
                        transport.send(channel.kind,PushFormat.Json,values,PushMessage("夜里周报已移除","",PushTopic.DailyMemory,null,started,memory=JSONObject().put("type","night_week").put("weekStart",key).put("deleted",true))).getOrThrow()
                        oldWeeks.remove(key);state.save()
                    }
                } else if(channel.kind==PushKind.MarkdownFolder) {
                    for(report in nightWeeks)if(state.templates.optString(app.resound.night.NightWeeks.path(report.weekStart))==report.markdown)oldWeeks.put(report.weekStart.toString(),sha256(report.markdown))
                    removedWeeks.filter {state.templates.optString(app.resound.night.NightWeeks.path(kotlinx.datetime.LocalDate.parse(it))).isEmpty()}.forEach {oldWeeks.remove(it)}
                    state.json.put("nightWeeks",oldWeeks);state.save()
                }
                for (prepared in preparedDays) {
                    val path = MemoryFolder.dailyPath(prepared.date)
                    if (channel.kind == PushKind.Webhook) {
                        val previousHash = state.json.optJSONObject("dayThreads")?.optJSONObject(prepared.date.toString())?.optString("hash")
                        if (previousHash != prepared.hash) {
                            transport.send(channel.kind, PushFormat.Json, values, PushMessage("一天的线索 · ${prepared.date}", "", PushTopic.DailyMemory, null, started,
                                memory = dayThreadPayload(prepared.thread, historical))).getOrThrow()
                            acknowledgeDay(state, prepared, started)
                        }
                    } else if (dayThreadSection(state.templates.optString(path)) == prepared.section) acknowledgeDay(state, prepared, started)
                }
                if (preparedDays.isNotEmpty() && preparedDays.all { state.json.optJSONObject("dayThreads")?.optJSONObject(it.date.toString())?.optString("hash") == it.hash }) {
                    state.json.put("dayThreadInitialized", true); state.save()
                }
                if (!dayFinal) change(id) { j ->
                    j.applyTimes(SubscriptionPlan.completed(s, j.times(), due.times(), started, more, summaryOnly, reminderOnly, NowMillis(now)) { zone().id })
                    if (more) j.put("summaryBatch", summaryOnly) else j.remove("summaryBatch")
                }
                val summaryCounts = state.json.optJSONObject("summaryCounts") ?: JSONObject()
                data.cards.map { LocalDate.ofEpochDay(it.date) }.plus(data.reviews.filter { it.type == "daily" }.map { LocalDate.parse(it.startDate) }).distinct().forEach { date ->
                    val path = MemoryFolder.dailyPath(date)
                    if (state.templates.optString(path) == files[path]) summaryCounts.put(path, data.cards.count { LocalDate.ofEpochDay(it.date) == date } + data.reviews
                        .count { it.type == "daily" && it.startDate == date.toString() })
                }
                state.json.put("summaryCounts", summaryCounts); state.save()
                if (!dayFinal) reminderSchedule(channel, raw)
                if (dayFinal && more) throw PushFailure("每日线索尚有文件待写，稍后继续")
                Result.success(trace.success(description + if (more) "，余下文件已排队" else ""))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (trace.exception.isEmpty()) trace.exception = e.javaClass.simpleName; Result.failure(PushFailure(if (e is PushFailure) e
                .message ?: "同步失败" else "同步失败，请检查网络和通道配置")) }
            finally { update(id, false) }
            PushDiagnostics.record(prefs, channel, PushTopic.DailyMemory, result, trace)
            update(id, false, result)
            log(channel, (if (channel.kind == PushKind.MarkdownFolder) "同步文件夹 · " else "推送订阅 · ") + (result.getOrNull() ?: "失败"), result.map { Unit })
            if (result.isSuccess && !dayFinal) { if (manual) queued.remove(id); plan(id, !manual) }
            else if (manual) { change(id) { it.put("batchAt", now() + 30_000) }; plan(id) }
            result
          }
        }
    }
    private suspend fun sendReminders(channel: PushChannel, values: Map<String, String>, reminders: List<SubscriptionReminder>, state: PushSyncState, at: Long) {
        val body = reminders.joinToString("\n") { "- ${it.text}" }
        if (channel.kind !in setOf(PushKind.MarkdownFolder, PushKind.Notion)) {
            for (reminder in reminders) {
                transport.send(channel.kind, channel.format, values,
                    PushMessage("待办提醒", reminder.text, PushTopic.TodoReminder, null, at)).getOrThrow()
                change(channel.id) { j ->
                    val sent = j.optJSONObject("reminded") ?: JSONObject()
                    reminder.keys.forEach { sent.put(it, at) }
                    j.put("reminded", sent)
                }
            }
            return
        }
        val date = day(at, zone()); val path = MemoryFolder.dailyPath(date)
        val files = state.templates.keys().asSequence().associateWith { state.templates.getString(it) }.toMutableMap()
        val base = files[path] ?: MemoryFolder.card("每日记录", "$date", "提醒", "$date · 提醒", reminders.size, "")
        files[path] = base
        // 提醒仅追加当天正文，已定稿的历史线索原样保留。
        files.keys.toList().filter { it.startsWith("daily/") }.forEach { files[it] = replaceScreenTimeSection(files.getValue(it), null) }
        files[path] = files.getValue(path).substringBefore("<!-- resound:reminders -->").trimEnd() +
            "\n\n<!-- resound:reminders -->\n## 提醒\n$body\n"
        if (channel.kind == PushKind.MarkdownFolder) {
            if ("README.md" !in files) files["README.md"] = MemoryFolder(date, zone()).generate(PushSnapshot(), channel.subscription).getValue("README.md")
            // 这里只改当天一份文件，其余模板原样保留。
            transport.syncFolder(values, state, files, channel.subscription.layout == FolderLayout.ByCategory, at)
        } else sendDocuments(channel, values, PushSnapshot(), channel.subscription, files, state, true, at)
    }
    private suspend fun summaryFiles(data: PushSnapshot, s: PushSubscription, state: PushSyncState, generator: MemoryFolder): Map<String, String> {
        val fresh = generator.generate(data.copy(events = emptyList(), items = emptyList(), lines = emptyList()), s.copy(records = emptySet(), lines = LinesMode.Off))
        val old = state.json.optJSONObject("summaryCounts") ?: JSONObject()
        val dates = data.cards.map { LocalDate.ofEpochDay(it.date).toString() } + data.reviews.filter { it.type == "daily" }.map { it.startDate }
        return SummaryTemplates.merge(fresh, state.templates.keys().asSequence().associateWith { state.templates.getString(it) },
            old.keys().asSequence().associateWith { old.optInt(it) }, dates.groupingBy { it }.eachCount())
    }
    private suspend fun sendDocuments(channel: PushChannel, values: Map<String, String>, data: PushSnapshot, s: PushSubscription,
        files: Map<String, String>, state: PushSyncState, incremental: Boolean, at: Long): String {
        val documents = files.filterKeys { it.startsWith("daily/") }.toSortedMap()
        data.reviews.filter { it.type != "daily" }.forEach { review ->
            val date = LocalDate.parse(review.startDate)
            val path = MemoryFolder.dailyPath(date)
            val week = files[MemoryFolder.weekPath(date)] ?: return@forEach
            documents[path] = (documents[path] ?: MemoryFolder.card("每日记录", "$date", "每周回顾", "$date · 每周回顾", 1, "")) + "\n" + week
        }
        if (channel.kind == PushKind.Webhook) state.files.keys().asSequence().toList().filter { PushTrace.current()?.skipped.isNullOrEmpty() && it.startsWith("daily/") &&
            it !in documents }.forEach { path ->
            documents[path] = MemoryFolder.card("每日记录", path.substringAfterLast('/').take(10), "已删除或移出范围", "当天已无记录", 0, "")
        }
        var count = 0
        for ((path, markdown) in documents) {
            val date = path.substringAfterLast('/').take(10)
            val transcript = files[MemoryFolder.transcriptPath(LocalDate.parse(date))].orEmpty()
            val body = markdown + if (transcript.isNotEmpty()) "\n$transcript" else ""
            val hash = sha256(body)
            if (incremental && state.files.optJSONObject(path)?.optString("sourceHash") == hash) continue
            val records = RecordDelta.records(data, s).filterValues { record ->
                when {
                    record.has("startDate") -> record.getString("startDate") == date
                    record.has("date") -> record.getString("date") == date
                    else -> day(record.optLong("start", record.optLong("at")), zone()).toString() == date
                }
            }
            val payload = JSONObject().put("type", "daily_memory").put("version", 1).put("app", "resound").put("date", date).put("generatedAt", at)
                .put("utcOffset", app.resound.platform.HistoricalZones.history().let { h -> h.offset(h.zonesOn(kotlinx.datetime.LocalDate.parse(date)).first().from) })
                .put("markdown", body.replace(MemoryFolder.UPDATED, MemoryFolder.updatedText(at)))
                .put("data", JSONObject().apply {
                    listOf("event" to "events", "item" to "items", "card" to "cards", "review" to "reviews", "line" to "lines").forEach { (kind, key) ->
                        if (kind != "line" || s.lines != LinesMode.Off) put(key, JSONArray(records.filterKeys { it.startsWith("$kind:") }.values.toList()))
                    }
                    put("review", records.filterKeys { it.startsWith("review:") }.values.firstOrNull() ?: JSONObject.NULL)
                })
            transport.send(channel.kind, channel.format, values, PushMessage("每日记忆文档 · $date", "$date 的记忆文档", PushTopic.DailyMemory, null, at, memory = payload)).getOrThrow()
            state.files.put(path, JSONObject().put("sourceHash", hash))
            state.templates.put(path, files[path] ?: markdown)
            files.filterKeys { it.startsWith("weekly/") }.forEach { (key, value) -> state.templates.put(key, value) }
            if (transcript.isNotEmpty()) state.templates.put(MemoryFolder.transcriptPath(LocalDate.parse(date)), transcript)
            if (channel.kind == PushKind.Webhook && path !in files) { state.files.remove(path); state.templates.remove(path) }
            state.save(); count++
        }
        if (channel.kind == PushKind.Notion) state.files.keys().asSequence().toList().filter { PushTrace.current()?.skipped.isNullOrEmpty() && it.startsWith("daily/") &&
            it !in documents }.forEach { path ->
            transport.deleteNotionDay(values, path.substringAfterLast('/').take(10))
            state.files.remove(path); state.templates.remove(path); state.save(); count++
        }
        return "更新 $count 天的文档"
    }
    private suspend fun applyScreenTime(files: MutableMap<String, String>, s: PushSubscription, today: LocalDate) {
        files.keys.toList().filter { it.startsWith("daily/") }.forEach { path ->
            files[path] = replaceScreenTimeSection(files.getValue(path), null)
        }
    }
}

internal const val SCREEN_TIME_MARKER = "<!-- resound:screen-time -->"
internal const val SCREEN_TIME_END = "<!-- /resound:screen-time -->"

internal fun replaceScreenTimeSection(content: String, markdown: String?): String {
    val start = content.indexOf(SCREEN_TIME_MARKER)
    val prefix: String
    val suffix: String
    if (start < 0) {
        prefix = content.trimEnd()
        suffix = ""
    } else {
        prefix = content.substring(0, start).trimEnd()
        val after = start + SCREEN_TIME_MARKER.length
        val end = content.indexOf(SCREEN_TIME_END, after)
        suffix = when {
            end >= 0 -> content.substring(end + SCREEN_TIME_END.length).trimStart()
            else -> {
                val rest = content.substring(after)
                val next = rest.indexOf("<!-- resound:")
                if (next >= 0) rest.substring(next).trimStart() else ""
            }
        }
    }
    val body = markdown?.trimEnd()?.takeIf { it.isNotEmpty() }
    val core = if (body == null) prefix else prefix + "\n\n$SCREEN_TIME_MARKER\n## 屏幕时间\n$body\n$SCREEN_TIME_END"
    val joined = if (suffix.isEmpty()) core else core.trimEnd() + "\n\n" + suffix.trimEnd()
    return if (joined.isEmpty()) joined else joined + "\n"
}
