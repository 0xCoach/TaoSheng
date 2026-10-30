package app.resound.usage

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 账户余额（元）；[granted]/[toppedUp] 只有 DeepSeek 有，自定义接口为 null；[updatedAt] 为查询成功的时刻 */
data class LlmBalance(val total: Double, val granted: Double?, val toppedUp: Double?, val currency: String, val available: Boolean, val updatedAt: Long)

/**
 * 去哪查余额：[path] 为 null 时按 DeepSeek 官方格式解析；否则取返回 JSON 里该路径的数（如 data.totalBalance，数组用 data.list.0.balance）。
 * 都用整理模型的同一把密钥（Authorization: Bearer）。
 */
data class BalanceSource(val url: String, val key: String, val path: String?)

data class LlmBalanceState(val balance: LlmBalance? = null, val error: String? = null, val loading: Boolean = false, val supported: Boolean = false)

/**
 * 查 DeepSeek 余额（官方 GET /user/balance，用整理模型的同一把密钥，不扣费）。
 * 只在打开用量页和每轮整理后查，最多每小时一次；余额低于提醒线时提醒一次，回到线上后才会再提醒。
 */
class LlmBalanceChecker(
    private val prefs: SharedPreferences,
    /** 当前整理模型的 (接口地址, 密钥, 是否自定义)；没填密钥返回 null */
    private val current: () -> Triple<String, String, Boolean>?,
    private val alert: suspend (LlmBalance, Double) -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _custom = MutableStateFlow(prefs.getString("customUrl", "").orEmpty() to prefs.getString("customPath", "").orEmpty())
    /** 自定义模型的余额查询地址和字段路径（空表示不查） */
    val custom = _custom.asStateFlow()
    private val _state = MutableStateFlow(LlmBalanceState(cached(), supported = source() != null))

    /** 当前能查余额的来源：DeepSeek 自动；自定义模型要填了查询地址和字段 */
    fun source(): BalanceSource? {
        val (base, key, isCustom) = current() ?: return null
        if (isDeepSeek(base) && !isCustom) return BalanceSource(base.trimEnd('/').removeSuffix("/v1") + "/user/balance", key, null)
        val (url, path) = _custom.value
        if (isCustom && url.isNotBlank() && path.isNotBlank()) return BalanceSource(url.trim(), key, path.trim())
        if (isCustom && isDeepSeek(base) && url.isBlank()) return BalanceSource(base.trimEnd('/').removeSuffix("/v1") + "/user/balance", key, null)
        return null
    }

    fun setCustom(url: String, path: String) {
        check(prefs.edit().putString("customUrl", url.trim()).putString("customPath", path.trim()).remove("cached").putBoolean("alerted", false).commit())
        _custom.value = url.trim() to path.trim()
        _state.value = LlmBalanceState(supported = source() != null)
    }

    /** 不保存，只试一下能不能取到余额 */
    suspend fun test(url: String, path: String): Result<LlmBalance> {
        val key = current()?.second ?: return Result.failure(IllegalStateException("请先填写整理模型的密钥"))
        return runCatching { fetch(BalanceSource(url.trim(), key, path.trim().ifBlank { null })) }
    }
    val state = _state.asStateFlow()
    private val _threshold = MutableStateFlow(prefs.getFloat("threshold", DEFAULT_THRESHOLD.toFloat()).toDouble())
    /** 低于多少元提醒；0 表示不提醒 */
    val threshold = _threshold.asStateFlow()

    fun setThreshold(yuan: Double) {
        val v = yuan.coerceIn(0.0, 10_000.0)
        check(prefs.edit().putFloat("threshold", v.toFloat()).putBoolean("alerted", false).commit())
        _threshold.value = v
    }

    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val cred = source()
        if (cred == null) { _state.value = LlmBalanceState(supported = false); return@withLock }
        val last = _state.value.balance?.updatedAt ?: 0
        if (!force && now() - last < MIN_INTERVAL) { _state.value = _state.value.copy(supported = true); return@withLock }
        _state.value = _state.value.copy(loading = true, supported = true, error = null)
        val result = runCatching { fetch(cred) }
        result.onSuccess { b ->
            check(prefs.edit().putString("cached", JSONObject().put("total", b.total).put("granted", b.granted ?: JSONObject.NULL).put("toppedUp", b.toppedUp ?: JSONObject.NULL)
                .put("currency", b.currency).put("available", b.available).put("at", b.updatedAt).toString()).commit())
            _state.value = LlmBalanceState(b, supported = true)
            checkAlert(b)
        }.onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "余额查询失败") }
    }

    private suspend fun checkAlert(b: LlmBalance) {
        val line = _threshold.value
        val low = line > 0 && (b.total < line || !b.available)
        val alerted = prefs.getBoolean("alerted", false)
        if (low && !alerted) { runCatching { alert(b, line) }; prefs.edit().putBoolean("alerted", true).apply() }
        if (!low && alerted) prefs.edit().putBoolean("alerted", false).apply()
    }

    internal suspend fun fetch(src: BalanceSource): LlmBalance = withContext(Dispatchers.IO) {
        val url = src.url.toHttpUrlOrNull() ?: error("查询地址格式不对")
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${src.key}").header("Accept", "application/json").get().build()).execute().use { r ->
            if (r.code == 401) error("密钥无效，请检查整理模型的密钥")
            if (!r.isSuccessful) error("余额查询失败（${r.code}）")
            val body = r.body?.string().orEmpty()
            if (src.path == null) parse(body, now()) else LlmBalance(pick(body, src.path), null, null, "CNY", true, now())
        }
    }

    private fun cached(): LlmBalance? = prefs.getString("cached", null)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let {
        LlmBalance(it.getDouble("total"), it.optDouble("granted").takeIf { v -> !v.isNaN() }, it.optDouble("toppedUp").takeIf { v -> !v.isNaN() }, it.getString("currency"), it.getBoolean("available"), it.getLong("at"))
    }

    companion object {
        const val DEFAULT_THRESHOLD = 10.0
        const val MIN_INTERVAL = 60 * 60_000L

        /** 多币种时优先人民币 */
        fun parse(body: String, at: Long): LlmBalance {
            val json = JSONObject(body)
            val infos = json.getJSONArray("balance_infos")
            val all = (0 until infos.length()).map { infos.getJSONObject(it) }
            val pick = all.firstOrNull { it.optString("currency") == "CNY" } ?: all.firstOrNull() ?: error("没有余额信息")
            fun num(k: String) = pick.optString(k, "0").toDoubleOrNull() ?: 0.0
            return LlmBalance(num("total_balance"), num("granted_balance"), num("topped_up_balance"), pick.optString("currency", "CNY"),
                json.optBoolean("is_available", true), at)
        }

        /** 按路径取数：a.b.0.c；数或数字字符串都行 */
        fun pick(body: String, path: String): Double {
            var node: Any? = runCatching { JSONObject(body) }.getOrNull() ?: runCatching { org.json.JSONArray(body) }.getOrNull() ?: error("返回的不是 JSON")
            for (part in path.split('.').filter { it.isNotBlank() }) {
                node = when (val n = node) {
                    is JSONObject -> if (n.has(part)) n.get(part) else error("返回结果里没有“$part”")
                    is org.json.JSONArray -> part.toIntOrNull()?.let { i -> if (i in 0 until n.length()) n.get(i) else null } ?: error("“$part”不是有效的序号")
                    else -> error("“$part”之前已经不是对象了")
                }
            }
            return when (val v = node) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null } ?: error("“$path”不是数字")
        }

        /** 常见服务的余额接口，按模型地址自动填；拿不准的让用户自己填 */
        fun suggest(base: String): Pair<String, String>? {
            val host = runCatching { java.net.URI(base.trim()).host }.getOrNull() ?: return null
            return when {
                host.endsWith("siliconflow.cn") -> "https://api.siliconflow.cn/v1/user/info" to "data.totalBalance"
                host.endsWith("moonshot.cn") -> "https://api.moonshot.cn/v1/users/me/balance" to "data.available_balance"
                host.endsWith("deepseek.com") -> "https://api.deepseek.com/user/balance" to "balance_infos.0.total_balance"
                else -> null
            }
        }

        /** 只认 DeepSeek 官方地址 */
        fun isDeepSeek(base: String) = runCatching { java.net.URI(base.trim()).host?.endsWith("deepseek.com") == true }.getOrDefault(false)
    }
}
