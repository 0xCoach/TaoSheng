package app.resound.digest

import org.json.JSONArray
import org.json.JSONObject

/** 每个请求内部按 index 拼接工具名、id、参数；绝不把服务端异常正文交给界面。 */
data class LlmToolCall(val id: String, val name: String, val arguments: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
}
data class LlmStreamReply(val content: String, val calls: List<LlmToolCall>, val reasoningContent: String? = null) {
    fun message(): JSONObject = JSONObject().put("role", "assistant").put("content", content.ifEmpty { null } ?: JSONObject.NULL)
        .also { if (calls.isNotEmpty()) it.put("tool_calls", JSONArray(calls.map { c -> c.json() })) }
        .also { if (reasoningContent != null) it.put("reasoning_content", reasoningContent) }
}

/** readLine 由 Okio 提供，UTF-8 / 网络分片不影响行边界；也支持 SSE 多行 data、注释和 CRLF。 */
internal class LlmSseParser(
    private val onText: (String) -> Unit,
    private val onUsage: (Long, Long) -> Unit,
) {
    private val data = StringBuilder()
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var hasReasoning = false
    private data class CallParts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, CallParts>()
    var done = false; private set
    private var finished = false
    private var chars = 0

    fun line(line: String) {
        if (done) return
        if (line.isEmpty()) { dispatch(); return }
        if (line.startsWith("data:")) {
            data.append(line.substring(5).removePrefix(" ")).append('\n')
            if (data.length > 256_000) throw LlmException(LlmError.Format)
        }
    }
    private fun dispatch() {
        val payload = data.toString().trim(); data.clear()
        if (payload.isEmpty()) return
        if (payload == "[DONE]") { done = true; return }
        try {
            val json = JSONObject(payload)
            if (json.has("error")) throw LlmException(LlmError.Server)
            json.optJSONObject("usage")?.let { onUsage(it.optLong("prompt_tokens").coerceAtLeast(0), it.optLong("completion_tokens").coerceAtLeast(0)) }
            val choices = json.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                if (choice.optInt("index", 0) != 0) continue
                if (!choice.isNull("finish_reason")) {
                    val reason = choice.getString("finish_reason")
                    if (reason !in setOf("stop", "tool_calls")) throw LlmException(LlmError.Format)
                    finished = true
                }
                val delta = choice.optJSONObject("delta") ?: continue
                if (!delta.isNull("reasoning_content")) {
                    val part = delta.getString("reasoning_content")
                    hasReasoning = true; reasoning.append(part); chars += part.length
                }
                if (!delta.isNull("content")) {
                    val part = delta.getString("content"); text.append(part); chars += part.length
                    onText(part)
                }
                val toolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
                for (j in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(j)
                    val index = call.getInt("index")
                    if (index !in 0..31) throw LlmException(LlmError.Format)
                    val parts = calls.getOrPut(index) { CallParts() }
                    if (!call.isNull("id")) parts.id.append(call.getString("id"))
                    call.optJSONObject("function")?.let { f ->
                        if (!f.isNull("name")) parts.name.append(f.getString("name"))
                        if (!f.isNull("arguments")) {
                            val part = f.getString("arguments"); parts.arguments.append(part); chars += part.length
                        }
                    }
                }
                if (chars > 256_000) throw LlmException(LlmError.Format)
            }
        } catch (e: LlmException) { throw e }
        catch (_: Exception) { throw LlmException(LlmError.Format) }
    }
    fun finish(): LlmStreamReply {
        dispatch()
        if (!done || !finished) throw LlmException(LlmError.Format)
        val result = calls.values.map {
            if (it.id.isEmpty() || it.name.isEmpty()) throw LlmException(LlmError.Format)
            LlmToolCall(it.id.toString(), it.name.toString(), it.arguments.toString())
        }
        if (result.map { it.id }.distinct().size != result.size) throw LlmException(LlmError.Format)
        return LlmStreamReply(text.toString(), result, reasoning.toString().takeIf { hasReasoning })
    }
}
