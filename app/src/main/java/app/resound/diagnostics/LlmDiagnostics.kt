package app.resound.diagnostics

import android.content.Context
import android.content.SharedPreferences
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import app.resound.digest.LlmPreset
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 只存类型、计数和配置标识。独立偏好不在任何备份白名单内。所有写入失败均不影响业务。 */
class LlmDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun start(feature: String, preset: LlmPreset) = LlmResponseTrace(this, feature, preset)

    internal fun save(row: JSONObject) { runCatching { synchronized(lock) {
        val rows = rows().filterNot { it.optString("id") == row.optString("id") } + row
        prefs.edit().putString("responses", JSONArray(rows.sortedBy { it.optLong("at") }.takeLast(20)).toString()).apply()
    } } }

    internal fun rows(): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getString("responses", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrDefault(emptyList())

    internal fun abandoned(count: Int, at: Long) { runCatching {
        prefs.edit().putInt("abandonedCount", count).putLong("abandonedAt", at).apply()
    } }

    internal fun error(type: LlmError, at: Long) { runCatching {
        prefs.edit().putString("errorType", type.name).putLong("errorAt", at).apply()
    } }

    internal fun switched(at: Long, from: String, to: String, reason: String) { runCatching {
        prefs.edit().putString("switch", scrubProblemReport("时间=${stamp(at)} 主=$from → 备=$to 原因=$reason")).apply()
    } }

    internal fun state(): SharedPreferences = prefs

    internal fun dumpResponses(): String = rows().joinToString("\n") { row ->
        listOf("at", "purpose", "provider", "model", "elapsedMs", "http", "finish_reason", "truncated",
            "chars", "json", "topic", "phase", "block", "blocks", "events", "items", "empty", "filtered", "error").joinToString(" ") { key ->
            "$key=${if (key == "at") stamp(row.optLong(key)) else if (row.isNull(key)) "暂无" else row.opt(key)}"
        }
    }.ifBlank { "暂无" }

    companion object {
        internal const val PREFS = "llm-diagnostics"
        private val lock = Any()
        internal fun stamp(at: Long): String = if (at > 0) java.time.Instant.ofEpochMilli(at).toString() else "暂无"
    }
}

/** 随请求传播，避免并发问问/技能把摘要关联到另一条响应；不持有正文。 */
class LlmResponseTrace internal constructor(
    private val store: LlmDiagnostics, feature: String, preset: LlmPreset,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmResponseTrace>
    private val began = System.nanoTime()
    private val row = JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
        .put("purpose", feature.take(40)).put("provider", scrubProblemReport(preset.name).take(120))
        .put("model", scrubProblemReport(preset.model).take(120))

    internal fun topic(id: Long, phase: String, block: Int, total: Int) = update {
        put("topic", id); put("phase", phase); put("block", block); put("blocks", total)
    }
    internal fun http(code: Int) = update { put("http", code) }
    internal fun finishReason(reason: String?) = update {
        // 服务端任意字符串不得原样落盘。
        val safe = reason?.let { if (it in setOf("stop", "length", "tool_calls", "function_call", "content_filter")) it else "other" }
        put("finish_reason", safe ?: JSONObject.NULL); put("truncated", safe?.let { it == "length" } ?: JSONObject.NULL)
    }
    internal fun content(content: String) = update {
        put("chars", content.length); put("json", validJson(content)); put("empty", content.isBlank())
    }
    internal fun parsed(events: Int? = null, items: Int? = null, empty: Boolean, filtered: Boolean? = null, error: LlmError? = null) {
        update {
            put("events", events ?: JSONObject.NULL); put("items", items ?: JSONObject.NULL)
            put("empty", empty); put("filtered", filtered ?: JSONObject.NULL); put("error", error?.name ?: JSONObject.NULL)
        }
        persist()
    }
    internal fun complete(error: Throwable? = null) {
        update {
            put("elapsedMs", (System.nanoTime() - began) / 1_000_000)
            if (error != null) put("error", when (error) {
                is LlmException -> error.type.name
                is kotlinx.coroutines.CancellationException -> "Cancelled"
                is app.resound.digest.LlmInputLimitException -> "InputLimit"
                is java.io.IOException -> LlmError.Network.name
                else -> "Local"
            })
        }
        persist()
    }
    private fun update(block: JSONObject.() -> Unit) { runCatching { synchronized(this) { row.block() } } }
    private fun persist() { runCatching { synchronized(this) { store.save(JSONObject(row.toString())) } } }
}

internal fun responseObject(content: String): JSONObject? = runCatching {
    JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
}.getOrNull()

internal fun validJson(content: String): Boolean = runCatching {
    val tokener = JSONTokener(content)
    val value = tokener.nextValue()
    value != null && tokener.nextClean() == '\u0000' &&
        (value !is String || content.trim().startsWith('"'))
}.getOrDefault(false)
