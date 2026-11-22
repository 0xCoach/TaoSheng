package app.resound.net

/**
 * 卡 220：429 不一定是“太频繁”（免费模型并发限制、模型过载、欠费都可能回 429）。
 * 通用兜底，不针对某家服务商：把正文里服务商自己的说明带给用户；说明里提到欠费/余额/额度用完时算 Quota。
 * 认得的写法：{"error":{"message":"..."}}、{"error":"..."}、{"message":"..."}、{"msg":"..."}；认不出时说明为 null，沿用“请求太频繁”。
 */
internal fun rateLimitReason(raw: String): Pair<NetLlmError, String?> {
    val json = runCatching { Obj(raw) }.getOrNull() ?: return NetLlmError.RateLimit to null
    val error = json.optJSONObject("error")
    val message = listOfNotNull(error?.optString("message"), json.optString("error").takeIf { error == null },
        json.optString("message"), json.optString("msg"))
        .map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "null" }?.take(120)
        ?: return NetLlmError.RateLimit to null
    val text = message.lowercase()
    val quota = listOf("欠费", "余额", "额度", "quota", "insufficient", "balance", "billing", "credit").any { it in text }
    return (if (quota) NetLlmError.Quota else NetLlmError.RateLimit) to "服务商拒绝了请求：$message"
}
