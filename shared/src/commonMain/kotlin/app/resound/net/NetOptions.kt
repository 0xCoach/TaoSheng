package app.resound.net

import app.resound.digest.LlmPreset

/** 按接口域名和模型能力适配，不能按用户自定 id 判断协议。依据见 076 调研报告。 */
/**
 * [thinking]：整理时用户打开了“整理前先思考”。只对能明确开关思考的模型生效（DeepSeek 系、火山方舟、智谱 GLM-4.5 及以上、
 * 硅基流动这两个），打开时放宽输出上限（思考过程也算输出），关闭时明确关掉；其他模型照旧。
 */
internal fun applyNetOptions(preset: LlmPreset, body: Obj, thinking: Boolean = false) {
    val host = runCatching { io.ktor.http.Url(preset.baseUrl).host.lowercase() }.getOrDefault("")
    val model = preset.model.lowercase()
    val stream = body.optBoolean("stream")
    fun noThinking() { body.put("thinking", Obj().put("type", "disabled")) }
    fun completionLimit(limit: Int) { body.remove("max_tokens"); body.put("max_completion_tokens", limit) }
    fun think() { body.put("thinking", Obj().put("type", "enabled")); body.put("max_tokens", NetLlmClient.THINKING_JSON_OUTPUT_TOKENS) }
    // 中转服务（如 VIPDLab）接的 DeepSeek V4 Flash 也默认会思考：按模型名认，不只认官方域名
    val deepseekFlash = model == "deepseek-flash" || model.startsWith("deepseek-v4") ||
        (model.contains("deepseek") && model.contains("v4") && model.contains("flash"))
    // 智谱 GLM-4.5 及以上默认会思考（混合推理）；不明确关掉，长批次的输出会被思考内容吃满而截断。
    // 按模型名认（含中转服务的 zai-org/、z-ai/ 等前缀），不只认官方域名。
    val glmThinking = isGlmHybridReasoning(model)
    when {
        host != "api.siliconflow.cn" && deepseekFlash -> if (thinking) think() else noThinking()
        host == "ark.cn-beijing.volces.com" -> if (thinking) think() else noThinking()
        host == "api.siliconflow.cn" && model in setOf("qwen/qwen3.5-35b-a3b", "deepseek-ai/deepseek-v4.1-flash") ->
            if (thinking) { body.put("enable_thinking", true); body.put("max_tokens", NetLlmClient.THINKING_JSON_OUTPUT_TOKENS) }
            else body.put("enable_thinking", false)
        host == "api.moonshot.cn" && model.startsWith("kimi-k3") -> {
            body.remove("temperature"); body.put("reasoning_effort", "low"); completionLimit(16_384)
        }
        host == "api.openai.com" && (model.startsWith("gpt-5.4") || model.startsWith("gpt-5.6")) -> {
            body.remove("temperature"); body.put("reasoning_effort", "none")
            completionLimit(if (stream) 1800 else NetLlmClient.MAX_JSON_OUTPUT_TOKENS)
        }
        // GLM-5.3 只有思考模式（官方不能关），照旧开启并放宽上限
        host == "open.bigmodel.cn" && model.startsWith("glm-5.3") -> {
            body.put("temperature", 1.0).put("top_p", 0.95)
                .put("thinking", Obj().put("type", "enabled").put("clear_thinking", false))
                .put("max_tokens", maxOf(16_384, NetLlmClient.THINKING_JSON_OUTPUT_TOKENS))
            if (stream) body.put("tool_stream", true)
        }
        // 其余 GLM-4.5 及以上（4.5/4.6/4.7…，含中转）：照“整理前先思考”开关走，以前没处理，默认在思考
        glmThinking && host == "api.siliconflow.cn" -> {
            body.put("enable_thinking", thinking)
            if (thinking) body.put("max_tokens", NetLlmClient.THINKING_JSON_OUTPUT_TOKENS)
        }
        glmThinking -> if (thinking) {
            body.put("thinking", Obj().put("type", "enabled").put("clear_thinking", false))
            body.put("max_tokens", NetLlmClient.THINKING_JSON_OUTPUT_TOKENS)
        } else noThinking()
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

/** Android 旧网络门面的 JSON 文本适配入口，复用同一组选项规则。 */
fun llmOptionsJson(preset: LlmPreset, body: String, thinking: Boolean = false): String =
    Obj(body).also { applyNetOptions(preset, it, thinking) }.toString()

/** 智谱 GLM-4.5 及以上（4.5 / 4.6 / 4.7 / 5.x…）：支持用 thinking.type 开关思考。允许前缀如 zai-org/glm-4.7 */
internal fun isGlmHybridReasoning(model: String): Boolean {
    val m = Regex("""(?:^|/)glm-(\d+)(?:\.(\d+))?""").find(model.lowercase()) ?: return false
    val major = m.groupValues[1].toIntOrNull() ?: return false
    val minor = m.groupValues[2].toIntOrNull() ?: 0
    return major > 4 || (major == 4 && minor >= 5)
}
