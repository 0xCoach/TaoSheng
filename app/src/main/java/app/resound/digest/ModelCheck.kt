package app.resound.digest

import app.resound.ask.AskCitations
import app.resound.ask.AskSource
import app.resound.digest.core.ModelCheckCore
import app.resound.net.NetLlmError
import app.resound.net.NetLlmException
import app.resound.net.NetReply
import app.resound.net.NetStreamReply
import app.resound.net.NetToolCall

import kotlinx.coroutines.CancellationException
import app.resound.ask.AskFailure
import app.resound.ask.AskFailureRecord
import org.json.JSONArray
import org.json.JSONObject

/** 一项检测结果：ok = null 表示前一步没过、这项没测 */
enum class ModelCheckStatus { Passed, Degraded, Failed, Skipped }
data class ModelCheckItem(val feature: String, val ok: Boolean?, val detail: String,
    val degraded: Boolean = false, val lastAskFailure: AskFailureRecord? = null) {
    val status: ModelCheckStatus get() = when {
        degraded -> ModelCheckStatus.Degraded
        ok == true -> ModelCheckStatus.Passed
        ok == false -> ModelCheckStatus.Failed
        else -> ModelCheckStatus.Skipped
    }
}

/** 安卓 JSON/问问出处判定适配；检测顺序和提示词在 shared。 */
class ModelCheck(
    private val json: suspend (system: String, user: String) -> LlmReply,
    private val toolResult: (AskFailure?) -> Unit = {},
    private val stream: suspend (messages: JSONArray, tools: JSONArray) -> LlmStreamReply,
) {
    private suspend fun <T> network(block: suspend () -> T): T = try { block() } catch (e: LlmException) {
        throw NetLlmException(NetLlmError.valueOf(e.type.name), e.status, e.detail, truncated = e.truncated)
    }
    suspend fun run(): List<ModelCheckItem> = ModelCheckCore(
        json = { system, user -> network { json(system, user) }.let { NetReply(it.content, it.promptTokens, it.completionTokens) } },
        toolResult = { toolResult(it?.let(AskFailure::valueOf)) },
        stream = { messages, tools -> network { stream(JSONArray(messages), JSONArray(tools)) }.let { reply ->
            NetStreamReply(reply.content, reply.calls.map { NetToolCall(it.id, it.name, it.arguments, it.extraContent?.toString()) }, reply.reasoningContent)
        } },
        failure = { e -> AskFailure.from(LlmException(LlmError.valueOf(e.type.name), e.status, e.detail ?: LlmError.valueOf(e.type.name).human, truncated = e.truncated)).name },
        citationValid = { content -> AskCitations.parse(content, listOf(
            AskSource("line", 1, 0, "检测资料", "我们的暗号是蓝鲸"))).let { !it.invalid && it.sources.isNotEmpty() } },
    ).run().map { ModelCheckItem(it.feature, it.ok, it.detail, it.degraded) }
    companion object {
        const val CONNECT = ModelCheckCore.CONNECT
        const val JSON = ModelCheckCore.JSON
        const val STREAM = ModelCheckCore.STREAM
        const val TOOLS = ModelCheckCore.TOOLS
        const val CITE = ModelCheckCore.CITE
    }
}
