package app.resound.digest

import app.resound.net.Obj

/** 检测判定与 JSON 合法性；问问兼容状态和诊断记录留安卓。 */
object ModelCheckRules {
    val rejected = setOf(400, 405, 415, 422)
    fun compatible(type: String, status: Int, truncated: Boolean) = !truncated && (status in rejected || type == "Format")
    fun validJson(content: String) = runCatching { Obj(content) }.isSuccess
    fun fatal(type: String, status: Int, detail: String, human: String): String? = when {
        type == "Auth" -> if (detail != human) detail else "密钥无效或没开通"
        type == "Network" -> detail
        status == 404 -> "找不到这个模型或服务地址，请检查"
        status == 429 -> "请求太频繁或额度已用完"
        status >= 500 -> "大模型服务暂时不可用，请稍后再检测"
        else -> null
    }
}
