package app.resound.ask.core
import app.resound.net.*

/** 只允许固定原因落盘，绝不保存异常正文、问题、原话或凭证。 */
enum class AskFailure(val human: String) {
    ToolFormat("模型调用工具时返回的格式不对"),
    ToolRejected("模型服务拒绝了带工具的请求"),
    AnswerFormat("模型未能输出有效回答，请换个模型重试"),
    Timeout("查询超时，请重试"),
    Query("查询失败，请重试"),
    NoLookup("模型没查记录就作答"),
    Citation("回答出处无法核实"),
    Empty("没有收到回答，请重试"),
    Truncated(NetLlmException.TRUNCATED),
    Auth("大模型密钥无效或没开通"), Quota("大模型余额不足或额度已用完"), RateLimit("请求太频繁，请稍后再试"),
    Network("网络连接失败，等待重试"), Server("大模型服务暂时不可用，等待重试");

    companion object {
        fun from(error: NetLlmException, withTools: Boolean = true): AskFailure = when {
            error.truncated -> Truncated
            error.status == 400 && withTools -> ToolRejected
            error.type == NetLlmError.Format -> if (withTools) ToolFormat else AnswerFormat
            else -> when (error.type) {
                NetLlmError.Auth -> Auth
                NetLlmError.Quota -> Quota
                NetLlmError.RateLimit -> RateLimit
                NetLlmError.Network -> Network
                else -> Server
            }
        }
    }
}

/** 已知兼容标记也可在备用服务真正被选中时触发，不伪造一次实际失败。 */
class AskPrefetchRequired(val reason: AskFailure, val actualFailure: Boolean = true) : RuntimeException()

data class AskFailureRecord(val reason: AskFailure, val at: Long)
data class AskCompatibilityState(val fallback: AskFailureRecord? = null, val lastFailure: AskFailureRecord? = null)

