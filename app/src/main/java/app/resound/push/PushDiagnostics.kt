package app.resound.push

import android.content.SharedPreferences
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** 每次推送独立，随协程切线程传播；仅收集类别和数量，不保存内容。 */
internal class PushTrace {
    var stage = "打包本地数据"
    var exception = ""
    val skipped = linkedSetOf<String>()
    val notices = linkedSetOf<String>()
    fun success(text: String) = if (skipped.isEmpty()) text else "已推送；有 ${skipped.size} 条记录格式异常，已跳过"
    fun requireContent(count: Int) {
        if (count == 0 && skipped.isNotEmpty()) throw PushFailure("本地有 ${skipped.size} 条记录格式异常，无法生成推送内容")
    }
    companion object {
        val local = ThreadLocal<PushTrace?>()
        fun current() = local.get()
        suspend fun <T> run(block: suspend (PushTrace) -> T): T {
            val trace = PushTrace()
            return withContext(local.asContextElement(trace)) { block(trace) }
        }
    }
}

internal inline fun <T> localRecord(kind: String, id: Long, block: () -> T): T? = try { block() }
catch (e: kotlinx.coroutines.CancellationException) { throw e }
catch (e: Exception) {
    if (e !is org.json.JSONException && e !is IllegalArgumentException && e !is ClassCastException && e !is java.time.DateTimeException) throw e
    PushTrace.current()?.apply { skipped += "$kind:$id"; exception = e.javaClass.simpleName }
    null
}

internal fun <T> responseJson(block: () -> T): T {
    val trace = PushTrace.current()
    val previous = trace?.stage
    trace?.stage = "解析回复"
    return try { block().also { if (previous != null) trace.stage = previous } }
    catch (e: org.json.JSONException) {
        trace?.exception = e.javaClass.simpleName
        throw PushFailure(PushErrors.INVALID_RESPONSE)
    }
}

internal object PushDiagnostics {
    @Synchronized fun record(prefs: SharedPreferences, channel: PushChannel, topic: PushTopic?, result: Result<*>, trace: PushTrace) {
        // 不导出远端自定义原因（可能回显请求内容），仅导出固定错误及异常类型。
        val error = result.exceptionOrNull()?.message.orEmpty()
        val fixed = (listOf(PushErrors.INVALID_URL, PushErrors.TIMEOUT) +
            (300..599).map { PushErrors.status(it, channel.kind == PushKind.MarkdownFolder) })
            .firstOrNull { error == it || error.startsWith("$it；") }
        val safe = when {
            fixed != null -> fixed
            error == PushErrors.INVALID_RESPONSE -> error
            error.matches(Regex("本地有 [0-9]+ 条记录格式异常，无法生成推送内容")) -> error
            result.isFailure -> "推送失败（详细原因请在本机推送日志查看）"
            else -> trace.success("已推送")
        }
        val entry = JSONObject().put("at", Instant.now().toString()).put("kind", channel.kind.name)
            .put("channelId", channel.id).put("topic", topic?.name ?: "Test").put("ok", result.isSuccess)
            .put("result", safe).put("stage", trace.stage).put("exception", trace.exception)
            .put("skipped", trace.skipped.size).put("categories", JSONObject(trace.skipped.groupingBy { it.substringBefore(':') }.eachCount()))
            .put("notices", JSONArray(trace.notices.toList()))
        val old = runCatching { JSONArray(prefs.getString("diagnostics", "[]")) }.getOrDefault(JSONArray())
        val ring = JSONArray().put(entry)
        repeat(minOf(29, old.length())) { ring.put(old.getJSONObject(it)) }
        check(prefs.edit().putString("diagnostics", ring.toString()).commit())
    }
    fun dump(prefs: SharedPreferences): String = buildString {
        val logs = runCatching { JSONArray(prefs.getString("diagnostics", "[]")) }.getOrDefault(JSONArray())
        val channels = runCatching { JSONArray(prefs.getString("channels", "[]")) }.getOrDefault(JSONArray())
        appendLine("推送通道（仅配置类型，不含地址或凭据）")
        repeat(channels.length()) { i ->
            val row = channels.optJSONObject(i) ?: return@repeat
            val kind = PushKind.entries.find { it.name == row.optString("kind") } ?: return@repeat
            val format = PushFormat.entries.find { it.name == row.optString("format") }
            val s = runCatching { Subscriptions.read(row.getJSONObject("subscription")) }.getOrNull()
            val last = (0 until logs.length()).mapNotNull { logs.optJSONObject(it) }.firstOrNull { it.optLong("channelId") == row.optLong("id") }
            appendLine("录音通道ID=${row.optLong("id")} 开启=${row.optBoolean("enabled") && s?.audio != null && s.audio != AudioPush.Off} 模式=${s?.audio} ${prefs.getString("audio-diagnostic-${row.optLong("id")}", "尚未统计")} ")
            appendLine("类型=${kind.name} 格式=$format 记录=${s?.records} 摘要=${s?.summaries} 提醒=${s?.reminders} 原话=${s?.lines} 上次结果=${last?.optString("result") ?: "暂无诊断"}")
        }
        appendLine("最近 30 次推送日志")
        repeat(minOf(30, logs.length())) { appendLine(logs.getJSONObject(it).toString()) }
    }
}
