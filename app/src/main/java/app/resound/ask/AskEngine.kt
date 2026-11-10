package app.resound.ask

import app.resound.digest.LlmStreamReply
import app.resound.digest.LlmException
import app.resound.digest.LlmError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private val propose: ((JSONObject, List<AskSource>) -> ProposedChange)? = null,
    private val mode: () -> AskMode = { AskMode.Tools },
    private val markFallback: (AskFailure, Boolean) -> Unit = { _, _ -> },
    private val recordFailure: (AskFailure) -> Unit = {},
    private val prefetch: suspend (String, AskContext?) -> AskContext = AskPrefetch(search)::read,
) {
    data class Update(val text: String, val sources: List<AskSource>, val progress: String?, val error: String? = null, val proposal: ProposedChange? = null, val mode: AskMode = AskMode.Tools)

    suspend fun answer(system: String, history: List<AskMessage>, question: String, update: (Update) -> Unit) =
        answer(system, history, question, null, update)

    suspend fun answer(system: String, history: List<AskMessage>, question: String, context: AskContext?, update: (Update) -> Unit) {
        var currentMode = mode()
        fun emit(value: Update) {
            value.error?.let { recordFailure(when (it) {
                AskFailure.Empty.human -> AskFailure.Empty
                AskFailure.AnswerFormat.human -> AskFailure.AnswerFormat
                else -> AskFailure.Citation
            }) }
            update(value.copy(mode = currentMode))
        }
        suspend fun run() {
            emit(Update("", emptyList(), if (currentMode == AskMode.Prefetch) "正在由程序查找相关记录…" else "正在查找相关记录…"))
            val evidence = if (currentMode == AskMode.Prefetch) prefetch(question, context) else context
            answerOnce(system, history, question, evidence, currentMode, ::emit)
        }
        try {
            try { run() } catch (fallback: AskPrefetchRequired) {
                markFallback(fallback.reason, fallback.actualFailure)
                currentMode = AskMode.Prefetch
                run() // 同一次问答只兜底一次，丢弃失败轮的文字和工具消息。
            }
        } catch (e: LlmException) {
            recordFailure(AskFailure.from(e, withTools = currentMode == AskMode.Tools))
            when {
                e.type == LlmError.Server -> throw LlmException(LlmError.Server, e.status, usage = e.usage)
                e.type == LlmError.Format && !e.truncated -> throw LlmException(LlmError.Format, e.status, AskFailure.AnswerFormat.human, e.usage)
                else -> throw e
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            recordFailure(AskFailure.Timeout); throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { recordFailure(AskFailure.Query); throw e }
    }

    private suspend fun answerOnce(system: String, history: List<AskMessage>, question: String,
        context: AskContext?, mode: AskMode, update: (Update) -> Unit) {
        var proposal: ProposedChange? = null
        fun emit(value: Update) = update(value.copy(proposal = proposal))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system +
            (if (mode == AskMode.Prefetch) "\n本轮由程序先查，模型只答。只依据本轮资料给出答案，不再调用工具，不能执行或声称完成数据修改。" else "") +
            "\n不需要查记录的寒暄或通用问题可直接回答，必须以 [无需记录] 开头且不带出处；涉及用户经历必须查证，不能用此标记跳过核实。" +
            "\n本轮提供的局部上下文和重新核验出处也可作为证据，编号仅使用本轮 ref；已有充分原话可直接回答，无需重复检索。历史回答本身不是证据。"))
        val refreshed = context?.sources.orEmpty().associateBy { it.kind to it.id }
        history.takeLast(12).forEach {
            // 上轮错误答案、以及未重新核验的旧回答不进入证据上下文，防止已删记录从聊天摘要复活。
            if (it.role == AskRole.Assistant && (it.error != null || it.sources.any { source ->
                refreshed[source.kind to source.id]?.quote != source.quote
            })) return@forEach
            messages.put(JSONObject().put("role", if (it.role == AskRole.User) "user" else "assistant")
                .put("content", AskCitations.withoutMarkers(it.text).take(4000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", listOfNotNull(context?.text, question).joinToString("\n\n")))
        val sources = context?.sources?.toMutableList() ?: mutableListOf()
        var count = 0
        var repairing = false
        repeat(8) {
            if (count >= 6) messages.put(JSONObject().put("role", "system").put("content",
                "本轮工具额度已用完。现在只根据已有原话出处给最终回答；没查到就说没找到。不再调用工具，不输出工具标记。"))
            var text = ""
            var prompt = 0L; var completion = 0L
            val reply = try {
                stream.call(messages, if (mode == AskMode.Tools && count < 6 && !repairing) definitions else JSONArray(), { delta ->
                    text += delta
                    val parsed = AskCitations.parse(displayText(text), sources)
                    if (!text.contains("DSML")) emit(Update(parsed.text, parsed.sources, null))
                }, { p, c -> prompt = p; completion = c })
            } catch (e: LlmException) {
                if (mode == AskMode.Tools && !e.truncated &&
                    ((e.type == LlmError.Format && !repairing) || e.status == 400)) throw AskPrefetchRequired(AskFailure.from(e))
                throw e
            } finally {
                // 取消也保留已收到的 usage；未返回 usage 时计一次调用、token 为 0，不捏造用量。
                withContext(NonCancellable) { usage(prompt, completion) }
            }
            if (reply.calls.isEmpty()) {
                val casual = reply.content.trimStart().startsWith(NO_RECORDS) && !reply.content.contains("[^")
                val parsed = AskCitations.parse(displayText(reply.content), sources)
                reply.diagnostics?.parsed(items = parsed.sources.size, empty = parsed.text.isBlank(),
                    filtered = sources.isNotEmpty() && parsed.sources.isEmpty(),
                    error = if (parsed.invalid || reply.content.contains("DSML") || parsed.text.isBlank()) app.resound.digest.LlmError.Format else null)
                if (reply.content.contains("DSML")) {
                    if (mode == AskMode.Tools) throw AskPrefetchRequired(AskFailure.ToolFormat)
                    emit(Update("", emptyList(), null, AskFailure.AnswerFormat.human)); return
                }
                if (mode == AskMode.Tools && !casual && count == 0 && sources.isEmpty() && context == null &&
                    parsed.text.isNotBlank()) throw AskPrefetchRequired(AskFailure.NoLookup)
                val missing = !casual && proposal == null && sources.isNotEmpty() && parsed.sources.isEmpty() &&
                    parsed.text.isNotBlank() && !parsed.text.contains("没找到")
                if (!repairing && (parsed.invalid || missing)) {
                    repairing = true
                    emit(Update("", emptyList(), "正在核对回答出处…"))
                    messages.put(JSONObject().put("role", "assistant").put("content", reply.content.take(4000)))
                    messages.put(JSONObject().put("role", "system").put("content",
                        "刚才的回答有无效或缺失出处。请只根据本轮已提供的资料重新作答，核对每条原话和 ref。" +
                        "当前有效 ref 为 ${if (sources.isEmpty()) "空（没有可引用记录）" else "1 至 ${sources.size}"}；数据库 id 不是 ref。" +
                        "不得猜测编号或仅为通过校验添加引用，不能证实的结论请删去；没有依据就明确说没找到。" +
                        "这是最后一次出处修复，不再调用工具，不发起或重复数据修改。"))
                    return@repeat
                }
                val error = when {
                    parsed.invalid -> "回答出处仍无法核实，已停止自动重试。请指定事件或补充关键词。"
                    parsed.text.isBlank() -> "没有收到回答，请重试"
                    // 程序查过也不能让没有出处的经历结论通过。
                    !casual && proposal == null && parsed.sources.isEmpty() && !parsed.text.contains("没找到") ->
                        if (mode == AskMode.Prefetch) "没找到可核实的记录，请补充关键词或缩小时间范围"
                        else "回答缺少出处，请重新提问；常出现可在设置里检测模型"
                    else -> null
                }
                reply.diagnostics?.parsed(items = parsed.sources.size, empty = parsed.text.isBlank(),
                    filtered = sources.isNotEmpty() && parsed.sources.isEmpty(), error = error?.let { app.resound.digest.LlmError.Format })
                emit(Update(parsed.text, parsed.sources, null, error)); return
            }
            reply.diagnostics?.parsed(items = reply.calls.size, empty = false,
                error = if (repairing) app.resound.digest.LlmError.Format else null)
            if (mode == AskMode.Prefetch || repairing) {
                emit(Update("", emptyList(), null, "模型未完成出处核对，已停止自动重试")); return
            }
            messages.put(reply.message())
            for (call in reply.calls) {
                val result = if (count >= 6) {
                    JSONObject().put("error", "工具次数已用完，请根据已有出处回答；没有就说没找到")
                } else {
                    count++
                    try {
                        val args = JSONObject(call.arguments)
                        emit(Update("", emptyList(), progress(call.name, args)))
                        if (call.name == "propose_change") {
                            require(proposal == null) { "一条回答最多一个改动" }
                            val handler = requireNotNull(propose)
                            proposal = handler(args, sources)
                            emit(Update("", emptyList(), "已准备确认卡片，等待你确认"))
                            JSONObject().put("pending", true).put("summary", proposal!!.action.summary)
                                .put("detail", proposal!!.action.detail).put("notice", "尚未修改数据，必须由用户在卡片上确认")
                        } else search(call.name, args, sources)
                    } catch (_: IllegalArgumentException) {
                        JSONObject().put("error", "参数无效，请检查日期与工具参数")
                    } catch (_: org.json.JSONException) {
                        reply.diagnostics?.parsed(items = reply.calls.size, empty = false, error = app.resound.digest.LlmError.Format)
                        JSONObject().put("error", "参数无效，请检查日期与工具参数")
                    } catch (_: java.time.DateTimeException) {
                        JSONObject().put("error", "参数无效，请检查日期与工具参数")
                    }
                }
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result.toString()))
            }
            emit(Update("", emptyList(), "正在整理找到的内容…"))
        }
        emit(Update("", emptyList(), null, "查询次数已达上限，请缩小问题范围"))
    }

    private fun displayText(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isNotEmpty() && NO_RECORDS.startsWith(trimmed)) return ""
        return if (trimmed.startsWith(NO_RECORDS)) trimmed.removePrefix(NO_RECORDS).trimStart() else text
    }
    private companion object { const val NO_RECORDS = "[无需记录]" }

    private fun progress(name: String, args: JSONObject): String {
        val keyword = args.optJSONArray("keywords")?.optString(0)?.take(16)?.takeIf { it.isNotBlank() }
        return when {
            name == "get_day" -> "正在翻 ${args.optString("date").take(10)} 的记录…"
            keyword != null -> "正在找关于‘$keyword’的${if (name == "search_lines") "原话" else "事"}…"
            name == "search_lines" -> "正在翻${args.optString("from").take(16)}的原话…"
            name == "list_items" -> "正在查${args.optString("category").ifBlank { "待办和提取项" }.take(12)}…"
            else -> "正在查找相关事件…"
        }
    }
}
