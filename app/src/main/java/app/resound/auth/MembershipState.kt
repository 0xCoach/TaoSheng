package app.resound.auth

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 会员与用量本地态：
 * - 成功转写 → 记 pending 用量（持久化，断网不丢）+ 本地累计
 * - 联网后上报 /auth/usage；服务端按 id 去重，重传安全
 * - Free 额度判定 = 服务端已计(缓存) + 本地未上报；Pro 判定 = 离线凭证 caps 含 "pro" 或服务端视图 plan=pro
 * 月份一律用 UTC（与服务端一致），跨设备合并同月额度。
 */
class MembershipState(context: Context, private val store: AuthStore, private val gate: AuthGate) {
    private val prefs = context.getSharedPreferences("membership", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    data class View(
        val plan: String, val proUntil: Long?, val capabilities: List<String>,
        val month: String, val used: Double, val limit: Double, val remaining: Double,
    )

    /** 最近一次服务端会员视图（离线时界面展示用）。 */
    var cachedView: View?
        get() = prefs.getString("view", null)?.let {
            runCatching {
                val o = json.parseToJsonElement(it).jsonObject
                View(
                    plan = o["plan"]?.jsonPrimitive?.content ?: "free",
                    proUntil = o["pro_until"]?.jsonPrimitive?.longOrNull,
                    capabilities = (o["capabilities"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    month = o["month"]?.jsonPrimitive?.content ?: "",
                    used = o["used"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    limit = o["limit"]?.jsonPrimitive?.doubleOrNull ?: 1800.0,
                    remaining = o["remaining"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }.getOrNull()
        }
        private set(v) {
            prefs.edit().putString("view", v?.let {
                """{"plan":"${it.plan}","pro_until":${it.proUntil ?: "null"},"month":"${it.month}","used":${it.used},"limit":${it.limit},"remaining":${it.remaining},"capabilities":${it.capabilities.joinToString(",", "[", "]") { c -> "\"$c\"" }}}"""
            }).apply()
        }

    /** 最近一次成功同步服务端的时间（毫秒）。 */
    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        private set(v) { prefs.edit().putLong("last_sync_at", v).apply() }

    /** 未上报的用量队列：JSON 数组 [{id,minutes,month}]。持久化，断网/杀进程不丢。 */
    private var pending: MutableList<AuthApi.UsageItem>
        get() = prefs.getString("pending", "[]")?.let {
            runCatching {
                json.parseToJsonElement(it).let { el ->
                    (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                        val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val minutes = o["minutes"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                        val month = o["month"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        AuthApi.UsageItem(id, minutes, month)
                    }?.toMutableList()
                }
            }.getOrNull()
        } ?: mutableListOf()
        set(v) {
            prefs.edit().putString("pending", v.joinToString(",", "[", "]") {
                """{"id":"${it.id}","minutes":${it.minutes},"month":"${it.month}"}"""
            }).apply()
        }

    /** UTC 月份（与服务端 usage 账期一致）。 */
    fun currentMonth(): String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().take(7)

    /** 当前账号是否 Pro：凭证能力行或服务端视图任一为准（凭证含 pro 时离线也成立）。 */
    fun isPro(): Boolean {
        if (store.licensePayload?.caps?.contains("pro") == true) {
            // 凭证过期与否由门禁管；这里只判断权益存在
            val until = cachedView?.proUntil
            if (until == null || until > System.currentTimeMillis() / 1000) return true
        }
        val v = cachedView ?: return false
        return v.plan == "pro" && (v.proUntil == null || v.proUntil > System.currentTimeMillis() / 1000)
    }

    /** 本月已用分钟 = 服务端缓存 + 本地未上报（重试期不重复计：pending 只算一次）。 */
    fun usedMinutes(): Double =
        (cachedView?.takeIf { it.month == currentMonth() }?.used ?: 0.0) +
            pending.filter { it.month == currentMonth() }.sumOf { it.minutes }

    fun freeLimit(): Double = cachedView?.takeIf { it.month == currentMonth() }?.limit ?: 1800.0
    fun remainingMinutes(): Double = (freeLimit() - usedMinutes()).coerceAtLeast(0.0)

    /** Free 超额即暂停新转写（录音不受影响）；Pro 或额度余量内放行。 */
    fun allowTranscribe(): Boolean = isPro() || usedMinutes() < freeLimit()

    /** 转写成功后记账：片段 id 天然幂等（重转/重试用同 id，服务端去重）。 */
    fun recordSuccess(segmentId: Long, durationMillis: Long) {
        if (durationMillis <= 0) return
        val item = AuthApi.UsageItem("seg:$segmentId", durationMillis / 60000.0, currentMonth())
        synchronized(this) {
            val list = pending
            if (list.none { it.id == item.id }) { list.add(item); pending = list }
        }
    }

    /** 联网上报 pending；服务端去重后返回最新视图，全部出列并刷新缓存。 */
    suspend fun flush(): Boolean {
        val batch = pending.take(500)
        if (batch.isEmpty()) { sync(); return true }
        return when (val r = gate.reportUsage(batch)) {
            is AuthApi.Result.Ok -> {
                val sent = batch.map { it.id }.toSet()
                pending = pending.filter { it.id !in sent }.toMutableList()
                applyView(r.body)
                true
            }
            else -> false // 网络失败/未授权：留在队列，下轮再传
        }
    }

    /** 拉一次服务端会员视图（界面打开/登录成功后调用）。 */
    suspend fun sync(): Boolean = when (val r = gate.membership()) {
        is AuthApi.Result.Ok -> { applyView(r.body); true }
        else -> false
    }

    private fun applyView(body: kotlinx.serialization.json.JsonObject) {
        val usage = body["usage"]?.jsonObject
        cachedView = View(
            plan = body["plan"]?.jsonPrimitive?.content ?: "free",
            proUntil = body["pro_until"]?.jsonPrimitive?.longOrNull,
            capabilities = runCatching {
                body["capabilities"]!!.let { el ->
                    (el as kotlinx.serialization.json.JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull }
                }
            }.getOrDefault(emptyList()),
            month = usage?.get("month")?.jsonPrimitive?.content ?: currentMonth(),
            used = usage?.get("used_minutes")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            limit = usage?.get("free_limit_minutes")?.jsonPrimitive?.doubleOrNull ?: 1800.0,
            remaining = usage?.get("remaining")?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
        lastSyncAt = System.currentTimeMillis()
    }
}
