package app.resound.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.time.Instant

internal data class SignedCards(val json: ByteArray, val signature: ByteArray)
internal interface CardsStore {
    fun cached(): List<SignedCards>
    fun save(tables: List<SignedCards>)
    fun enabled(id: String): Boolean?
    fun enable(id: String, enabled: Boolean)
    var lastAttempt: Long
}

internal class RemoteDeviceCards(
    bundled: List<String>, private val store: CardsStore, private val publicKey: ByteArray,
    private val appVersion: String, private val report: (String) -> Unit = {},
    private val parser: (String) -> Result<DeviceCard> = BootstrapCardParser::parse,
) : DeviceCards {
    private val state = MutableStateFlow<List<InstalledCard>>(emptyList())
    override val cards = state.asStateFlow()
    private var version = 0
    private var tables = emptyList<SignedCards>()
    private val owners = mutableMapOf<String, SignedCards>()
    init {
        bundled.forEach { text -> parse(text).fold({ c -> merge(c, CardSource.Bundled) }, { report("bundled_invalid") }) }
        runCatching { store.cached().forEach { accept(it, persist = false) } }.onFailure { report("cache_invalid") }
    }
    override fun parse(json: String) = parser(json)
    @Synchronized override fun setEnabled(cardId: String, enabled: Boolean) {
        if (state.value.none { it.card.id == cardId }) return
        store.enable(cardId, enabled)
        state.value = state.value.map { if (it.card.id == cardId) it.copy(enabled = enabled) else it }
    }
    private fun merge(card: DeviceCard, source: CardSource) {
        if (!compatible(card.minApp, appVersion)) return
        val previous = state.value.firstOrNull { it.card.id == card.id }
        if (previous != null && previous.card.version >= card.version) return
        state.value = (state.value.filterNot { it.card.id == card.id } + InstalledCard(card, source,
            store.enabled(card.id) ?: (card.status == CardStatus.Verified))).sortedBy { it.card.id }
    }
    @Synchronized fun accept(table: SignedCards, persist: Boolean = true): Boolean = try {
        require(table.json.size <= MAX_TABLE && table.signature.size == 64 && publicKey.size == 32) { "signature_invalid" }
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(table.json, 0, table.json.size) }
        require(verifier.verifySignature(table.signature)) { "signature_invalid" }
        val root = JSONObject(table.json.toString(Charsets.UTF_8)).only("version issuedAt cards")
        val incoming = root.i("version").also { require(it > 0) }
        require(incoming >= version) { "version_old" }
        // 同版本只能重放完全相同的表，避免同版本不同内容改变缓存。
        if (incoming == version) {
            require(tables.lastOrNull()?.json?.contentEquals(table.json) == true) { "version_conflict" }
            true
        } else {
            Instant.parse(root.s("issuedAt"))
            val array = root.getJSONArray("cards").also { require(it.length() <= 200) }
            val parsed = (0 until array.length()).map { parse(array.getJSONObject(it).toString()).getOrThrow() }
            require(parsed.map { it.id }.distinct().size == parsed.size)
            val newOwners = owners.toMutableMap()
            parsed.filter { compatible(it.minApp, appVersion) }.forEach { c ->
                if ((state.value.firstOrNull { it.card.id == c.id }?.card?.version ?: 0) < c.version) newOwners[c.id] = table
            }
            // 保留仍提供最高卡版本的签名原表，重启也不允许某张卡降级或被遗漏删除。
            val retained = (tables + table).filter { it === table || it in newOwners.values }
            require(retained.sumOf { it.json.size } <= MAX_CACHE)
            if (persist) store.save(retained) // 持久化成功后才发布新状态。
            parsed.forEach { merge(it, CardSource.Remote) }
            owners.clear(); owners.putAll(newOwners); tables = retained; version = incoming
            true
        }
    } catch (e: Exception) {
        report(when(e.message) { "signature_invalid", "version_old", "version_conflict" -> e.message!!; else -> "table_invalid" }); false
    }
    /** 调用者由 WorkManager 的 CONNECTED 约束保证联网；失败也计入每天一次。 */
    @Synchronized fun refresh(now: Long, fetch: (accept: (SignedCards) -> Boolean) -> Unit) {
        val last = store.lastAttempt
        if (last != 0L && now - last < DAY) return
        try { store.lastAttempt = now; fetch { accept(it) } } catch (_: Exception) { report("fetch_failed") }
    }
    companion object { const val MAX_TABLE = 256 * 1024; const val MAX_CACHE = 4 * 1024 * 1024; const val DAY = 86_400_000L }
}
