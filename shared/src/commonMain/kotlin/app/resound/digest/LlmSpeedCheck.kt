package app.resound.digest

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 固定合成资料，约 3000 字；不读用户的记录，只有显式检测才调用。 */
object LlmSpeedCheck {
    const val FEATURE = "整理速度"
    const val SYSTEM = "你是会议整理助手，只输出 JSON 对象：{\"events\":[{\"from\":1,\"to\":2,\"title\":\"主题\",\"summary\":\"摘要\",\"points\":[\"要点\"]}],\"items\":[]}。" +
        "from/to 是原话编号。按议题整理所有原话，保留决定、行动、时间和负责人，不编造事实。输入全是合成资料。"
    val SAMPLE: String = (1..34).joinToString("\n") { i ->
        "[$i] 09:${(i % 60).toString().padStart(2, '0')}:00 同事甲：这是第${i}项模拟会议议题。我们决定周五完成展示样稿，由同事乙核对文字和配图，周四先检查进展。如果素材还不齐，就记录缺失清单，不要提前宣布完成。"
    }
    data class Reply(val content: String, val reasoningChars: Int)
    data class Result(val ok: Boolean, val detail: String)
    fun detail(elapsedMillis: Long, reasoning: Boolean): String =
        "约 ${(elapsedMillis.coerceAtLeast(0) + 500) / 1000} 秒一批（约 3000 字），${if (reasoning) "会思考" else "不思考"}"
    suspend fun run(now: () -> Long, request: suspend (String, String) -> Reply, failure: (Exception) -> String): Result {
        val started = now()
        return try {
            val reply = request(SYSTEM, SAMPLE)
            val parsed = runCatching { Json.parseToJsonElement(reply.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject }
                .getOrNull()
            val valid = parsed?.get("events") is JsonArray && parsed["items"] is JsonArray
            val text = detail(now() - started, reply.reasoningChars > 0)
            Result(valid, if (valid) text else "$text；输出格式不可解析")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result(false, "整理速度检测未完成：${failure(e)}") }
    }
}
