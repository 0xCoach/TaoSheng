package app.resound.digest

import app.resound.diagnostics.FailureNotes

import app.resound.ask.AskCompatibility
import app.resound.skills.SkillFiles
import app.resound.transcribe.EndpointKind
import app.resound.usage.LlmBalanceChecker

import android.content.Context
import app.resound.transcribe.OpenAiEndpoint
import app.resound.transcribe.fetchRemoteModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 单独注入密钥存储，迁移只查存在性，不解密、不重写历史密文。 */
internal interface ProviderKeys {
    fun has(id: String): Boolean
    fun read(id: String): String
    fun write(id: String, key: String)
    fun remove(id: String)
}

private class EncryptedProviderKeys(context: Context) : ProviderKeys {
    private val store = SecretStore(context, "digest-secrets", "resound-llm-aes-v1")
    override fun has(id: String) = store.has("key-$id")
    override fun read(id: String) = store.read("key-$id")
    override fun write(id: String, key: String) = store.write("key-$id", key)
    override fun remove(id: String) = store.remove("key-$id")
}

class AndroidLlmProviders internal constructor(
    context: Context,
    private val client: LlmClient,
    private val keys: ProviderKeys,
    internal val headerStore: ServiceHeaders = ServiceHeaders(context),
    private val recordUsage: suspend (LlmReply) -> Unit = {},
) : LlmProviders {
    constructor(context: Context, client: LlmClient, recordUsage: suspend (LlmReply) -> Unit = {}) :
        this(context, client, EncryptedProviderKeys(context), recordUsage = recordUsage)

    fun headers(id: String) = headerStore.headers(id)
    fun requestHeaders(preset: LlmPreset) = headerStore.forPreset(preset)
    fun hasHeaderAuth(id: String) = runCatching { headers(id).any { it.value.isNotBlank() } }.onFailure {
        FailureNotes.record("digest/AndroidLlmProviders.kt:48", it)
    }.getOrDefault(false)
    fun saveHeaders(id: String, headers: List<HeaderEntry>) = synchronized(guard) {
        require(saved.value.any { it.id == id }) { "服务商不存在，请先保存服务商" }
        headerStore.save(id, headers)
        publish(saved.value, activeId.value, id)
    }
    internal val guard = Any()
    private val prefs = context.getSharedPreferences("llm-providers", Context.MODE_PRIVATE)
    val askCompatibility = AskCompatibility(context)
    override val templates = parseTemplates(context.assets.open("llm-providers.json").bufferedReader().use { it.readText() }.also(LlmInputBudget::configure))
    private val listeners = mutableListOf<(String?) -> Unit>()
    /** 必须先将技能引用落盘，删除失败时不移除服务商/密钥。 */
    var beforeDelete: (String) -> Unit = { id -> SkillFiles(context).resetProviders(setOf(id)) }
    private val _saved: MutableStateFlow<List<SavedProvider>>
    private val _activeId: MutableStateFlow<String>

    /** 官方条目就绪态由 AppGraph 经 OfficialAi 状态刷新维护：开启+已配置+已同意才算可用。 */
    internal var officialReady: () -> Boolean = { false }
    /** 官方套餐（free/pro 等），由 AppGraph 挂 officialAi.status?.plan；null=状态未拉到 */
    internal var officialPlan: () -> String? = { null }
    private fun officialEntry() = SavedProvider(OFFICIAL_PROVIDER_ID, null, "声声不息官方 AI", OFFICIAL_PROVIDER_ID, OFFICIAL_PROVIDER_ID, officialReady())
    private fun withOfficial(list: List<SavedProvider>) = if (list.any { it.id == OFFICIAL_PROVIDER_ID }) list else list + officialEntry()

    init {
        if (!prefs.contains("saved")) migrate(context)
        // 损坏时拒绝覆盖，以免把仍在的密钥变成无法找到的孤儿。
        val list = withOfficial(decode(requireNotNull(prefs.getString("saved", null))))
        require(list.isNotEmpty() && list.map { it.id }.distinct().size == list.size) { "服务商配置损坏，请保留数据后恢复备份" }
        _saved = MutableStateFlow(list)
        _activeId = MutableStateFlow(prefs.getString("activeId", null)?.takeIf { id -> list.any { it.id == id } } ?: list.first().id)
    }
    override val saved = _saved.asStateFlow()
    override val activeId = _activeId.asStateFlow()

    private fun migrate(context: Context) {
        val old = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
        val balance = context.getSharedPreferences("llm-balance", Context.MODE_PRIVATE)
        val selected = old.getString("preset", "deepseek") ?: "deepseek"
        val deepseek = templates.first { it.id == "deepseek" }
        val list = mutableListOf<SavedProvider>()
        fun addDeepSeek() { list += deepseek.saved("deepseek", keys.has("deepseek")) }
        if (keys.has("deepseek")) addDeepSeek()
        val base = old.getString("customEndpoint", "").orEmpty()
        val model = old.getString("customModel", "").orEmpty()
        if (base.isNotBlank() || model.isNotBlank() || keys.has("custom")) {
            val oldUrl = balance.getString("customUrl", "").orEmpty()
            val oldPath = balance.getString("customPath", "").orEmpty()
            val suggested = LlmBalanceChecker.suggest(base)?.takeIf {
                oldUrl.isBlank() && oldPath.isBlank() && LlmBalanceChecker.isDeepSeek(base)
            }
            list += SavedProvider("custom", null, "自定义", base, model, keys.has("custom"),
                oldUrl.ifBlank { suggested?.first.orEmpty() }, oldPath.ifBlank { suggested?.second.orEmpty() })
        }
        // 更早版本的豆包也沿用原 id 和原密文，不能解密失败后迁移成空密钥。
        if (selected == "doubao") list += templates.first { it.id == "ark" }.saved("doubao", keys.has("doubao"))
        if (list.isEmpty() || (selected == "deepseek" && list.none { it.id == selected })) addDeepSeek()
        val active = selected.takeIf { id -> list.any { it.id == id } } ?: list.first().id
        val placeholder = list.size == 1 && list.first().id == "deepseek" && !list.first().hasKey
        persist(list, active, placeholder)
    }

    private fun ProviderTemplate.saved(id: String, hasKey: Boolean) = SavedProvider(id, this.id, name, baseUrl, models.first(), hasKey,
        balance?.url.orEmpty(), balance?.path.orEmpty())
    private fun decode(text: String): List<SavedProvider> = JSONArray(text).let { a -> (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val id = o.getString("id")
        SavedProvider(id, o.nullString("templateId"), o.getString("name"), o.getString("baseUrl"), o.getString("model"), keys.has(id),
            o.optString("balanceUrl"), o.optString("balancePath"))
    } }
    private fun persist(list: List<SavedProvider>, active: String, placeholder: Boolean = prefs.getBoolean("placeholder", false)) {
        val data = JSONArray(list.map { p -> JSONObject().put("id", p.id).put("templateId", p.templateId ?: JSONObject.NULL)
            .put("name", p.name).put("baseUrl", p.baseUrl).put("model", p.model)
            .put("balanceUrl", p.balanceUrl).put("balancePath", p.balancePath) })
        check(prefs.edit().putString("saved", data.toString()).putString("activeId", active).putBoolean("placeholder", placeholder).commit()) { "服务商保存失败，请重试" }
    }
    internal fun listen(listener: (String?) -> Unit) = synchronized(guard) { listeners += listener }
    /** 官方 AI 就绪态变化后重发列表（hasKey 只随 publish 重算）。 */
    internal fun refreshOfficial() = synchronized(guard) {
        val wasReady = saved.value.firstOrNull { it.id == OFFICIAL_PROVIDER_ID }?.hasKey == true
        publish(saved.value, activeId.value, null)
        // 登录授权就绪瞬间：当前服务商没填密钥不可用，默认切到官方 AI——
        // 只在 false→true 沿触发，用户之后手动选回其他服务商不被覆盖。
        if (!wasReady && officialReady()) {
            val cur = selected()
            if (cur.id != OFFICIAL_PROVIDER_ID && !cur.hasKey && !cur.preset().allowsEmptyKey() && !hasHeaderAuth(cur.id)) {
                activate(OFFICIAL_PROVIDER_ID)
            }
        }
    }
    private fun publish(list: List<SavedProvider>, active: String, changed: String?) {
        changed?.let(askCompatibility::changed)
        _saved.value = withOfficial(list).map { it.copy(hasKey = if (it.id == OFFICIAL_PROVIDER_ID) officialReady() else keys.has(it.id)) }
        _activeId.value = active
        listeners.forEach { it(changed) }
    }
    internal fun selected(): SavedProvider = synchronized(guard) { saved.value.first { it.id == activeId.value } }
    internal fun credentials(id: String? = null): Pair<LlmPreset, String> = synchronized(guard) {
        val p = saved.value.firstOrNull { it.id == id } ?: selected()
        p.preset() to readKey(p.id)
    }
    internal fun readKey(id: String): String = synchronized(guard) {
        // 系统暂不能解密时返回未就绪，但不清除或覆盖原密文。
        try { keys.read(id) } catch (caught: Exception) {
            FailureNotes.record("digest/AndroidLlmProviders.kt:129", caught)
            "" }
    }

    override suspend fun save(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = saveNow(id, templateId, name, baseUrl, model, key, balanceUrl, balancePath)

    internal fun saveNow(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = synchronized(guard) {
        require(id == null || saved.value.any { it.id == id }) { "服务商已删除，请重新添加" }
        require(templateId == null || templates.any { it.id == templateId }) { "服务商模板不存在" }
        require(id != OFFICIAL_PROVIDER_ID) { "官方 AI 条目不可编辑" }
        require(name.isNotBlank() && model.isNotBlank()) { "请填写名称和模型" }
        validateBase(baseUrl); validateBalance(balanceUrl, balancePath)
        val value = key?.trim(); validateKey(value)
        val target = id ?: UUID.randomUUID().toString()
        val p = SavedProvider(target, templateId, name.trim(), baseUrl.trim().trimEnd('/'), model.trim(), keys.has(target), balanceUrl.trim(), balancePath.trim())
        val list = saved.value.filterNot { it.id == target }.toMutableList().apply {
            val index = saved.value.indexOfFirst { it.id == target }
            if (index < 0) add(p) else add(index, p)
        }
        // 先确保持久配置可引用这个 id；密钥写入失败不删除原密文，重试仍能找到它。
        val active = if (saved.value.isEmpty() || prefs.getBoolean("placeholder", false)) target else activeId.value
        persist(list, active, false)
        try { if (value != null) { if (value.isEmpty()) keys.remove(target) else keys.write(target, value) } }
        finally { publish(list, active, target) }
        target
    }

    override fun move(id: String, toIndex: Int) = synchronized(guard) {
        val list = saved.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        require(index >= 0) { "服务商不存在" }
        val item = list.removeAt(index)
        list.add(toIndex.coerceIn(0, list.size), item)
        persist(list, activeId.value)
        publish(list, activeId.value, null)
    }
    internal var onTestSuccess: (String) -> Unit = {}

    override fun activate(id: String) = synchronized(guard) {
        require(saved.value.any { it.id == id }) { "服务商不存在" }
        if (id != activeId.value) { persist(saved.value, id); publish(saved.value, id, null) }
    }
    override fun delete(id: String) = synchronized(guard) {
        require(id != OFFICIAL_PROVIDER_ID) { "官方 AI 条目不能删除" }
        require(id != activeId.value) { "不能删除当前服务商，请先切换" }
        if (saved.value.none { it.id == id }) return@synchronized
        beforeDelete(id)
        val list = saved.value.filterNot { it.id == id }
        persist(list, activeId.value)
        try { keys.remove(id); headerStore.save(id, emptyList()) } finally { publish(list, activeId.value, id) }
    }
    internal fun setKey(key: String) = synchronized(guard) {
        val value = key.trim(); validateKey(value)
        val id = activeId.value
        require(id != OFFICIAL_PROVIDER_ID) { "官方 AI 不需要密钥" }
        if (value.isEmpty()) keys.remove(id) else keys.write(id, value)
        check(prefs.edit().putBoolean("placeholder", false).commit())
        publish(saved.value, id, id)
    }
    internal fun setBalance(url: String, path: String) = synchronized(guard) {
        validateBalance(url, path)
        val id = activeId.value
        val list = saved.value.map { if (it.id == id) it.copy(balanceUrl = url.trim(), balancePath = path.trim()) else it }
        persist(list, id); publish(list, id, id)
    }
    /** 保留旧界面的两档入口；只在明确选中时补建，不污染新界面的保存列表。 */
    internal fun ensureLegacy(id: String) = synchronized(guard) {
        if (saved.value.any { it.id == id }) return@synchronized
        val p = when (id) {
            "custom" -> SavedProvider(id, null, "自定义", "", "", keys.has(id))
            "deepseek" -> templates.first { it.id == id }.saved(id, keys.has(id))
            else -> return@synchronized
        }
        val list = saved.value + p; persist(list, activeId.value); publish(list, activeId.value, id)
    }

    private fun draftKey(id: String?, key: String?): String = synchronized(guard) {
        key?.trim()?.also(::validateKey) ?: if (id == null) "" else {
            require(saved.value.any { it.id == id }) { "服务商已删除" }; readKey(id)
        }
    }
    override suspend fun test(id: String?, baseUrl: String, model: String, key: String?): Result<String> = try {
        validateBase(baseUrl); require(model.isNotBlank()) { "请填写模型" }
        val p = LlmPreset(id ?: "draft", "测试服务", baseUrl.trim(), model.trim())
        val secret = draftKey(id, key)
        require(p.id == OFFICIAL_PROVIDER_ID || secret.isNotBlank() || p.allowsEmptyKey() || hasHeaderAuth(p.id)) { "还没填写大模型密钥" }
        val reply = client.probeJson(p, secret, "只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"你好\"}")
        withContext(NonCancellable) { recordUsage(reply) }
        id?.let(onTestSuccess)
        Result.success("连接成功")
    } catch (e: CancellationException) { throw e }
    catch (e: LlmException) { Result.failure(IllegalStateException(e.detail)) }
    catch (caught: Exception) {
        FailureNotes.record("digest/AndroidLlmProviders.kt:219", caught)
        Result.failure(IllegalStateException("测试失败，请检查地址、模型和密钥")) }

    override suspend fun models(id: String?, baseUrl: String, key: String?): List<String> = try {
        validateBase(baseUrl); fetchRemoteModels(ServiceHeaders.modelsEndpoint(baseUrl), draftKey(id, key), headers = headerStore.forService(id ?: "draft", baseUrl)).ids
    } catch (e: CancellationException) { throw e }
    catch (caught: Exception) {
        FailureNotes.record("digest/AndroidLlmProviders.kt:224", caught)
        emptyList() }

    companion object {
        internal fun parseTemplates(text: String): List<ProviderTemplate> = JSONArray(text).let { a -> (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val models = o.getJSONArray("models")
            ProviderTemplate(o.getString("id"), o.getString("name"), o.getString("baseUrl"), (0 until models.length()).map(models::getString),
                o.optJSONObject("balance")?.let { BalanceTemplate(it.getString("url"), it.getString("path")) }, o.nullString("keyUrl"), o.nullString("note"))
        }.also { list -> require(list.isNotEmpty() && list.map { it.id }.distinct().size == list.size && list.all { it.models.isNotEmpty() }) } }
        private fun validateBase(base: String) {
            require(!base.contains('{') && !base.contains('}')) { "请先将服务地址中的占位内容替换为自己的业务空间 ID" }
            OpenAiEndpoint.bases(base)
        }
        private fun validateKey(key: String?) { require(key == null || key.all { it.code in 33..126 }) { "密钥格式不正确" } }
        private fun validateBalance(url: String, path: String) {
            require(url.isBlank() == path.isBlank()) { "余额地址和字段路径需一起填写或留空" }
            if (url.isNotBlank()) validateBase(url)
            require(path.isBlank() || path.trim().split('.').all { it.isNotBlank() }) { "余额字段路径不正确" }
        }
    }
}

private fun JSONObject.nullString(key: String) = if (isNull(key)) null else getString(key)
internal fun SavedProvider.preset() = LlmInputBudget.bind(LlmPreset(id, name, baseUrl, model), templateId)
/** 保留旧自定义无密钥语义，新建的局域网服务也可不鉴权；官方条目走账号令牌计费、从不持本地密钥——
 *  免密钥放行后由官方桥直接报真因（未登录/未同意/点数不足），比各处"还没填写密钥"的误导文案准确。 */
fun LlmPreset.allowsEmptyKey() = id == OFFICIAL_PROVIDER_ID || id == "custom" || OpenAiEndpoint.kind(baseUrl) == EndpointKind.LocalHttp
