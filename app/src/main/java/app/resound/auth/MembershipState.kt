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

    /** 队列条目：acct=记账时登录账号；只能由同账号会话上报，切号不得代传。 */
    private data class PendingEntry(val id: String, val minutes: Double, val month: String, val acct: String)

    /** 未上报的用量队列：JSON 数组 [{id,minutes,month,acct}]。持久化，断网/杀进程不丢。 */
    private var pending: MutableList<PendingEntry>
        get() = prefs.getString("pending", "[]")?.let {
            runCatching {
                json.parseToJsonElement(it).let { el ->
                    (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                        val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val minutes = o["minutes"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                        val month = o["month"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        // 老条目无 acct：迁移时记到当前会话账号（升级期同一账号场景为主）
                        PendingEntry(id, minutes, month,
                            o["acct"]?.jsonPrimitive?.contentOrNull ?: (store.sessionAccountId ?: ""))
                    }?.toMutableList()
                }
            }.getOrNull()
        } ?: mutableListOf()
        set(v) {
            prefs.edit().putString("pending", v.joinToString(",", "[", "]") {
                """{"id":"${it.id}","minutes":${it.minutes},"month":"${it.month}","acct":"${it.acct}"}"""
            }).apply()
        }

    /** UTC 月份（与服务端 usage 账期一致）。 */
    fun currentMonth(): String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().take(7)

    /** 当前账号是否 Pro：离线凭证携带真实 pro_until——到期即落 Free，不被 7 天凭证续命。 */
    fun isPro(): Boolean {
        val nowS = System.currentTimeMillis() / 1000
        val lic = store.licensePayload
        if (lic != null && lic.exp >= nowS && "pro" in lic.caps) {
            val until = lic.proUntil
            if (until == null || until < 0 || until > nowS) return true
            // pro_until 已过 → 权益按真实到期结束
        }
        val v = cachedView ?: return false
        return v.plan == "pro" && (v.proUntil == null || v.proUntil < 0 || v.proUntil > nowS)
    }

    /** 本月已用分钟 = 服务端缓存 + 本账号本地未上报（pending 只算一次，切号不混计）。 */
    fun usedMinutes(): Double =
        (cachedView?.takeIf { it.month == currentMonth() }?.used ?: 0.0) +
            pending.filter { it.month == currentMonth() && it.acct == (store.sessionAccountId ?: "") }
                .sumOf { it.minutes }

    fun freeLimit(): Double = cachedView?.takeIf { it.month == currentMonth() }?.limit ?: 1800.0
    fun remainingMinutes(): Double = (freeLimit() - usedMinutes()).coerceAtLeast(0.0)

    /** Free 超额即暂停新转写（录音不受影响）；Pro 或额度余量内放行。 */
    fun allowTranscribe(): Boolean = isPro() || usedMinutes() < freeLimit()

    /** 转写成功后记账：设备号+片段 id 联合幂等——多设备本地段号可能相同，带 deviceId 防互相吞账；
     *  重转/重试用同 id，服务端按 (user_id,id) 去重；记账绑定当前账号。 */
    fun recordSuccess(segmentId: Long, durationMillis: Long) =
        recordUsage("seg:${store.deviceId}:$segmentId", durationMillis)

    /** 非片段来源（密钥测试等）记账：itemId 由各路径保证唯一。 */
    fun recordUsage(itemId: String, durationMillis: Long) {
        if (durationMillis <= 0) return
        val entry = PendingEntry(itemId, durationMillis / 60000.0, currentMonth(), store.sessionAccountId ?: "")
        synchronized(this) {
            val list = pending
            if (list.none { it.id == entry.id }) { if (list.size < 2000) list.add(entry); pending = list }
        }
    }

    /** 联网上报 pending（只发当前账号条目，切号不代传）；服务端去重后返回最新视图。 */
    suspend fun flush(): Boolean {
        val acct = store.sessionAccountId ?: return false // 未登录不上报
        val batch = pending.filter { it.acct == acct }.take(500)
        if (batch.isEmpty()) { sync(); return true }
        return when (val r = gate.reportUsage(batch.map { AuthApi.UsageItem(it.id, it.minutes, it.month) })) {
            is AuthApi.Result.Ok -> {
                val sent = batch.map { it.id }.toSet()
                pending = pending.filter { it.id !in sent || it.acct != acct }.toMutableList()
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
