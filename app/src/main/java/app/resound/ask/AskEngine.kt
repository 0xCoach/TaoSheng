package app.resound.ask

import app.resound.digest.LlmStreamReply
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
) {
    data class Update(val text: String, val sources: List<AskSource>, val progress: String?, val error: String? = null, val proposal: ProposedChange? = null)

    suspend fun answer(system: String, history: List<AskMessage>, question: String, update: (Update) -> Unit) =
        answer(system, history, question, null, update)

    suspend fun answer(system: String, history: List<AskMessage>, question: String, context: AskContext?, update: (Update) -> Unit) {
        var proposal: ProposedChange? = null
        fun emit(value: Update) = update(value.copy(proposal = proposal))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.takeLast(12).forEach {
            messages.put(JSONObject().put("role", if (it.role == AskRole.User) "user" else "assistant")
                .put("content", AskCitations.withoutMarkers(it.text).take(4000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", listOfNotNull(context?.text, question).joinToString("\n\n")))
        val sources = context?.sources?.toMutableList() ?: mutableListOf()
        var count = 0
        repeat(7) {
            if (count >= 6) messages.put(JSONObject().put("role", "system").put("content",
                "本轮工具额度已用完。现在只根据已有原话出处给最终回答；没查到就说没找到。不再调用工具，不输出工具标记。"))
            var text = ""
            var prompt = 0L; var completion = 0L
            val reply = try {
                stream.call(messages, if (count < 6) definitions else JSONArray(), { delta ->
                    text += delta
                    val parsed = AskCitations.parse(text, sources)
                    if (!text.contains("DSML")) emit(Update(parsed.text, parsed.sources, null))
                }, { p, c -> prompt = p; completion = c })
            } finally {
                // 取消也保留已收到的 usage；未返回 usage 时计一次调用、token 为 0，不捏造用量。
                withContext(NonCancellable) { usage(prompt, completion) }
            }
            if (reply.calls.isEmpty()) {
                val parsed = AskCitations.parse(reply.content, sources)
                if (reply.content.contains("DSML")) {
                    emit(Update("", emptyList(), null, "大模型返回的格式不对")); return
                }
                val error = when {
                    parsed.invalid -> "回答出处不完整，请重新提问"
                    parsed.text.isBlank() -> "没有收到回答，请重试"
                    // 一次都没查记录：多半是模型不支持工具调用，指给用户去检测，而不是只让重问
                    proposal == null && parsed.sources.isEmpty() && !parsed.text.contains("没找到") ->
                        if (count == 0 && sources.isEmpty()) "模型没查记录就作答，可能不支持工具调用，可在设置里检测模型"
                        else "回答缺少出处，请重新提问；常出现可在设置里检测模型"
                    else -> null
                }
                emit(Update(parsed.text, parsed.sources, null, error)); return
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
