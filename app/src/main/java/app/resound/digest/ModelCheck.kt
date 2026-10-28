package app.resound.digest

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** 一项检测结果：ok = null 表示前一步没过、这项没测 */
data class ModelCheckItem(val feature: String, val ok: Boolean?, val detail: String)

/**
 * 按功能逐项检测当前模型：连接、按格式回答（整理/回顾/技能/添加待办）、问问的流式回答、工具调用、标注出处。
 * 每项给出具体原因，例如“模型不支持工具调用”，方便排查“回答缺少出处”这类问题。纯逻辑，网络调用由外面传入。
 */
class ModelCheck(
    private val json: suspend (system: String, user: String) -> LlmReply,
    private val stream: suspend (messages: JSONArray, tools: JSONArray) -> LlmStreamReply,
) {
    suspend fun run(): List<ModelCheckItem> {
        val items = mutableListOf<ModelCheckItem>()
        fun skip(vararg features: String) = features.forEach { items += ModelCheckItem(it, null, "前一项没通过，未检测") }

        // 1. 连接 + 流式回答：问问靠它边想边显示
        var streaming = true
        try {
            stream(JSONArray().put(message("user", "只回复一个字：好")), JSONArray())
            items += ModelCheckItem(CONNECT, true, "可用")
            // 2. 按格式回答：整理、回顾、技能、添加待办都要它
            items += checkJson()
            items += ModelCheckItem(STREAM, true, "正常")
        } catch (e: CancellationException) { throw e
        } catch (e: LlmException) {
            fatal(e)?.let { items += ModelCheckItem(CONNECT, false, it); skip(JSON, STREAM, TOOLS, CITE); return items }
            // 流式不行时，用普通请求确认连接本身是好的
            val plain = checkJson()
            if (plain.ok != true && e.status in REJECTED) {
                items += ModelCheckItem(CONNECT, false, "服务拒绝了请求，请检查模型名和服务地址"); skip(JSON, STREAM, TOOLS, CITE); return items
            }
            items += ModelCheckItem(CONNECT, true, "可用")
            items += plain
            items += ModelCheckItem(STREAM, false, "模型不支持流式输出，问问没法用")
            streaming = false
        }

        if (!streaming) { skip(TOOLS, CITE); return items }

        // 3. 工具调用：问问要先查记录才能回答
        val tools = JSONArray().put(TOOL)
        val messages = JSONArray().put(message("system", SYSTEM)).put(message("user", "我们约定的暗号是什么？"))
        val first = try { stream(messages, tools) } catch (e: CancellationException) { throw e
        } catch (e: LlmException) {
            items += ModelCheckItem(TOOLS, false, when {
                e.status in REJECTED -> "模型不支持工具调用"
                e.type == LlmError.Format -> "模型调用工具时返回的格式不对"
                else -> fatal(e) ?: e.type.human
            })
            skip(CITE); return items
        }
        val call = first.calls.firstOrNull { it.name == TOOL_NAME }
        if (call == null) {
            items += ModelCheckItem(TOOLS, false, "模型不支持工具调用：没有去查记录就作答，问问会提示缺少出处")
            skip(CITE); return items
        }
        items += ModelCheckItem(TOOLS, true, "正常")

        // 4. 标注出处：查到后要在结论后写 [^1]，否则问问提示缺少出处
        messages.put(first.message()).put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content",
            JSONObject().put("results", JSONArray().put(JSONObject().put("ref", 1).put("time", "2026-10-28 09:00")
                .put("text", "我们的暗号是蓝鲸"))).toString()))
        items += try {
            val answer = stream(messages, tools)
            when {
                answer.content.contains("[^1]") -> ModelCheckItem(CITE, true, "正常")
                answer.calls.isNotEmpty() -> ModelCheckItem(CITE, false, "模型查完记录后还在反复查，不给回答")
                answer.content.isBlank() -> ModelCheckItem(CITE, false, "模型查完记录后没有给出回答")
                else -> ModelCheckItem(CITE, false, "模型不会按要求标注出处，问问会提示缺少出处")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: LlmException) { ModelCheckItem(CITE, false, fatal(e) ?: e.type.human) }
        return items
    }

    private suspend fun checkJson(): ModelCheckItem = try {
        val reply = json("只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"用一句不超过十个字的中文打个招呼\"}")
        if (runCatching { JSONObject(reply.content) }.isSuccess) ModelCheckItem(JSON, true, "能按格式回答")
        else ModelCheckItem(JSON, false, "模型没按格式回答，整理、回顾、技能会失败")
    } catch (e: CancellationException) { throw e
    } catch (e: LlmException) {
        ModelCheckItem(JSON, false, when {
            e.status in REJECTED -> "模型不支持按格式输出（JSON），整理、回顾、技能会失败"
            e.type == LlmError.Format -> "模型没按格式回答，整理、回顾、技能会失败"
            else -> fatal(e) ?: e.type.human
        })
    }

    /** 这些原因跟具体功能无关，后面不用再测 */
    private fun fatal(e: LlmException): String? = when {
        e.type == LlmError.Auth -> "密钥无效或没开通"
        e.type == LlmError.Network -> "网络连不上大模型服务"
        e.status == 404 -> "找不到这个模型或服务地址，请检查"
        e.status == 429 -> "请求太频繁或额度已用完"
        e.status >= 500 -> "大模型服务暂时不可用，请稍后再检测"
        else -> null
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    companion object {
        const val CONNECT = "连接与密钥"
        const val JSON = "整理、回顾、技能、添加待办"
        const val STREAM = "问问 · 流式回答"
        const val TOOLS = "问问 · 工具调用"
        const val CITE = "问问 · 标注出处"
        private val REJECTED = setOf(400, 405, 415, 422)
        private const val TOOL_NAME = "search_notes"
        private const val SYSTEM = "你是记录查询助手。回答前必须先调用 search_notes 查记录。" +
            "每个事实结论后必须紧跟工具返回的 ref 对应的 [^n]，如 [^1]。直接给简洁的简体中文答案。"
        private val TOOL = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", TOOL_NAME).put("description", "查用户的记录")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("keywords", JSONObject().put("type", "array")
                    .put("items", JSONObject().put("type", "string"))))
                .put("required", JSONArray().put("keywords"))))
    }
}
