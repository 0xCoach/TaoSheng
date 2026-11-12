package app.resound.ask.core
import app.resound.ask.*
import app.resound.net.*

/** 跨平台边界的 JSON 均为协议文本；不传平台 JSON 对象。 */
data class ChangeProposal(val action: AskAction, val operation: String, val args: String, val expected: String?)
fun interface AskModel {
    suspend fun call(messages: String, tools: String, onText: (String) -> Unit, onUsage: (Long, Long) -> Unit): AskReply
}
fun interface AskTrace {
    fun parsed(items: Int, empty: Boolean, filtered: Boolean, error: NetLlmError?)
}
data class AskReply(val value: NetStreamReply, val diagnostics: AskTrace? = null) {
    val content get() = value.content
    val calls get() = value.calls
    internal fun message(): Obj = Obj().put("role", "assistant").put("content", content.ifEmpty { null } ?: Obj.NULL)
        .also { if (calls.isNotEmpty()) it.put("tool_calls", Arr(calls.map { c ->
            Obj().put("id", c.id).put("type", "function").put("function", Obj().put("name", c.name).put("arguments", c.arguments))
                .also { if (c.extraContent != null) it.put("extra_content", Obj(c.extraContent)) }
        })) }.also { if (value.reasoningContent != null) it.put("reasoning_content", value.reasoningContent) }
}
internal fun AskTrace.parsed(items: Int, empty: Boolean, error: NetLlmError? = null) = parsed(items, empty, false, error)

/** 平台 JSON 解析错误保留诊断含义，与一般参数校验分开。 */
class AskArgumentFormatException(cause: Throwable) : IllegalArgumentException(cause)
