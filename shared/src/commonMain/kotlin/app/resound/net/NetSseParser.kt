package app.resound.net

/** readLine 由 Okio 提供，UTF-8 / 网络分片不影响行边界；也支持 SSE 多行 data、注释和 CRLF。 */
internal class NetSseParser(
    private val onText: (String) -> Unit,
    private val onUsage: (Long, Long) -> Unit,
    private val diagnostics: NetworkTrace? = null,
) {
    private val data = StringBuilder()
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var hasReasoning = false
    private data class CallParts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder(), val extra: Obj = Obj())
    private val calls = mutableMapOf<Int, CallParts>()
    private val ids = mutableMapOf<String, Int>()
    var done = false; private set
    private var finished = false
    private var chars = 0

    fun line(line: String) {
        if (done) return
        if (line.isEmpty()) { dispatch(); return }
        if (line.startsWith("data:")) {
            data.append(line.substring(5).removePrefix(" ")).append('\n')
            if (data.length > 256_000) throw NetLlmException(NetLlmError.Format)
        }
    }
    private fun dispatch() {
        val payload = data.toString().trim(); data.clear()
        if (payload.isEmpty()) return
        if (payload == "[DONE]") { done = true; return }
        try {
            val json = Obj(payload)
            json.optJSONObject("usage")?.let { onUsage(it.optLong("prompt_tokens").coerceAtLeast(0), it.optLong("completion_tokens").coerceAtLeast(0)) }
            if (json.has("error")) throw NetLlmException(NetLlmError.Server)
            val choices = json.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                if (choice.optInt("index", 0) != 0) continue
                if (!choice.isNull("finish_reason")) {
                    val reason = choice.getString("finish_reason")
                    diagnostics?.finishReason(reason)
                    diagnostics?.content(text.toString())
                    if (reason == "length") throw NetLlmException(NetLlmError.Format, detail = NetLlmException.TRUNCATED, truncated = true)
                    if (reason !in setOf("stop", "tool_calls")) throw NetLlmException(NetLlmError.Format)
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
                val toolCalls = delta.optJSONArray("tool_calls") ?: Arr()
                for (j in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(j)
                    val explicit = !call.isNull("index")
                    val id = if (call.isNull("id")) null else call.getString("id")
                    val index = if (explicit) call.getInt("index") else if (id != null) {
                        ids[id] ?: (0..31).firstOrNull { it !in calls }
                            ?: throw NetLlmException(NetLlmError.Format)
                    } else j
                    if (index !in 0..31) throw NetLlmException(NetLlmError.Format)
                    val parts = calls.getOrPut(index) { CallParts() }
                    if (id != null) {
                        if (explicit || parts.id.isEmpty()) parts.id.append(id)
                        ids[parts.id.toString()] = index
                    }
                    call.optJSONObject("extra_content")?.let { extra ->
                        merge(parts.extra, extra); chars += extra.toString().length
                    }
                    call.optJSONObject("function")?.let { f ->
                        if (!f.isNull("name")) parts.name.append(f.getString("name"))
                        if (!f.isNull("arguments")) {
                            val part = f.getString("arguments"); parts.arguments.append(part); chars += part.length
                        }
                    }
                }
                if (chars > 256_000) throw NetLlmException(NetLlmError.Format)
            }
        } catch (e: NetLlmException) { diagnostics?.content(text.toString()); throw e }
        catch (_: Exception) { throw NetLlmException(NetLlmError.Format) }
    }
    private fun merge(target: Obj, incoming: Obj) {
        incoming.keys().forEach { key ->
            val value = incoming.get(key)
            val previous = target.optJSONObject(key)
            if (value is Obj && previous != null) merge(previous, value)
            else target.put(key, value)
        }
    }
    fun recordPartial() { diagnostics?.content(text.toString()) }

    fun finish(): NetStreamReply {
        dispatch()
        if (!done || !finished) throw NetLlmException(NetLlmError.Format)
        val result = calls.toList().sortedBy { it.first }.map { (index, it) ->
            if (it.name.isEmpty()) throw NetLlmException(NetLlmError.Format)
            NetToolCall(it.id.toString().ifEmpty { "resound_call_$index" }, it.name.toString(), it.arguments.toString(), it.extra.takeIf { extra -> extra.length() > 0 }?.toString())
        }
        if (result.map { it.id }.distinct().size != result.size) throw NetLlmException(NetLlmError.Format)
        return NetStreamReply(text.toString(), result, reasoning.toString().takeIf { hasReasoning })
    }
}
