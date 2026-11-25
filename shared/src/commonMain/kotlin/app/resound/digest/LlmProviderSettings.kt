package app.resound.digest

import app.resound.net.Arr
import app.resound.net.Obj
import app.resound.platform.KeyValueStores
import app.resound.platform.SecretVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 串行执行同步设置操作。变更回调可在同一调用中重入读取。 */
interface ProviderAccess {
    fun <T> run(action: () -> T): T
}

class LlmProviderSettings(
    stores: KeyValueStores,
    private val vault: SecretVault,
    private val headerStore: SecretHeaders,
    templateJson: String,
    private val newId: () -> String,
    private val validateEndpoint: (String) -> Unit,
    private val isLocalHttp: (String) -> Boolean,
    private val access: ProviderAccess,
    private val legacyBalance: (String) -> Pair<String, String>? = { null },
    private val onConfigurationChanged: (String) -> Unit = {},
) {
    fun headers(id: String) = headerStore.headers(id)
    fun hasHeaderAuth(id: String) = runCatching { headers(id).any { it.value.isNotBlank() } }.getOrDefault(false)
    fun canUseWithoutKey(preset: LlmPreset) = preset.allowsEmptyKey(isLocalHttp) || hasHeaderAuth(preset.id)
    fun saveHeaders(id: String, headers: List<HeaderEntry>) = access.run {
        require(saved.value.any { it.id == id }) { "服务商不存在，请先保存服务商" }
        headerStore.save(id, headers)
        publish(saved.value, activeId.value, id)
    }
    private val prefs = stores.open("llm-providers")
    val templates = parseTemplates(templateJson)
    private val listeners = mutableListOf<(String?) -> Unit>()
    /** 必须先将技能引用落盘，删除失败时不移除服务商/密钥。 */
    var beforeDelete: (String) -> Unit = {}
    private val _saved: MutableStateFlow<List<SavedProvider>>
    private val _activeId: MutableStateFlow<String>

    init {
        if (!prefs.contains("saved")) migrate(stores)
        // 损坏时拒绝覆盖，以免把仍在的密钥变成无法找到的孤儿。
        val list = decode(requireNotNull(prefs.getString("saved", null)))
        require(list.isNotEmpty() && list.map { it.id }.distinct().size == list.size) { "服务商配置损坏，请保留数据后恢复备份" }
        _saved = MutableStateFlow(list)
        _activeId = MutableStateFlow(prefs.getString("activeId", null)?.takeIf { id -> list.any { it.id == id } } ?: list.first().id)
    }
    val saved = _saved.asStateFlow()
    val activeId = _activeId.asStateFlow()

    private fun migrate(stores: KeyValueStores) {
        val old = stores.open("digest")
        val balance = stores.open("llm-balance")
        val selected = old.getString("preset", "deepseek") ?: "deepseek"
        val deepseek = templates.first { it.id == "deepseek" }
        val list = mutableListOf<SavedProvider>()
        fun addDeepSeek() { list += deepseek.saved("deepseek", hasKey("deepseek")) }
        if (hasKey("deepseek")) addDeepSeek()
        val base = old.getString("customEndpoint", "").orEmpty()
        val model = old.getString("customModel", "").orEmpty()
        if (base.isNotBlank() || model.isNotBlank() || hasKey("custom")) {
            val oldUrl = balance.getString("customUrl", "").orEmpty()
            val oldPath = balance.getString("customPath", "").orEmpty()
            val suggested = legacyBalance(base)?.takeIf { oldUrl.isBlank() && oldPath.isBlank() }
            list += SavedProvider("custom", null, "自定义", base, model, hasKey("custom"),
                oldUrl.ifBlank { suggested?.first.orEmpty() }, oldPath.ifBlank { suggested?.second.orEmpty() })
        }
        // 更早版本的豆包也沿用原 id 和原密文，不能解密失败后迁移成空密钥。
        if (selected == "doubao") list += templates.first { it.id == "ark" }.saved("doubao", hasKey("doubao"))
        if (list.isEmpty() || (selected == "deepseek" && list.none { it.id == selected })) addDeepSeek()
        val active = selected.takeIf { id -> list.any { it.id == id } } ?: list.first().id
        val placeholder = list.size == 1 && list.first().id == "deepseek" && !list.first().hasKey
        persist(list, active, placeholder)
    }

    private fun ProviderTemplate.saved(id: String, hasKey: Boolean) = SavedProvider(id, this.id, name, baseUrl, models.first(), hasKey,
        balance?.url.orEmpty(), balance?.path.orEmpty())
    private fun decode(text: String): List<SavedProvider> = Arr(text).let { a -> (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val id = o.getString("id")
        SavedProvider(id, o.nullString("templateId"), o.getString("name"), o.getString("baseUrl"), o.getString("model"), hasKey(id),
            o.optString("balanceUrl"), o.optString("balancePath"))
    } }
    private fun persist(list: List<SavedProvider>, active: String, placeholder: Boolean = prefs.getBoolean("placeholder", false)) {
        val data = Arr(list.map { p -> Obj().put("id", p.id).put("templateId", p.templateId ?: Obj.NULL)
            .put("name", p.name).put("baseUrl", p.baseUrl).put("model", p.model)
            .put("balanceUrl", p.balanceUrl).put("balancePath", p.balancePath) })
        check(prefs.commit {
            putString("saved", data.toString())
            putString("activeId", active)
            putBoolean("placeholder", placeholder)
        }) { "服务商保存失败，请重试" }
    }
    fun listen(listener: (String?) -> Unit) = access.run { listeners += listener }
    private fun publish(list: List<SavedProvider>, active: String, changed: String?) {
        changed?.let(onConfigurationChanged)
        _saved.value = list.map { it.copy(hasKey = hasKey(it.id)) }
        _activeId.value = active
        listeners.forEach { it(changed) }
    }
    fun selected(): SavedProvider = access.run { saved.value.first { it.id == activeId.value } }
    fun credentials(id: String? = null): Pair<SavedProvider, String> = access.run {
        val p = saved.value.firstOrNull { it.id == id } ?: selected()
        p to readKey(p.id)
    }
    fun readKey(id: String): String = access.run {
        // 系统暂不能解密时返回未就绪，但不清除或覆盖原密文。
        runCatching { vault.read(SECRET_GROUP, keyField(id)) }.getOrDefault("")
    }

    fun save(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = access.run {
        require(id == null || saved.value.any { it.id == id }) { "服务商已删除，请重新添加" }
        require(templateId == null || templates.any { it.id == templateId }) { "服务商模板不存在" }
        require(name.isNotBlank() && model.isNotBlank()) { "请填写名称和模型" }
        validateBase(baseUrl); validateBalance(balanceUrl, balancePath)
        val value = key?.trim(); validateKey(value)
        val target = id ?: newId()
        val p = SavedProvider(target, templateId, name.trim(), baseUrl.trim().trimEnd('/'), model.trim(), hasKey(target), balanceUrl.trim(), balancePath.trim())
        val list = saved.value.filterNot { it.id == target }.toMutableList().apply {
            val index = saved.value.indexOfFirst { it.id == target }
            if (index < 0) add(p) else add(index, p)
        }
        // 先确保持久配置可引用这个 id；密钥写入失败不删除原密文，重试仍能找到它。
        val active = if (saved.value.isEmpty() || prefs.getBoolean("placeholder", false)) target else activeId.value
        persist(list, active, false)
        try { if (value != null) { if (value.isEmpty()) vault.remove(SECRET_GROUP, keyField(target)) else vault.write(SECRET_GROUP, keyField(target), value) } }
        finally { publish(list, active, target) }
        target
    }

    fun move(id: String, toIndex: Int) = access.run {
        val list = saved.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        require(index >= 0) { "服务商不存在" }
        val item = list.removeAt(index)
        list.add(toIndex.coerceIn(0, list.size), item)
        persist(list, activeId.value)
        publish(list, activeId.value, null)
    }

    fun activate(id: String) = access.run {
        require(saved.value.any { it.id == id }) { "服务商不存在" }
        if (id != activeId.value) { persist(saved.value, id); publish(saved.value, id, null) }
    }
    fun delete(id: String) = access.run {
        require(id != activeId.value) { "不能删除当前服务商，请先切换" }
        if (saved.value.none { it.id == id }) return@run
        beforeDelete(id)
        val list = saved.value.filterNot { it.id == id }
        persist(list, activeId.value)
        try { vault.remove(SECRET_GROUP, keyField(id)); headerStore.save(id, emptyList()) } finally { publish(list, activeId.value, id) }
    }
    fun setKey(key: String) = access.run {
        val value = key.trim(); validateKey(value)
        val id = activeId.value
        if (value.isEmpty()) vault.remove(SECRET_GROUP, keyField(id)) else vault.write(SECRET_GROUP, keyField(id), value)
        check(prefs.commit { putBoolean("placeholder", false) })
        publish(saved.value, id, id)
    }
    fun setBalance(url: String, path: String) = access.run {
        validateBalance(url, path)
        val id = activeId.value
        val list = saved.value.map { if (it.id == id) it.copy(balanceUrl = url.trim(), balancePath = path.trim()) else it }
        persist(list, id); publish(list, id, id)
    }
    /** 保留旧界面的两档入口；只在明确选中时补建，不污染新界面的保存列表。 */
    fun ensureLegacy(id: String) = access.run {
        if (saved.value.any { it.id == id }) return@run
        val p = when (id) {
            "custom" -> SavedProvider(id, null, "自定义", "", "", hasKey(id))
            "deepseek" -> templates.first { it.id == id }.saved(id, hasKey(id))
            else -> return@run
        }
        val list = saved.value + p; persist(list, activeId.value); publish(list, activeId.value, id)
    }

    fun draftKey(id: String?, key: String?): String = access.run {
        key?.trim()?.also(::validateKey) ?: if (id == null) "" else {
            require(saved.value.any { it.id == id }) { "服务商已删除" }; readKey(id)
        }
    }
    fun validateBase(base: String) {
        require('{' !in base && '}' !in base) {
            "请先将服务地址中的占位内容替换为自己的业务空间 ID"
        }
        validateEndpoint(base)
    }

    private fun validateKey(key: String?) {
        require(key == null || key.all { it.code in 33..126 }) { "密钥格式不正确" }
    }

    private fun validateBalance(url: String, path: String) {
        require(url.isBlank() == path.isBlank()) { "余额地址和字段路径需一起填写或留空" }
        if (url.isNotBlank()) validateBase(url)
        require(path.isBlank() || path.trim().split('.').all { it.isNotBlank() }) { "余额字段路径不正确" }
    }

    private fun keyField(id: String) = "key-$id"
    private fun hasKey(id: String) = vault.has(SECRET_GROUP, keyField(id))

    companion object {
        const val SECRET_GROUP = "digest-secrets"
        fun parseTemplates(text: String): List<ProviderTemplate> = Arr(text).let { a -> (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val models = o.getJSONArray("models")
            ProviderTemplate(o.getString("id"), o.getString("name"), o.getString("baseUrl"), (0 until models.length()).map(models::getString),
                o.optJSONObject("balance")?.let { BalanceTemplate(it.getString("url"), it.getString("path")) }, o.nullString("keyUrl"), o.nullString("note"))
        }.also { list -> require(list.isNotEmpty() && list.map { it.id }.distinct().size == list.size && list.all { it.models.isNotEmpty() }) } }
    }
}

private fun Obj.nullString(key: String) = if (isNull(key)) null else getString(key)

/** 旧自定义选项及局域网 HTTP 服务可以按原规则免密钥。 */
fun LlmPreset.allowsEmptyKey(isLocalHttp: (String) -> Boolean) = id == "custom" || isLocalHttp(baseUrl)
