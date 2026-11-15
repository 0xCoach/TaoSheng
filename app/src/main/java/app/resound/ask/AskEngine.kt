package app.resound.ask

import app.resound.ask.core.AskArgumentFormatException
import app.resound.ask.core.AskEngineCore
import app.resound.ask.core.AskModel
// 与安卓兼容层同名，core 异常和失败类型保留全限定名。
import app.resound.ask.core.AskReply
import app.resound.ask.core.AskTrace
import app.resound.ask.core.ChangeProposal
import app.resound.digest.LlmReply
import app.resound.net.NetLlmError
import app.resound.net.NetLlmException
import app.resound.net.NetReply
import app.resound.net.NetStreamReply
import app.resound.net.NetToolCall

import app.resound.digest.LlmStreamReply
import app.resound.digest.LlmException
import app.resound.digest.LlmError
import org.json.JSONArray
import org.json.JSONObject

fun interface AskStream {
    suspend fun call(messages: JSONArray, tools: JSONArray, onText: (String) -> Unit, onUsage: (Long, Long) -> Unit): LlmStreamReply
}

/** 与 Android 生命周期解耦，工具预算按调用数计数（不是按 HTTP 轮数）。 */
class AskEngine(
    private val stream: AskStream,
    private val definitions: JSONArray,
    private val search: suspend (String, JSONObject, MutableList<AskSource>) -> JSONObject,
    private val usage: suspend (Long, Long) -> Unit,
    private val propose: (suspend (JSONObject, List<AskSource>) -> ProposedChange)? = null,
    private val mode: () -> AskMode = { AskMode.Tools },
    private val markFallback: (AskFailure, Boolean) -> Unit = { _, _ -> },
    private val recordFailure: (AskFailure) -> Unit = {},
    private val prefetch: suspend (String, AskContext?) -> AskContext = AskPrefetch(search)::read,
) {
    data class Update(val text: String, val sources: List<AskSource>, val progress: String?, val error: String? = null, val proposal: ProposedChange? = null, val mode: AskMode = AskMode.Tools)

    private val core = AskEngineCore(
        stream = AskModel { messages, tools, text, tokens ->
            val reply = try { stream.call(JSONArray(messages), JSONArray(tools), text, tokens) }
            catch (e: AskPrefetchRequired) {
                throw app.resound.ask.core.AskPrefetchRequired(app.resound.ask.core.AskFailure.valueOf(e.reason.name), e.actualFailure)
            }
            catch (e: LlmException) { throw e.toAskNet() }
            AskReply(NetStreamReply(reply.content,
                reply.calls.map { NetToolCall(it.id, it.name, it.arguments, it.extraContent?.toString()) }, reply.reasoningContent),
                reply.diagnostics?.let { trace -> AskTrace { items, empty, filtered, error ->
                    trace.parsed(items = items, empty = empty, filtered = filtered, error = error?.let { LlmError.valueOf(it.name) })
                } })
        }, definitions.toString(),
        { name, args, sources -> toolArguments { search(name, JSONObject(args), sources).toString() } }, usage,
        propose?.let { handler -> { args, sources -> toolArguments { handler(JSONObject(args), sources).toCore() } } },
        mode, { reason, actual -> markFallback(AskFailure.valueOf(reason.name), actual) },
        { recordFailure(AskFailure.valueOf(it.name)) }, prefetch,
    )
    suspend fun answer(system: String, history: List<AskMessage>, question: String, update: (Update) -> Unit) =
        answer(system, history, question, null, update)
    suspend fun answer(system: String, history: List<AskMessage>, question: String, context: AskContext?, update: (Update) -> Unit) {
        try { core.answer(system, history, question, context) { value ->
            update(Update(value.text, value.sources, value.progress, value.error, value.proposal?.let {
                ProposedChange(it.action, it.operation, JSONObject(it.args), it.expected)
            }, value.mode))
        } } catch (e: NetLlmException) {
            val type = LlmError.valueOf(e.type.name)
            throw LlmException(type, e.status, e.detail ?: type.human,
                e.usage?.let { LlmReply(it.content, it.promptTokens, it.completionTokens) }, e.truncated)
        }
    }
}
internal fun LlmException.toAskNet() = NetLlmException(NetLlmError.valueOf(type.name), status, detail,
    usage?.let { NetReply(it.content, it.promptTokens, it.completionTokens) }, truncated)
private fun ProposedChange.toCore() = ChangeProposal(action, operation, args.toString(), expected)
private inline fun <T> toolArguments(block: () -> T): T = try { block() }
catch (e: org.json.JSONException) { throw AskArgumentFormatException(e) }
catch (e: java.time.DateTimeException) { throw IllegalArgumentException(e) }
