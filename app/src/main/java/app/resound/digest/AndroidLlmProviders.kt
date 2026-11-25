package app.resound.digest

import android.content.Context
import app.resound.ask.AskCompatibility
import app.resound.diagnostics.FailureNotes
import app.resound.platform.AndroidKeyValueStores
import app.resound.platform.AndroidSecretVault
import app.resound.platform.KeyValueStores
import app.resound.platform.SecretVault
import app.resound.skills.SkillFiles
import app.resound.transcribe.EndpointKind
import app.resound.transcribe.OpenAiEndpoint
import app.resound.transcribe.fetchRemoteModels
import app.resound.usage.LlmBalanceChecker
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 单独注入密钥存储，迁移只查存在性，不解密、不重写历史密文。 */
internal interface ProviderKeys {
    fun has(id: String): Boolean
    fun read(id: String): String
    fun write(id: String, key: String)
    fun remove(id: String)
}

class AndroidLlmProviders internal constructor(
    context: Context,
    private val client: LlmClient,
    stores: KeyValueStores,
    vault: SecretVault,
    internal val headerStore: ServiceHeaders = ServiceHeaders(vault),
    private val recordUsage: suspend (LlmReply) -> Unit = {},
) : LlmProviders {
    constructor(context: Context, client: LlmClient, recordUsage: suspend (LlmReply) -> Unit = {}) :
        this(context, client, AndroidKeyValueStores(context), AndroidSecretVault(context), recordUsage = recordUsage)

    internal constructor(
        context: Context,
        client: LlmClient,
        keys: ProviderKeys,
        headerStore: ServiceHeaders = ServiceHeaders(context),
        recordUsage: suspend (LlmReply) -> Unit = {},
    ) : this(context, client, AndroidKeyValueStores(context), object : SecretVault {
        override fun has(group: String, key: String) = keys.has(key.removePrefix("key-"))
        override fun read(group: String, key: String) = keys.read(key.removePrefix("key-"))
        override fun write(group: String, key: String, value: String) = keys.write(key.removePrefix("key-"), value)
        override fun remove(group: String, key: String) = keys.remove(key.removePrefix("key-"))
    }, headerStore, recordUsage)

    internal val guard = Any()
    val askCompatibility = AskCompatibility(context)
    private val settings = LlmProviderSettings(
        stores, vault, headerStore.settings,
        context.assets.open("llm-providers.json").bufferedReader().use { it.readText() }.also(LlmInputBudget::configure),
        { UUID.randomUUID().toString() }, { OpenAiEndpoint.bases(it) }, ::isLocalHttp,
        object : ProviderAccess {
            override fun <T> run(action: () -> T): T = synchronized(guard, action)
        },
        legacyBalance = { base -> LlmBalanceChecker.suggest(base)?.takeIf { LlmBalanceChecker.isDeepSeek(base) } },
        onConfigurationChanged = askCompatibility::changed,
    ).apply {
        beforeDelete = { id -> SkillFiles(context).resetProviders(setOf(id)) }
    }

    override val templates get() = settings.templates
    override val saved get() = settings.saved
    override val activeId get() = settings.activeId
    internal var onTestSuccess: (String) -> Unit = {}
    var beforeDelete: (String) -> Unit
        get() = settings.beforeDelete
        set(value) { settings.beforeDelete = value }

    fun headers(id: String) = settings.headers(id)
    fun requestHeaders(preset: LlmPreset) = headerStore.forPreset(preset)
    fun hasHeaderAuth(id: String) = settings.hasHeaderAuth(id)
    fun canUseWithoutKey(preset: LlmPreset) = settings.canUseWithoutKey(preset)
    fun saveHeaders(id: String, headers: List<HeaderEntry>) = settings.saveHeaders(id, headers)
    internal fun listen(listener: (String?) -> Unit) = settings.listen(listener)
    internal fun selected() = settings.selected()
    internal fun credentials(id: String? = null): Pair<LlmPreset, String> = synchronized(guard) {
        val (provider, key) = settings.credentials(id)
        provider.preset() to key
    }
    internal fun readKey(id: String) = settings.readKey(id)

    override suspend fun save(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String): String = saveNow(id, templateId, name, baseUrl, model, key, balanceUrl, balancePath)

    internal fun saveNow(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
        balanceUrl: String, balancePath: String) = settings.save(id, templateId, name, baseUrl, model, key, balanceUrl, balancePath)

    override fun move(id: String, toIndex: Int) = settings.move(id, toIndex)
    override fun activate(id: String) = settings.activate(id)
    override fun delete(id: String) = settings.delete(id)
    internal fun setKey(key: String) = settings.setKey(key)
    internal fun setBalance(url: String, path: String) = settings.setBalance(url, path)
    internal fun ensureLegacy(id: String) = settings.ensureLegacy(id)

    override suspend fun test(id: String?, baseUrl: String, model: String, key: String?): Result<String> = try {
        settings.validateBase(baseUrl); require(model.isNotBlank()) { "请填写模型" }
        val p = LlmPreset(id ?: "draft", "测试服务", baseUrl.trim(), model.trim())
        val secret = settings.draftKey(id, key)
        require(secret.isNotBlank() || canUseWithoutKey(p)) { "还没填写大模型密钥" }
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
        settings.validateBase(baseUrl); fetchRemoteModels(ServiceHeaders.modelsEndpoint(baseUrl), settings.draftKey(id, key), headers = headerStore.forService(id ?: "draft", baseUrl)).ids
    } catch (e: CancellationException) { throw e }
    catch (caught: Exception) {
        FailureNotes.record("digest/AndroidLlmProviders.kt:224", caught)
        emptyList() }

    companion object {
        internal fun parseTemplates(text: String) = LlmProviderSettings.parseTemplates(text)
    }
}

internal fun SavedProvider.preset() = LlmInputBudget.bind(LlmPreset(id, name, baseUrl, model), templateId)
private fun isLocalHttp(baseUrl: String) = OpenAiEndpoint.kind(baseUrl) == EndpointKind.LocalHttp
fun LlmPreset.allowsEmptyKey() = allowsEmptyKey(::isLocalHttp)
