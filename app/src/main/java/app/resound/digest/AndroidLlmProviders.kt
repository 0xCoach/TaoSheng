package app.resound.digest

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
    private val recordUsage: suspend (LlmReply) -> Unit = {},
) : LlmProviders {
    constructor(context: Context, client: LlmClient, recordUsage: suspend (LlmReply) -> Unit = {}) :
        this(context, client, EncryptedProviderKeys(context), recordUsage)

    internal val guard = Any()
    private val prefs = context.getSharedPreferences("llm-providers", Context.MODE_PRIVATE)
    override val templates = parseTemplates(context.assets.open("llm-providers.json").bufferedReader().use { it.readText() })
    private val listeners = mutableListOf<(String?) -> Unit>()
    /** 必须先将技能引用落盘，删除失败时不移除服务商/密钥。 */
    var beforeDelete: (String) -> Unit = { id -> app.resound.skills.SkillFiles(context).resetProviders(setOf(id)) }
    private val _saved: MutableStateFlow<List<SavedProvider>>
    private val _activeId: MutableStateFlow<String>

    init {
        if (!prefs.contains("saved")) migrate(context)
        // 损坏时拒绝覆盖，以免把仍在的密钥变成无法找到的孤儿。
        val list = decode(requireNotNull(prefs.getString("saved", null)))
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
            val suggested = app.resound.usage.LlmBalanceChecker.suggest(base)?.takeIf {
                oldUrl.isBlank() && oldPath.isBlank() && app.resound.usage.LlmBalanceChecker.isDeepSeek(base)
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
    private fun publish(list: List<SavedProvider>, active: String, changed: String?) {
        _saved.value = list.map { it.copy(hasKey = keys.has(it.id)) }
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
        try { keys.read(id) } catch (_: Exception) { "" }
    }

    override suspend fun save(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = saveNow(id, templateId, name, baseUrl, model, key, balanceUrl, balancePath)

    internal fun saveNow(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = synchronized(guard) {
        require(id == null || saved.value.any { it.id == id }) { "服务商已删除，请重新添加" }
        require(templateId == null || templates.any { it.id == templateId }) { "服务商模板不存在" }
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

    override fun activate(id: String) = synchronized(guard) {
        require(saved.value.any { it.id == id }) { "服务商不存在" }
        if (id != activeId.value) { persist(saved.value, id); publish(saved.value, id, null) }
    }
    override fun delete(id: String) = synchronized(guard) {
        require(id != activeId.value) { "不能删除当前服务商，请先切换" }
        if (saved.value.none { it.id == id }) return@synchronized
        beforeDelete(id)
        val list = saved.value.filterNot { it.id == id }
        persist(list, activeId.value)
        try { keys.remove(id) } finally { publish(list, activeId.value, id) }
    }
    internal fun setKey(key: String) = synchronized(guard) {
        val value = key.trim(); validateKey(value)
        val id = activeId.value
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
        require(secret.isNotBlank() || p.allowsEmptyKey()) { "还没填写大模型密钥" }
        val reply = client.chatJson(p, secret, "只输出 JSON。", "输出 {\"ok\":true,\"reply\":\"你好\"}")
        withContext(NonCancellable) { recordUsage(reply) }
        Result.success("连接成功")
    } catch (e: CancellationException) { throw e }
    catch (e: LlmException) { Result.failure(IllegalStateException(e.detail)) }
    catch (_: Exception) { Result.failure(IllegalStateException("测试失败，请检查地址、模型和密钥")) }

    override suspend fun models(id: String?, baseUrl: String, key: String?): List<String> = try {
        validateBase(baseUrl); fetchRemoteModels(baseUrl, draftKey(id, key)).ids
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { emptyList() }

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
internal fun SavedProvider.preset() = LlmPreset(id, name, baseUrl, model)
/** 保留旧自定义无密钥语义，新建的局域网服务也可不鉴权。 */
fun LlmPreset.allowsEmptyKey() = id == "custom" || app.resound.transcribe.OpenAiEndpoint.kind(baseUrl) == app.resound.transcribe.EndpointKind.LocalHttp
