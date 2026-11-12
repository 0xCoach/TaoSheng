package app.resound.ask

import android.content.Context
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import app.resound.digest.LlmPreset
import org.json.JSONObject

/** 只允许固定原因落盘，绝不保存异常正文、问题、原话或凭证。 */
enum class AskFailure(val human: String) {
    ToolFormat("模型调用工具时返回的格式不对"),
    ToolRejected("模型服务拒绝了带工具的请求"),
    AnswerFormat("模型未能输出有效回答，请换个模型重试"),
    Timeout("查询超时，请重试"),
    Query("查询失败，请重试"),
    NoLookup("模型没查记录就作答"),
    Citation("回答出处无法核实"),
    Empty("没有收到回答，请重试"),
    Truncated(LlmException.TRUNCATED),
    Auth(LlmError.Auth.human), Quota(LlmError.Quota.human), RateLimit(LlmError.RateLimit.human),
    Network(LlmError.Network.human), Server(LlmError.Server.human);

    companion object {
        fun from(error: LlmException, withTools: Boolean = true): AskFailure =
            valueOf(app.resound.ask.core.AskFailure.from(error.toAskNet(), withTools).name)
    }
}

/** 已知兼容标记也可在备用服务真正被选中时触发，不伪造一次实际失败。 */
internal class AskPrefetchRequired(val reason: AskFailure, val actualFailure: Boolean = true) : RuntimeException()

data class AskFailureRecord(val reason: AskFailure, val at: Long)
data class AskCompatibilityState(val fallback: AskFailureRecord? = null, val lastFailure: AskFailureRecord? = null)

class AskCompatibility(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("ask-compatibility", Context.MODE_PRIVATE)
    private fun key(preset: LlmPreset) = "model:" + modelKey(preset.id, preset.model)
    private fun modelKey(id: String, model: String) = org.json.JSONArray().put(id).put(model).toString()
    fun generation(id: String): Long = synchronized(lock) { prefs.getLong("generation:$id", 0) }
    fun state(preset: LlmPreset): AskCompatibilityState = synchronized(lock) {
        val row = runCatching { JSONObject(prefs.getString(key(preset), "{}")!!) }.getOrDefault(JSONObject())
        fun record(name: String): AskFailureRecord? = runCatching {
            val value = row.getJSONObject(name)
            AskFailureRecord(AskFailure.valueOf(value.getString("reason")), value.getLong("at"))
        }.getOrNull()
        AskCompatibilityState(record("fallback"), record("failure"))
    }
    fun mark(preset: LlmPreset, reason: AskFailure, generation: Long, actualFailure: Boolean = true) = update(preset, generation) {
        val record = JSONObject().put("reason", reason.name).put("at", now())
        put("fallback", record)
        if (actualFailure) put("failure", record)
    }
    fun failed(preset: LlmPreset, reason: AskFailure, generation: Long) = update(preset, generation) {
        put("failure", JSONObject().put("reason", reason.name).put("at", now()))
    }
    fun passed(preset: LlmPreset, generation: Long) = update(preset, generation) { remove("fallback") }
    private fun update(preset: LlmPreset, generation: Long, block: JSONObject.() -> Unit) = synchronized(lock) {
        if (generation != generation(preset.id)) return@synchronized
        val row = runCatching { JSONObject(prefs.getString(key(preset), "{}")!!) }.getOrDefault(JSONObject())
        row.block()
        prefs.edit().putString(key(preset), row.toString()).apply()
    }
    fun changed(id: String) = synchronized(lock) {
        val editor = prefs.edit().putLong("generation:$id", generation(id) + 1)
        prefs.all.keys.filter { it.startsWith("model:") }.forEach { key ->
            if (runCatching { org.json.JSONArray(key.removePrefix("model:")).getString(0) == id }.getOrDefault(false)) editor.remove(key)
        }
        editor.apply()
    }
    companion object { private val lock = Any() }
}
