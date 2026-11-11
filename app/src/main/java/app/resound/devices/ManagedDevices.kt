package app.resound.devices

import app.resound.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal data class RememberedDevice(val address: String, val cardId: String, val name: String,
    val gapStart: Long? = null, val clockOffset: Long? = null)
internal interface PairedDeviceStore {
    fun read(): List<RememberedDevice>
    fun write(devices: List<RememberedDevice>)
}
internal data class DeviceAdvertisement(val address: String, val name: String?, val services: Set<String>,
    val manufacturers: Set<Int>, val rssi: Int?)
internal interface DeviceRadio {
    fun startScan(result: (DeviceAdvertisement) -> Unit, error: (String) -> Unit)
    fun stopScan()
    suspend fun pair(address: String, bond: Boolean)
    suspend fun connect(address: String, card: DeviceCard): LinkedDevice
}
internal fun matchingCard(ad: DeviceAdvertisement, cards: List<InstalledCard>): InstalledCard? = cards.firstOrNull { installed ->
    val match = installed.card.match
    installed.enabled && (match.namePrefix.any { ad.name?.startsWith(it) == true } ||
        match.serviceUuid.any { uuid -> ad.services.any { it.equals(uuid, true) } } || match.manufacturerId in ad.manufacturers)
}

/** 单个当前指定设备在后台连通后才发布为可用，连接等待期间 InputSwitchLoop 继续录手机。 */
internal class ManagedDevices(
    private val cards: DeviceCards,
    private val store: PairedDeviceStore,
    private val radio: DeviceRadio,
    private val scope: CoroutineScope,
    private val source: (RememberedDevice, DeviceCard, DeviceLink, suspend (Long?) -> Unit, (Int) -> Unit) -> AudioSource,
    private val onLinked: suspend (RememberedDevice, DeviceCard, DeviceLink, Long?, Long?, Long?, (BackfillProgress?) -> Unit) -> Unit = { _, _, _, _, _, _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = 3000,
) : Devices {
    private val remembered = MutableStateFlow(store.read())
    private val states = MutableStateFlow(remembered.value.map { row ->
        row.state().let { if (cards.cards.value.any { it.card.id == row.cardId && it.enabled }) it
            else it.copy(lastError = "设备说明卡不可用") }
    })
    override val devices = states.asStateFlow()
    private val discoveries = MutableStateFlow<List<FoundDevice>>(emptyList())
    override val found = discoveries.asStateFlow()
    // Devices 约定没有全局错误字段；具体实现额外提供，空列表时也能提示权限问题。
    private val error = MutableStateFlow<String?>(null)
    val lastError = error.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    private val active = MutableStateFlow(false)
    private val stream = MutableStateFlow<DeviceAudioBus?>(null)
    private var scanning = false
    private var scanTimer: Job? = null
    private val pairing = mutableMapOf<String, Job>()

    init {
        scope.launch {
            cards.cards.collect { enabled ->
                discoveries.value = discoveries.value.filter { d -> enabled.any { it.card.id == d.cardId && it.enabled } }
                states.update { rows -> rows.map { row ->
                    if (enabled.none { it.card.id == row.cardId && it.enabled })
                        row.copy(status = DeviceStatus.Disconnected, backfill = null, lastError = "设备说明卡不可用")
                    else if (row.lastError == "设备说明卡不可用") row.copy(lastError = null) else row
                } }
            }
        }
        scope.launch {
            combine(selected, active, remembered, cards.cards) { key, running, saved, installed ->
                val row = saved.find { deviceKey(it.address) == key }
                val card = installed.find { it.enabled && it.card.id == row?.cardId && it.card.audio != null }?.card
                if (running && row != null && card != null) row.copy(gapStart = null, clockOffset = null) to card else null
            }.distinctUntilChanged().collectLatest { target ->
                if (target == null) return@collectLatest
                val (identity, card) = target
                while (currentCoroutineContext().isActive) {
                    var link: LinkedDevice? = null
                    var failure: AudioSourceException? = null
                    val bus = DeviceAudioBus()
                    update(identity.address) { it.copy(status = DeviceStatus.Connecting, lastError = null) }
                    try {
                        val connected = radio.connect(identity.address, card)
                        link = connected
                        val current = remembered.value.first { it.address == identity.address }
                        coroutineScope {
                            var sync: Job? = null
                            try {
                                source(current, card, connected, { offset ->
                                    val restored = now()
                                    // 旧文件按断开前的偏差换算；刚写的新时钟不能回填成旧偏差。
                                    if (card.offline == OfflineMode.Files) sync = launch {
                                        onLinked(current, card, connected, current.gapStart,
                                            current.gapStart?.let { restored }, current.clockOffset) { progress ->
                                            update(identity.address) { it.copy(backfill = progress) }
                                        }
                                        saveRow(identity.address) { it.copy(gapStart = null, clockOffset = offset) }
                                    } else saveRow(identity.address) { it.copy(gapStart = null, clockOffset = offset) }
                                }, { value -> update(identity.address) { it.copy(battery = value) } }).events().collect { event ->
                                    if (event is AudioEvent.Frame && stream.value !== bus) {
                                        stream.value = bus
                                        update(identity.address) { it.copy(status = DeviceStatus.Live, lastError = null) }
                                    }
                                    bus.send(event)
                                }
                                throw AudioSourceException(AudioSourceException.Reason.Disconnected)
                            } finally { sync?.cancelAndJoin() }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failure = e as? AudioSourceException ?: if (e is SecurityException) AudioSourceException(AudioSourceException.Reason.NoPermission, "缺少蓝牙连接权限")
                            else AudioSourceException(AudioSourceException.Reason.Disconnected, "设备连接失败")
                        saveRow(identity.address) { if (it.gapStart == null) it.copy(gapStart = link?.disconnectedAt ?: now()) else it }
                    } finally {
                        if (stream.value === bus) stream.value = null
                        bus.close(failure ?: AudioSourceException(AudioSourceException.Reason.Disconnected))
                        update(identity.address) { it.copy(status = if (failure == null) DeviceStatus.Disconnected else DeviceStatus.Waiting,
                            backfill = null, lastError = if (cards.cards.value.none { c -> c.card.id == identity.cardId && c.enabled }) "设备说明卡不可用" else failure?.message) }
                        withContext(NonCancellable) { link?.close() }
                    }
                    delay(retryMillis)
                }
            }
        }
    }
    fun select(choice: InputChoice) { selected.value = (choice as? InputChoice.Device)?.key }
    fun recording(value: Boolean) { active.value = value }
    fun audioSource(key: String): AudioSource = object : AudioSource {
        override val id = key
        override val displayName = devices.value.find { deviceKey(it.address) == key }?.name ?: "专用录音设备"
        override fun events(): Flow<AudioEvent> = flow {
            if (selected.value != key) throw AudioSourceException(AudioSourceException.Reason.Disconnected)
            val current = stream.value ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected)
            emitAll(current.events())
        }
    }
    @Synchronized private fun update(address: String, change: (DeviceState) -> DeviceState) {
        states.value = states.value.map { if (it.address == address) change(it) else it }
    }
    @Synchronized private fun saveRow(address: String, change: (RememberedDevice) -> RememberedDevice) {
        val next = remembered.value.map { if (it.address == address) change(it) else it }
        store.write(next); remembered.value = next
    }
    @Synchronized override fun startScan() {
        stopScan(); discoveries.value = emptyList(); error.value = null; scanning = true
        try {
            radio.startScan({ ad -> synchronized(this) {
                if (!scanning) return@synchronized
                val match = matchingCard(ad, cards.cards.value) ?: return@synchronized
                val found = FoundDevice(ad.address, ad.name?.takeIf { it.isNotBlank() } ?: match.card.name,
                    match.card.id, match.card.status == CardStatus.Trial, ad.rssi)
                discoveries.value = (discoveries.value.filterNot { it.address == found.address } + found).takeLast(100)
            } }, { message -> scanError(message) })
            scanTimer = scope.launch { delay(30_000); stopScan() }
        } catch (_: SecurityException) { scanError("缺少蓝牙搜索或连接权限") }
        catch (_: Exception) { scanError("蓝牙搜索不可用，请检查蓝牙开关") }
    }
    private fun scanError(message: String) {
        error.value = message
        synchronized(this) { states.value = states.value.map { it.copy(lastError = message) }; stopScan() }
    }
    @Synchronized override fun stopScan() {
        scanning = false; scanTimer?.cancel(); scanTimer = null
        runCatching { radio.stopScan() }
    }
    @Synchronized override fun pair(device: FoundDevice) {
        val card = cards.cards.value.find { it.card.id == device.cardId && it.enabled }?.card ?: return
        if (found.value.none { it.address == device.address && it.cardId == device.cardId } || pairing[device.address]?.isActive == true) return
        pairing[device.address] = scope.launch {
            try {
                radio.pair(device.address, card.link.bond)
                synchronized(this@ManagedDevices) {
                    if (cards.cards.value.none { it.card.id == card.id && it.enabled }) return@synchronized
                    val row = RememberedDevice(device.address, card.id, device.name)
                    val next = remembered.value.filterNot { it.address == row.address } + row
                    store.write(next); remembered.value = next
                    states.value = states.value.filterNot { it.address == row.address } + row.state()
                    error.value = null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { error.value = "缺少蓝牙连接权限" }
            catch (_: Exception) { error.value = "配对失败，请检查设备和系统配对提示" }
        }
    }
    @Synchronized override fun forget(address: String) {
        pairing.remove(address)?.cancel()
        val next = remembered.value.filterNot { it.address == address }
        store.write(next); remembered.value = next; states.value = states.value.filterNot { it.address == address }
    }
    companion object { fun deviceKey(address: String) = "CardDevice:$address" }
}
private fun RememberedDevice.state() = DeviceState(address, name, cardId, DeviceStatus.Disconnected)

/** 有界广播，关闭携带故障；不会用 SharedFlow 吞掉断开。 */
internal class DeviceAudioBus {
    private val listeners = mutableSetOf<Channel<AudioEvent>>()
    private var ended: AudioSourceException? = null
    fun events(): Flow<AudioEvent> = flow {
        val channel = Channel<AudioEvent>(64)
        synchronized(this@DeviceAudioBus) { ended?.let { channel.close(it) } ?: listeners.add(channel) }
        try { for (event in channel) emit(event) }
        finally { synchronized(this@DeviceAudioBus) { listeners.remove(channel) }; channel.cancel() }
    }
    @Synchronized fun send(event: AudioEvent) {
        listeners.forEach { if (!it.trySend(event).isSuccess) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备声音消费超时") }
    }
    @Synchronized fun close(error: AudioSourceException) { ended = error; listeners.forEach { it.close(error) }; listeners.clear() }
}
