package app.resound.usage

import android.content.SharedPreferences
import app.resound.digest.AndroidLlmProviders
import kotlinx.coroutines.CancellationException
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

/** [currency] 为实际币种；自填字段默认人民币，DeepSeek 按返回币种。 */
data class LlmBalance(val total: Double, val granted: Double?, val toppedUp: Double?, val currency: String, val available: Boolean, val updatedAt: Long)
class BalanceSource(val url: String, val key: String, val path: String?)
data class LlmBalanceState(val balance: LlmBalance? = null, val error: String? = null, val loading: Boolean = false, val supported: Boolean = false)

/** 缓存、提醒按保存项 id 隔离；服务商切换或修改后，旧请求不能覆盖新选择。 */
class LlmBalanceChecker(
    private val prefs: SharedPreferences,
    private val providers: AndroidLlmProviders,
    private val alert: suspend (LlmBalance, Double) -> Unit,
    http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val lock = Mutex()
    private var generation = 0L
    private val _custom = MutableStateFlow(providers.selected().let { it.balanceUrl to it.balancePath })
    val custom = _custom.asStateFlow()
    private val _state = MutableStateFlow(LlmBalanceState(cached(providers.activeId.value), supported = source() != null))
    val state = _state.asStateFlow()
    private val _threshold = MutableStateFlow(prefs.getFloat("threshold", DEFAULT_THRESHOLD.toFloat()).toDouble())
    /** 人民币提醒线；0 表示不提醒，非 CNY 账户不按人民币阈值报警。 */
    val threshold = _threshold.asStateFlow()

    init {
        providers.listen { changed ->
            generation++
            if (changed != null) check(prefs.edit().remove("cached-$changed").remove("alerted-$changed").commit())
            val p = providers.selected()
            _custom.value = p.balanceUrl to p.balancePath
            _state.value = LlmBalanceState(cached(p.id), supported = source() != null)
        }
    }

    fun source(): BalanceSource? = synchronized(providers.guard) {
        val p = providers.selected()
        if (!p.hasKey || p.balanceUrl.isBlank() || p.balancePath.isBlank()) return@synchronized null
        val key = providers.readKey(p.id)
        if (key.isBlank()) return@synchronized null
        val dedicated = isDeepSeek(p.balanceUrl) && p.balanceUrl.trimEnd('/').endsWith("/user/balance") &&
            p.balancePath == "balance_infos.0.total_balance"
        BalanceSource(p.balanceUrl, key, if (dedicated) null else p.balancePath)
    }
    /** 保留旧界面入口，保存到当前服务商。 */
    fun setCustom(url: String, path: String) = providers.setBalance(url, path)

    suspend fun test(url: String, path: String): Result<LlmBalance> = try {
        val key = providers.credentials().second
        require(key.isNotBlank())
        Result.success(fetch(BalanceSource(url.trim(), key, path.trim().ifBlank { null })))
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.failure(IllegalStateException("余额测试失败，请检查地址、字段和密钥")) }

    fun setThreshold(yuan: Double) = synchronized(providers.guard) {
        require(yuan.isFinite())
        val v = yuan.coerceIn(0.0, 10_000.0)
        val editor = prefs.edit().putFloat("threshold", v.toFloat())
        prefs.all.keys.filter { it.startsWith("alerted-") }.forEach { editor.remove(it) }
        check(editor.commit()); _threshold.value = v
    }

    suspend fun refresh(force: Boolean = false) = lock.withLock {
        var id = ""; var token = 0L
        val cred = synchronized(providers.guard) {
            id = providers.activeId.value; token = generation
            val src = source()
            if (src == null) _state.value = LlmBalanceState(supported = false)
            else {
                val last = _state.value.balance?.updatedAt
                if (!force && last != null && now() - last in 0 until MIN_INTERVAL) return@withLock
                _state.value = _state.value.copy(loading = true, supported = true, error = null)
            }
            src
        } ?: return@withLock
        var notify: Pair<LlmBalance, Double>? = null
        try {
            val b = fetch(cred)
            synchronized(providers.guard) {
                if (token != generation || id != providers.activeId.value) return@withLock
                val json = JSONObject().put("total", b.total).put("granted", b.granted ?: JSONObject.NULL).put("toppedUp", b.toppedUp ?: JSONObject.NULL)
                    .put("currency", b.currency).put("available", b.available).put("at", b.updatedAt)
                val line = _threshold.value
                val low = b.currency == "CNY" && line > 0 && (b.total < line || !b.available)
                val alerted = prefs.getBoolean("alerted-$id", false)
                check(prefs.edit().putString("cached-$id", json.toString()).putBoolean("alerted-$id", low).commit())
                _state.value = LlmBalanceState(b, supported = true)
                if (low && !alerted) notify = b to line
            }
            notify?.let { (b, line) ->
                if (synchronized(providers.guard) { token == generation && id == providers.activeId.value }) {
                    try { alert(b, line) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { prefs.edit().putBoolean("alerted-$id", false).apply() }
                }
            }
        } catch (e: CancellationException) {
            synchronized(providers.guard) { if (token == generation) _state.value = _state.value.copy(loading = false) }
            throw e
        } catch (_: Exception) {
            synchronized(providers.guard) { if (token == generation) _state.value = _state.value.copy(loading = false, error = "余额查询失败，请检查配置和网络") }
        }
    }

    internal suspend fun fetch(src: BalanceSource): LlmBalance = withContext(Dispatchers.IO) {
        val url = src.url.toHttpUrlOrNull() ?: error("查询地址格式不对")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${src.key}").header("Accept", "application/json").get().build()).execute().use { r ->
            check(r.isSuccessful) { "余额查询失败" }
            val body = r.body?.source() ?: error("余额响应为空")
            body.request(65_537)
            check(body.buffer.size <= 65_536) { "余额响应过大" }
            val content = body.readUtf8()
            if (src.path == null) parse(content, now()) else LlmBalance(pick(content, src.path), null, null, "CNY", true, now())
        }
    }
    private fun cached(id: String): LlmBalance? = runCatching {
        prefs.getString("cached-$id", null)?.let { text ->
            val o = JSONObject(text)
            LlmBalance(o.getDouble("total"), o.optDouble("granted").takeIf { it.isFinite() }, o.optDouble("toppedUp").takeIf { it.isFinite() },
                o.getString("currency"), o.getBoolean("available"), o.getLong("at"))
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_THRESHOLD = 10.0
        const val MIN_INTERVAL = 60 * 60_000L

        /** 多币种时优先人民币 */
        fun parse(body: String, at: Long): LlmBalance {
            val json = JSONObject(body)
            val infos = json.getJSONArray("balance_infos")
            val all = (0 until infos.length()).map { infos.getJSONObject(it) }
            val pick = all.firstOrNull { it.optString("currency") == "CNY" } ?: all.firstOrNull() ?: error("没有余额信息")
            fun num(k: String) = pick.getString(k).toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("余额不是有效数字")
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
            return when (val v = node) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() } ?: error("“$path”不是数字")
        }

        /** 常见服务的余额接口，按模型地址自动填；拿不准的让用户自己填 */
        fun suggest(base: String): Pair<String, String>? {
            val host = runCatching { java.net.URI(base.trim()).host }.getOrNull() ?: return null
            return when {
                host == "api.moonshot.cn" -> "https://api.moonshot.cn/v1/users/me/balance" to "data.available_balance"
                host == "api.deepseek.com" -> "https://api.deepseek.com/user/balance" to "balance_infos.0.total_balance"
                else -> null
            }
        }

        /** 只认 DeepSeek 官方地址 */
        fun isDeepSeek(base: String) = runCatching { java.net.URI(base.trim()).host == "api.deepseek.com" }.getOrDefault(false)
    }
}
