package app.resound.digest

import org.json.JSONObject
import java.net.URI

/** 按接口域名和模型能力适配，不能按用户自定 id 判断协议。依据见 076 调研报告。 */
internal fun applyLlmOptions(preset: LlmPreset, body: JSONObject) {
    val host = runCatching { URI(preset.baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
    val model = preset.model.lowercase()
    val stream = body.optBoolean("stream")
    fun noThinking() { body.put("thinking", JSONObject().put("type", "disabled")) }
    fun completionLimit(limit: Int) { body.remove("max_tokens"); body.put("max_completion_tokens", limit) }
    when {
        host == "api.deepseek.com" && (model == "deepseek-flash" || model.startsWith("deepseek-v4")) -> noThinking()
        host == "ark.cn-beijing.volces.com" -> noThinking()
        host == "api.siliconflow.cn" && model in setOf("qwen/qwen3.5-35b-a3b", "deepseek-ai/deepseek-v4.1-flash") ->
            body.put("enable_thinking", false)
        host == "api.moonshot.cn" && model.startsWith("kimi-k3") -> {
            body.remove("temperature"); body.put("reasoning_effort", "low"); completionLimit(16_384)
        }
        host == "api.openai.com" && (model.startsWith("gpt-5.4") || model.startsWith("gpt-5.6")) -> {
            body.remove("temperature"); body.put("reasoning_effort", "none")
            if (stream) completionLimit(1800)
        }
        host == "open.bigmodel.cn" && model.startsWith("glm-5.3") -> {
            body.put("temperature", 1.0).put("top_p", 0.95)
                .put("thinking", JSONObject().put("type", "enabled").put("clear_thinking", false))
                .put("max_tokens", 16_384)
            if (stream) body.put("tool_stream", true)
        }
        host == "api.minimax.cn" && model.startsWith("minimax-m3") -> {
            body.remove("temperature")
            // 官方兼容契约未提供 response_format；仍要求 JSON，交给既有严格解析/重试判定。
            body.remove("response_format")
            body.put("reasoning_split", true); completionLimit(16_384)
            if (model.startsWith("minimax-m3.1")) body.put("reasoning_effort", "low") else noThinking()
        }
        (host.endsWith(".maas.aliyuncs.com") || host == "dashscope.aliyuncs.com") && model in setOf("qwen-flash", "qwen-plus") ->
            body.put("enable_thinking", false)
        host == "api.stepfun.com" && model == "step-3.5-flash-2603" -> {
            body.put("reasoning_effort", "low").put("max_tokens", 16_384)
        }
        host == "tokenhub.tencentmaas.com" && model in setOf("hy3", "hy4-preview") -> {
            body.put("reasoning_effort", "low").put("max_tokens", 16_384)
        }
    }
}
