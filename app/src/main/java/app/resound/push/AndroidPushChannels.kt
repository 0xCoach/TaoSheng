package app.resound.push

import android.content.Context
import android.content.SharedPreferences
import app.resound.digest.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal interface PushSecrets {
    fun read(ref: String): String
    fun write(ref: String, value: String)
    fun remove(ref: String)
}

/** 配置只含随机密钥引用；密文位于不进入备份的独立 preferences。 */
class AndroidPushChannels internal constructor(
    private val prefs: SharedPreferences,
    private val outbox: SharedPreferences,
    private val secrets: PushSecrets,
    private val transport: PushTransport,
    private val enqueue: (String) -> Unit,
    private val latestReview: suspend () -> app.resound.review.DailyReview? = { null },
) : PushChannels {
    constructor(context: Context, latestReview: suspend () -> app.resound.review.DailyReview? = { null }) : this(
        context.getSharedPreferences("push", Context.MODE_PRIVATE),
        context.getSharedPreferences("push-retries", Context.MODE_PRIVATE),
        object : PushSecrets {
            private val store = SecretStore(context, "push-secrets", "resound-push-aes-v1")
            override fun read(ref: String) = store.read(ref)
            override fun write(ref: String, value: String) = store.write(ref, value)
            override fun remove(ref: String) = store.remove(ref)
        }, PushTransport(), { PushRetryScheduler(context).enqueue(it) }, latestReview,
    )

    internal var memoryDocument: suspend (java.time.LocalDate?, Boolean) -> PushMessage? = { _, _ -> null }

    private val mutableChannels = MutableStateFlow(readChannels().map(::publicChannel))
    override val channels = mutableChannels.asStateFlow()
    private val mutableLogs = MutableStateFlow(readLogs())
    override fun logs(limit: Int): Flow<List<PushLog>> = mutableLogs.map { it.take(limit.coerceIn(0, 100)) }
    override fun fields(kind: PushKind) = PushFields.fields(kind)

    // ---------- 卡 042 实现前的占位 ----------
    override fun capabilities(kind: PushKind) = when (kind) {
        PushKind.PushPlus, PushKind.WeCom, PushKind.ServerChan, PushKind.Feishu, PushKind.DingTalk ->
            PushCapabilities(records = false, incremental = false, folder = false, formats = setOf(PushFormat.Text, PushFormat.Markdown))
        PushKind.Telegram, PushKind.Email -> PushCapabilities(records = true, incremental = false, folder = false, formats = setOf(PushFormat.Text, PushFormat.Markdown))
        PushKind.Notion -> PushCapabilities(records = true, incremental = true, folder = false, formats = setOf(PushFormat.Markdown))
        PushKind.MarkdownFolder -> PushCapabilities(records = true, incremental = true, folder = true, formats = setOf(PushFormat.Markdown))
        PushKind.Webhook -> PushCapabilities(records = true, incremental = true, folder = false, formats = PushFormat.entries.toSet())
    }
    override val linkedNutstoreAccount: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun saveWithSubscription(id: Long?, kind: PushKind, name: String, values: Map<String, String>,
                                              subscription: PushSubscription, format: PushFormat, useLinkedNutstore: Boolean): Result<Long> =
        save(id, kind, name, values, topics(kind), format, subscription.lines != LinesMode.Off)
    override suspend fun pushNow(id: Long): Result<String> = Result.failure(IllegalStateException("还在开发中"))
    override suspend fun rebuild(id: Long): Result<String> = Result.failure(IllegalStateException("还在开发中"))
    override fun status(id: Long): kotlinx.coroutines.flow.Flow<PushChannelStatus> = kotlinx.coroutines.flow.flowOf(PushChannelStatus(null, null, null, false))
    override fun topics(kind: PushKind): Set<PushTopic> =
        if (kind in setOf(PushKind.Webhook, PushKind.Email, PushKind.Notion, PushKind.Telegram, PushKind.MarkdownFolder)) PushTopic.entries.toSet()
        else PushTopic.entries.toSet() - PushTopic.DailyMemory

    private fun array(key: String) = JSONArray(prefs.getString(key, "[]"))
    private fun readChannels(): List<JSONObject> = array("channels").let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun readLogs(): List<PushLog> = array("logs").let { a -> List(a.length()) { i ->
        val row = a.getJSONObject(i)
        PushLog(row.getLong("id"), row.getLong("channelId"), row.getString("channelName"), row.getLong("at"),
            PushTopic.entries.find { it.name == row.optString("topic") }, row.getString("title"), row.getBoolean("ok"),
            if (row.isNull("error")) null else row.getString("error"))
    } }
    private fun publicChannel(row: JSONObject): PushChannel {
        val kind = PushKind.valueOf(row.getString("kind"))
        val refs = row.getJSONObject("secrets")
        val summary = fields(kind).filter { it.secret }.joinToString(" · ") { field ->
            val present = runCatching { secrets.read(refs.optString(field.key)).isNotBlank() }.getOrDefault(false)
            "${field.label} ${if (present) "已设置" else "未填写"}"
        }
        return PushChannel(row.getLong("id"), kind, row.getString("name"), row.getBoolean("enabled"),
            row.getJSONArray("topics").let { a -> List(a.length()) { PushTopic.valueOf(a.getString(it)) }.toSet() },
            PushFormat.valueOf(row.getString("format")), summary, row.optBoolean("memoryWithLines", false))
    }
    private fun values(row: JSONObject): Map<String, String> {
        val kind = PushKind.valueOf(row.getString("kind"))
        return fields(kind).associate { field -> field.key to if (field.secret) {
            val ref = row.getJSONObject("secrets").optString(field.key)
            if (ref.isEmpty()) "" else runCatching { secrets.read(ref) }.getOrDefault("")
        } else row.getJSONObject("values").optString(field.key) }
    }
    private fun persist(rows: List<JSONObject>) {
        check(prefs.edit().putString("channels", JSONArray(rows).toString()).commit())
        mutableChannels.value = rows.map(::publicChannel)
    }

    override suspend fun save(id: Long?, kind: PushKind, name: String, values: Map<String, String>, topics: Set<PushTopic>, format: PushFormat,
                              memoryWithLines: Boolean): Result<Long> =
        withContext(Dispatchers.IO) { saveLocked(id, kind, name, values, topics, format, memoryWithLines) }

    @Synchronized private fun saveLocked(id: Long?, kind: PushKind, name: String, input: Map<String, String>, topics: Set<PushTopic>, format: PushFormat, memoryWithLines: Boolean): Result<Long> {
        val createdRefs = mutableListOf<String>()
        return try {
            val rows = readChannels()
            val old = rows.find { it.getLong("id") == id }
            if (id != null && old == null) throw PushFailure("通道不存在，请重新打开")
            if (name.isBlank()) throw PushFailure("请填写通道名称")
            val previous = old?.takeIf { it.getString("kind") == kind.name }?.let(::values).orEmpty()
            val merged = fields(kind).associate { field -> field.key to
                (input[field.key]?.trim().orEmpty().let { if (field.secret && it.isEmpty()) previous[field.key].orEmpty() else it }) }
            PushFields.validate(kind, merged)
            val channelId = id ?: maxOf(System.currentTimeMillis(), (rows.maxOfOrNull { it.getLong("id") } ?: 0) + 1)
            val refs = JSONObject()
            val plain = JSONObject()
            fields(kind).forEach { field ->
                val value = merged.getValue(field.key)
                if (field.secret && value.isNotEmpty()) {
                    val ref = UUID.randomUUID().toString()
                    createdRefs += ref
                    secrets.write(ref, value)
                    refs.put(field.key, ref)
                } else if (!field.secret) plain.put(field.key, value)
            }
            val row = JSONObject().put("id", channelId).put("kind", kind.name)
                .put("name", redact(name.trim(), sensitiveValues(merged))).put("enabled", old?.getBoolean("enabled") ?: true)
                .put("topics", JSONArray(topics.intersect(this.topics(kind)).map { it.name })).put("memoryWithLines", memoryWithLines).put("format", format.name).put("values", plain).put("secrets", refs)
            persist(rows.filterNot { it.getLong("id") == channelId } + row)
            old?.let(::removeSecrets)
            Result.success(channelId)
        } catch (e: Exception) {
            createdRefs.forEach { runCatching { secrets.remove(it) } }
            Result.failure(if (e is PushFailure) e else PushFailure("通道保存失败，请重试"))
        }
    }

    private fun removeSecrets(row: JSONObject) {
        val refs = row.getJSONObject("secrets")
        refs.keys().forEach { key -> runCatching { secrets.remove(refs.getString(key)) } }
    }
    @Synchronized override fun setEnabled(id: Long, enabled: Boolean) {
        try { persist(readChannels().onEach { if (it.getLong("id") == id) it.put("enabled", enabled) }) }
        catch (_: Exception) { throw PushFailure("通道设置保存失败，请重试") }
    }
    @Synchronized override fun delete(id: Long) {
        try {
            val rows = readChannels()
            persist(rows.filterNot { it.getLong("id") == id })
            rows.find { it.getLong("id") == id }?.let(::removeSecrets)
            outbox.all.filterValues { value -> runCatching { JSONObject(value as String).getLong("channelId") == id }.getOrDefault(false) }
                .keys.forEach { outbox.edit().remove(it).commit() }
        } catch (_: Exception) { throw PushFailure("通道删除失败，请重试") }
    }

    override suspend fun test(id: Long): Result<String> {
        val channel = resolve(id)?.first ?: return Result.failure(PushFailure("通道不存在，请重新打开"))
        if (PushTopic.DailyMemory in channel.topics) {
            val message = try { memoryDocument(null, channel.memoryWithLines) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return Result.failure(PushFailure("记忆文档读取失败，请稍后重试")) }
                ?: return Result.failure(PushFailure("还没有可发送的记忆文档"))
            return deliver(id, message, testing = true).map { "已发送 ${message.memory!!.getString("date")} 的记忆文档" }
        }
        val review = try { latestReview() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return Result.failure(PushFailure("回顾读取失败，请稍后重试")) }
        val message = if (review != null) PushMessage("测试 · ${review.pushTitle()}", review.pushBody(PushFormat.Text),
            PushTopic.DailyReview, "resound://review?open=review&date=${review.date}", System.currentTimeMillis(), review)
        else PushMessage("回响 · 测试推送", "如果你看到这条，说明通道可用", null, null, System.currentTimeMillis())
        return deliver(id, message, testing = true).map {
            if (review != null) "已发送 ${review.pushDate()}的回顾" else "测试消息已提交，请查看接收端"
        }
    }

    internal suspend fun dispatchDailyMemory(date: java.time.LocalDate) = supervisorScope {
        channels.value.filter { it.enabled && PushTopic.DailyMemory in it.topics && PushTopic.DailyMemory in topics(it.kind) }.map { channel -> launch {
            try {
                deliver(channel.id, PushMessage("每日记忆文档 · $date", "", PushTopic.DailyMemory, null, System.currentTimeMillis(),
                    memoryDate = date.toString(), memoryWithLines = channel.memoryWithLines))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 一个通道的存储故障不阻断其他通道。 */ }
        } }.joinAll()
    }

    internal suspend fun dispatch(message: PushMessage) = supervisorScope {
        if (message.topic == null) return@supervisorScope
        channels.value.filter { it.enabled && message.topic in it.topics }.map { channel -> launch {
            try { deliver(channel.id, message) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 单个通道的存储故障不阻断其他通知。 */ }
        } }.joinAll()
    }

    @Synchronized private fun resolve(id: Long): Pair<PushChannel, Map<String, String>>? =
        readChannels().find { it.getLong("id") == id }?.let { publicChannel(it) to values(it) }

    private data class Attempt(val result: Result<Unit>, val snapshot: PushMessage)
    private suspend fun attempt(id: Long, message: PushMessage, testing: Boolean): Attempt? {
        val (channel, values) = resolve(id) ?: return null
        if (!testing && (!channel.enabled || message.topic !in channel.topics)) return null
        var snapshot = message
        val result = try {
            if (message.memoryDate != null && message.memory == null) {
                snapshot = memoryDocument(java.time.LocalDate.parse(message.memoryDate), message.memoryWithLines)
                    ?: return Attempt(Result.success(Unit), message) // 已无内容，不发送、不记成功日志。
            }
            transport.send(channel.kind, channel.format, values, snapshot)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(PushFailure("记忆文档读取失败，请稍后重试")) }
        record(channel, snapshot, result, sensitiveValues(values))
        return Attempt(result, snapshot)
    }

    private suspend fun deliver(id: Long, message: PushMessage, testing: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val attempt = attempt(id, message, testing) ?: return@withContext Result.failure(PushFailure("通道不存在或已停用"))
            val result = attempt.result
            if (result.isFailure) {
                val job = UUID.randomUUID().toString()
                val payload = JSONObject().put("channelId", id).put("testing", testing).put("message", attempt.snapshot.json())
                check(outbox.edit().putString(job, payload.toString()).commit())
                try { enqueue(job) } catch (_: Exception) {
                    outbox.edit().remove(job).commit()
                    val failure = Result.failure<Unit>(PushFailure("推送失败，重试任务未能保存，请手动重试"))
                    resolve(id)?.let { (channel, values) -> record(channel, message, failure, sensitiveValues(values)) }
                    return@withContext failure
                }
            }
            result
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(PushFailure("推送记录保存失败，请稍后重试")) }
    }

    /** 首次失败后最多额外尝试 3 次；不递归创建新任务，删除/停用后的普通推送停止。 */
    internal suspend fun retry(job: String, attemptCount: Int): Boolean = withContext(Dispatchers.IO) {
        if (attemptCount >= 3) {
            outbox.edit().remove(job).commit()
            return@withContext false
        }
        val payload = outbox.getString(job, null) ?: return@withContext false
        try {
            val row = JSONObject(payload)
            val attempt = attempt(row.getLong("channelId"), PushMessage.from(row.getJSONObject("message")), row.getBoolean("testing"))
            val again = attempt?.result?.isFailure == true && attemptCount < 2
            if (again) check(outbox.edit().putString(job, row.put("message", attempt!!.snapshot.json()).toString()).commit())
            if (!again) outbox.edit().remove(job).commit()
            again
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (attemptCount >= 2) outbox.edit().remove(job).commit()
            attemptCount < 2
        }
    }

    @Synchronized private fun record(channel: PushChannel, message: PushMessage, result: Result<Unit>, sensitive: Collection<String>) {
        val logs = array("logs")
        val now = System.currentTimeMillis()
        val id = maxOf(now, if (logs.length() > 0) logs.getJSONObject(0).getLong("id") + 1 else 0)
        val entry = JSONObject().put("id", id).put("channelId", channel.id).put("channelName", redact(channel.name, sensitive))
            .put("at", now).put("topic", message.topic?.name ?: JSONObject.NULL).put("title", redact(message.title, sensitive))
            .put("ok", result.isSuccess).put("error", result.exceptionOrNull()?.message ?: JSONObject.NULL)
        val ring = JSONArray().put(entry)
        repeat(minOf(logs.length(), 99)) { ring.put(logs.getJSONObject(it)) }
        check(prefs.edit().putString("logs", ring.toString()).commit())
        mutableLogs.value = readLogs()
    }

    private fun sensitiveValues(values: Map<String, String>): Collection<String> = values.values +
        runCatching { PushFields.headers(values["headers"].orEmpty()).values.toList() }.getOrDefault(emptyList())

    private fun redact(text: String, sensitive: Collection<String>): String {
        var safe = text
        sensitive.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[已隐藏]") }
        return safe.replace(Regex("https?://\\S+"), "[地址已隐藏]")
    }
}
