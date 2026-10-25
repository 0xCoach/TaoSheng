package app.resound.push

import app.resound.appDisplayName
import android.content.SharedPreferences
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
    var reminderSettings: () -> app.resound.digest.AppointmentReminders = { app.resound.digest.AppointmentReminders() }
    var source: suspend (LocalDate?, Boolean) -> PushSnapshot = { _, _ -> throw PushFailure("记忆数据尚未就绪") }
    private val gates = ConcurrentHashMap<Long, Mutex>()
    private val states = ConcurrentHashMap<Long, MutableStateFlow<PushChannelStatus>>()
    private val queued = ConcurrentHashMap<Long, Long>()
    private fun meta(id: Long) = JSONObject(prefs.getString("subscription-$id", "{}")!!)
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
        states.getValue(id).value = PushChannelStatus(j.optLong("lastPushAt").takeIf { it > 0 }, j.optString("lastResult").takeIf { it.isNotEmpty() }, j.optString("lastError").takeIf { it.isNotEmpty() }, running)
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
    private fun hasRecords(s: PushSubscription) = s.records.isNotEmpty() || s.lines != LinesMode.Off
    fun configure(channel: PushChannel, fresh: Boolean) {
        if (fresh) queued.remove(channel.id)
        val s = channel.subscription
        change(channel.id) { j ->
            if (fresh || !j.has("scheduleAt")) {
                j.remove("scheduleAt")
                if (hasRecords(s) && s.trigger is RecordTrigger.Schedule) j.put("scheduleAt", Subscriptions.nextSchedule(s.trigger, Instant.ofEpochMilli(now()).atZone(zone())))
            }
            if (fresh || !j.has("fullAt")) {
                j.remove("fullAt")
                if (hasRecords(s) && s.incremental && s.fullCheckAt != null && s.trigger != RecordTrigger.Manual)
                    j.put("fullAt", Subscriptions.nextSchedule(RecordTrigger.Schedule(listOf(s.fullCheckAt)), Instant.ofEpochMilli(now()).atZone(zone())))
            }
            if (fresh) {
                j.remove("changeAt"); j.remove("summaryAt")
                if (s.reminders) j.put("reminderAt", now()) else j.remove("reminderAt")
                // 首次补齐服从触发方式；Manual 不安排任何自动任务。
                if (hasRecords(s)) Subscriptions.changeAt(s.trigger, j.optLong("lastRecordsAt").takeIf { it > 0 }, now())?.let { j.put("changeAt", it) }
            }
        }
        plan(channel.id)
    }
    fun changed(channel: PushChannel, summary: Boolean = false) {
        if (!channel.enabled) return
        change(channel.id) { j ->
            if (summary && channel.subscription.summaries) j.put("summaryAt", now())
            else if (hasRecords(channel.subscription)) Subscriptions.changeAt(channel.subscription.trigger,
                j.optLong("lastRecordsAt").takeIf { it > 0 }, now())?.let { at ->
                if (status(channel.id).value.running || !j.has("changeAt")) j.put("changeAt", if (status(channel.id).value.running) maxOf(now() + 1, at) else at)
            }
        }
        plan(channel.id)
    }
    private fun deadline(id: Long): Long? {
        val j = meta(id)
        return listOf("scheduleAt", "fullAt", "changeAt", "summaryAt", "batchAt", "reminderAt").mapNotNull { key -> j.optLong(key).takeIf { it > 0 } }.minOrNull()
    }
    private fun plan(id: Long, continuation: Boolean = false) {
        if (channelById(id)?.enabled != true || status(id).value.running) return
        deadline(id)?.let { at ->
            if (!continuation && queued[id]?.let { it <= at } == true) return
            runCatching { schedule(id, at, continuation); queued[id] = at }.onFailure { update(id, false, Result.failure(PushFailure("调度保存失败，请重试"))) } }
    }
    suspend fun scheduled(id: Long): Boolean {
        queued.remove(id)
        if (channelById(id)?.enabled != true) return false
        if ((deadline(id) ?: Long.MAX_VALUE) > now()) { plan(id, true); return false }
        return run(id, manual = false).isFailure.also { if (it) queued[id] = 0L }
    }
    suspend fun run(id: Long, rebuild: Boolean = false, manual: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        gates.getOrPut(id) { Mutex() }.withLock {
            val channel = channelById(id) ?: return@withLock Result.failure(PushFailure("通道不存在，请重新打开"))
            if (!manual && !channel.enabled) return@withLock Result.success("通道已停用")
            update(id, true)
            val started = now()
            val due = meta(id)
            var more = false
            val result = try {
                val (_, values) = resolve(id) ?: throw PushFailure("通道不存在")
                val s = channel.subscription
                if (!hasRecords(s) && !s.summaries && !s.reminders) throw PushFailure("请先选择要推送的内容")
                val state = PushSyncState(File(directory, "$id.json"))
                val identity = sha256(channel.kind.name + canonical(JSONObject(values.filterKeys { it in setOf("account", "url", "folder", "databaseId", "chatId", "to") })))
                if (state.json.has("destination") && state.json.getString("destination") != identity) {
                    state.files.keys().asSequence().toList().forEach { state.files.remove(it) }
                    state.records.keys().asSequence().toList().forEach { state.records.remove(it) }
                    state.templates.keys().asSequence().toList().forEach { state.templates.remove(it) }
                    state.json.remove("since"); state.json.remove("temporary"); state.json.remove("rebuilding")
                }
                state.json.put("destination", identity); state.save()
                val today = day(started, zone())
                val since = state.since(s, today, rebuild)
                if (rebuild && channel.kind == PushKind.MarkdownFolder) { state.json.put("rebuilding", true); state.save() }
                val summaryOnly = !manual && !state.json.optBoolean("rebuilding") && (due.optBoolean("summaryBatch") || !due.has("batchAt") && due.optLong("summaryAt").let { it > 0 && it <= started } &&
                    listOf("scheduleAt", "fullAt", "changeAt", "batchAt").none { due.optLong(it).let { at -> at > 0 && at <= started } })
                val raw = source(since, s.lines != LinesMode.Off)
                val reminderDue = !manual && due.optLong("reminderAt").let { it > 0 && it <= started }
                val reminders = if (reminderDue) SubscriptionReminders.candidates(raw, s, due.optJSONObject("reminded") ?: JSONObject(), reminderSettings(), zone(), started).filter { it.at <= started } else emptyList()
                val reminderOnly = reminderDue && listOf("scheduleAt", "fullAt", "changeAt", "summaryAt", "batchAt").none { due.optLong(it).let { at -> at > 0 && at <= started } }
                if (reminders.isNotEmpty()) {
                    sendReminders(channel, values, reminders, state, started)
                    change(id) { j ->
                        val sent = j.optJSONObject("reminded") ?: JSONObject()
                        reminders.flatMap { it.keys }.forEach { sent.put(it, started) }
                        // 保留已发身份；切换状态或开关后也不能重发同一时间版本。
                        j.put("reminded", sent)
                    }
                }
                val data = raw.select(s, since, today, zone())
                val generator = MemoryFolder(today, zone())
                val files = if (summaryOnly) summaryFiles(data, s, state, generator) else generator.generate(data, s)
                val current = RecordDelta.records(data, s)
                val full = !s.incremental || rebuild || due.optLong("fullAt").let { it > 0 && it <= started }
                val description = when {
                    reminderOnly -> "推了 ${reminders.size} 条提醒"
                    channel.kind == PushKind.MarkdownFolder -> {
                        // 摘要即时推只刷新已有记录中的摘要，不提前发送尚未到触发点的整理结果。
                        val outgoing = files
                        val batch = transport.syncFolder(values, state, outgoing, s.layout == FolderLayout.ByCategory, started, if (reminders.isNotEmpty()) 18 else 20)
                        more = batch.more; batch.description
                    }
                    channel.kind == PushKind.Webhook -> {
                        val selected = if (summaryOnly) current.filterKeys { it.startsWith("card:") || it.startsWith("review:") } else current
                        val previous = if (summaryOnly) JSONObject().also { j -> state.records.keys().asSequence().filter { it.startsWith("card:") || it.startsWith("review:") }.forEach { j.put(it, state.records.get(it)) } } else state.records
                        if (!full || summaryOnly) {
                            val delta = RecordDelta.changes(selected, previous)
                            val count = delta.getJSONArray("changes").length()
                            if (count > 0) transport.send(channel.kind, channel.format, values, PushMessage("整理结果变化", "", PushTopic.DailyMemory, null, started, memory = delta)).getOrThrow()
                            if (summaryOnly) {
                                previous.keys().asSequence().toList().forEach { state.records.remove(it) }
                                selected.forEach { (key, value) -> state.records.put(key, sha256(canonical(value))) }; state.save()
                            } else RecordDelta.acknowledge(current, state)
                            "推了 $count 条变化"
                        } else {
                            sendDocuments(channel, values, data, s, files, state, false, started).also { RecordDelta.acknowledge(current, state) }
                        }
                    }
                    channel.kind in setOf(PushKind.Notion, PushKind.Telegram, PushKind.Email) -> sendDocuments(channel, values, data, s, files, state, channel.kind == PushKind.Notion && s.incremental && !rebuild, started)
                    else -> throw PushFailure("这个通道只支持摘要和提醒")
                }
                change(id) { j ->
                    // 仅清除启动时见到的触发，保留运行期间新来的变更。
                    listOf("changeAt", "summaryAt", "batchAt").forEach { key -> if (j.optLong(key) == due.optLong(key)) j.remove(key) }
                    if (more) j.put("batchAt", now() + 30_000).put("summaryBatch", summaryOnly) else j.remove("summaryBatch")
                    if (!summaryOnly && !reminderOnly) {
                        j.put("lastRecordsAt", started)
                        if (j.has("changeAt") && s.trigger is RecordTrigger.OnChange) j.put("changeAt", maxOf(j.getLong("changeAt"), started + s.trigger.minGapMinutes * 60_000L))
                    }
                    if (due.optLong("scheduleAt").let { it > 0 && it <= started } && s.trigger is RecordTrigger.Schedule)
                        j.put("scheduleAt", Subscriptions.nextSchedule(s.trigger, Instant.ofEpochMilli(now()).atZone(zone())))
                    if (due.optLong("fullAt").let { it > 0 && it <= started } && s.fullCheckAt != null)
                        j.put("fullAt", Subscriptions.nextSchedule(RecordTrigger.Schedule(listOf(s.fullCheckAt)), Instant.ofEpochMilli(now()).atZone(zone())))
                }
                val summaryCounts = state.json.optJSONObject("summaryCounts") ?: JSONObject()
                data.cards.map { LocalDate.ofEpochDay(it.date) }.plus(data.reviews.filter { it.type == "daily" }.map { LocalDate.parse(it.startDate) }).distinct().forEach { date ->
                    val path = MemoryFolder.dailyPath(date)
                    if (state.templates.optString(path) == files[path]) summaryCounts.put(path, data.cards.count { LocalDate.ofEpochDay(it.date) == date } + data.reviews.count { it.type == "daily" && it.startDate == date.toString() })
                }
                state.json.put("summaryCounts", summaryCounts); state.save()
                reminderSchedule(channel, raw)
                Result.success(description + if (more) "，余下文件已排队" else "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(PushFailure(if (e is PushFailure) e.message ?: "同步失败" else "同步失败，请检查网络和通道配置")) }
            finally { update(id, false) }
            update(id, false, result)
            log(channel, (if (channel.kind == PushKind.MarkdownFolder) "同步文件夹 · " else "推送订阅 · ") + (result.getOrNull() ?: "失败"), result.map { Unit })
            if (result.isSuccess) { if (manual) queued.remove(id); plan(id, !manual) }
            else if (manual) { change(id) { it.put("batchAt", now() + 30_000) }; plan(id) }
            result
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
        files[path] = base.substringBefore("<!-- resound:reminders -->").trimEnd() + "\n\n<!-- resound:reminders -->\n## 提醒\n$body\n"
        if (channel.kind == PushKind.MarkdownFolder) {
            if ("README.md" !in files) files["README.md"] = MemoryFolder(date, zone()).generate(PushSnapshot(), channel.subscription).getValue("README.md")
            // 这里只改当天一份文件，其余模板原样保留。
            transport.syncFolder(values, state, files, channel.subscription.layout == FolderLayout.ByCategory, at)
        } else sendDocuments(channel, values, PushSnapshot(), channel.subscription, files, state, true, at)
    }
    private fun summaryFiles(data: PushSnapshot, s: PushSubscription, state: PushSyncState, generator: MemoryFolder): Map<String, String> {
        val fresh = generator.generate(data.copy(events = emptyList(), items = emptyList(), lines = emptyList()), s.copy(records = emptySet(), lines = LinesMode.Off))
        val result = state.templates.keys().asSequence().associateWith { state.templates.getString(it) }.toMutableMap()
        fun section(text: String, name: String): String = text.substringAfter("<!-- resound:$name:start -->", "").substringBefore("<!-- resound:$name:end -->")
        fresh.filterKeys { it.startsWith("daily/") || it.startsWith("weekly/") }.forEach { (path, content) ->
            var merged = result[path]
            if (merged != null && path.startsWith("daily/")) {
                for (name in listOf("reviews", "cards")) {
                    val start = "<!-- resound:$name:start -->"; val end = "<!-- resound:$name:end -->"
                    merged = merged!!.substringBefore(start) + start + section(content, name) + end + merged.substringAfter(end, "")
                }
                val date = path.substringAfterLast('/').take(10)
                val count = Regex("(?m)^条数: ([0-9]+)$").find(merged!!)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val oldSummaryCount = state.json.optJSONObject("summaryCounts")?.optInt(path) ?: 0
                val summaryCount = data.cards.count { LocalDate.ofEpochDay(it.date).toString() == date } + data.reviews.count { it.type == "daily" && it.startDate == date }
                result[path] = merged.replace(Regex("(?m)^条数: [0-9]+$"), "条数: ${(count - oldSummaryCount + summaryCount).coerceAtLeast(0)}")
            } else result[path] = content
        }
        if ("README.md" !in result) result["README.md"] = fresh.getValue("README.md")
        return result
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
        if (channel.kind == PushKind.Webhook) state.files.keys().asSequence().toList().filter { it.startsWith("daily/") && it !in documents }.forEach { path ->
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
            val payload = JSONObject().put("type", "daily_memory").put("version", 1).put("app", appDisplayName).put("date", date).put("generatedAt", at)
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
        if (channel.kind == PushKind.Notion) state.files.keys().asSequence().toList().filter { it.startsWith("daily/") && it !in documents }.forEach { path ->
            transport.deleteNotionDay(values, path.substringAfterLast('/').take(10))
            state.files.remove(path); state.templates.remove(path); state.save(); count++
        }
        return "更新 $count 天的文档"
    }
}
