package app.resound.digest

import org.json.JSONArray
import org.json.JSONObject

/** 每个请求内部归并工具分片；绝不把服务端异常正文交给界面。 */
data class LlmToolCall(val id: String, val name: String, val arguments: String, val extraContent: JSONObject? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
        .also { if (extraContent != null) it.put("extra_content", JSONObject(extraContent.toString())) }
}
data class LlmStreamReply(val content: String, val calls: List<LlmToolCall>, val reasoningContent: String? = null, val diagnostics: app.resound.diagnostics.LlmResponseTrace? = null) {
    fun message(): JSONObject = JSONObject().put("role", "assistant").put("content", content.ifEmpty { null } ?: JSONObject.NULL)
        .also { if (calls.isNotEmpty()) it.put("tool_calls", JSONArray(calls.map { c -> c.json() })) }
        .also { if (reasoningContent != null) it.put("reasoning_content", reasoningContent) }
}

/** 旧 OkHttp 门面也使用共用 SSE 规则；只转换平台 JSON 和诊断类型。 */
internal class LlmSseParser(onText: (String) -> Unit, onUsage: (Long, Long) -> Unit,
    diagnostics: app.resound.diagnostics.LlmResponseTrace? = null) {
    private val core = app.resound.net.NetSseParser(onText, onUsage, diagnostics?.let { trace -> object : app.resound.net.NetworkTrace {
        override fun http(status: Int) = trace.http(status)
        override fun content(text: String) = trace.content(text)
        override fun finishReason(reason: String?) = trace.finishReason(reason)
    } })
    val done get() = core.done
    private inline fun <T> result(block: () -> T): T = try { block() } catch (e: app.resound.net.NetLlmException) {
        val type = LlmError.valueOf(e.type.name)
        throw LlmException(type, e.status, e.detail ?: type.human, truncated = e.truncated)
    }
    fun line(line: String) = result { core.line(line) }
    fun recordPartial() = core.recordPartial()
    fun finish(): LlmStreamReply = result { core.finish().let { reply ->
        LlmStreamReply(reply.content, reply.calls.map { LlmToolCall(it.id, it.name, it.arguments, it.extraContent?.let(::JSONObject)) }, reply.reasoningContent)
    } }
}
