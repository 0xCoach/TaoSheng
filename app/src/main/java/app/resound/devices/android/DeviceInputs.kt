package app.resound.devices.android

import app.resound.audio.*
import app.resound.devices.*
import app.resound.devices.card.*
import app.resound.devices.driver.*
import app.resound.devices.hub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 原配置行及字段不变；配对配置和中枢选择各司其职。 */
internal data class RememberedDevice(val address: String, val cardId: String, val name: String,
    val gapStart: Long? = null, val clockOffset: Long? = null)
internal interface PairedDeviceStore {
    fun read(): List<RememberedDevice>
    fun write(devices: List<RememberedDevice>)
}

/** 旧界面/AudioSource 到设备中枢的兼容层。仅指定设备且正在录音时建立中枢生命周期。 */
internal class DeviceInputs(
    private val cards: DeviceCards,
    private val store: PairedDeviceStore,
    private val repository: HubRepository,
    private val connector: suspend (String, DeviceCard) -> HubLink,
    private val toolbox: Toolbox,
    private val clock: ClockPort,
    private val extras: PlatformExtras,
    private val scan: () -> Flow<Advertisement>,
    private val bond: suspend (String, Boolean) -> Unit,
    private val identity: (String) -> DeviceIdentity,
    private val scope: CoroutineScope,
    private val plugins: List<FileSourcePlugin> = emptyList(),
    private val onLinked: suspend (RememberedDevice, DeviceCard, DeviceLink, Long?, Long?, Long?, (BackfillProgress?) -> Unit) -> Unit = { _, _, _, _, _, _, _ -> },
    private val report: (String, LinkDiagnostics) -> Unit = { _, _ -> },
) : Devices {
    private val savedLock = Mutex()
    private val remembered = MutableStateFlow(store.read())
    private fun RememberedDevice.state() = DeviceState(address, name, cardId, DeviceStatus.Disconnected,
        lastError = if (cards.cards.value.any { it.enabled && it.card.id == cardId }) null else "设备说明卡不可用")
    private val states = MutableStateFlow(remembered.value.map { it.state() })
    override val devices = states.asStateFlow()
    private val discoveries = MutableStateFlow<List<FoundDevice>>(emptyList())
    override val found = discoveries.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val lastError = error.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    private val active = MutableStateFlow(false)
    private val stream = MutableStateFlow<Feed?>(null)
    private val scanJob = MutableStateFlow<Job?>(null)
    private val pairing = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val runningHub = MutableStateFlow<DeviceHub?>(null)

    init {
        scope.launch {
            cards.cards.collect { installed ->
                discoveries.update { rows -> rows.filter { d -> installed.any { it.enabled && it.card.id == d.cardId } } }
                states.update { rows -> rows.map { row ->
                    if (installed.none { it.enabled && it.card.id == row.cardId })
                        row.copy(status = DeviceStatus.Disconnected, backfill = null, lastError = "设备说明卡不可用")
                    else if (row.lastError == "设备说明卡不可用") row.copy(lastError = null) else row
                } }
            }
        }
        scope.launch {
            combine(selected, active, remembered, cards.cards) { key, recording, rows, installed ->
                val row = rows.find { deviceKey(it.address) == key }
                val enabled = installed.filter { it.enabled }
                if (recording && row != null && enabled.any { it.card.id == row.cardId && it.card.audio != null })
                    row.copy(gapStart = null, clockOffset = null) to enabled else null
            }.distinctUntilChanged().collectLatest { target ->
                if (target != null) runTarget(target.first, target.second)
            }
        }
    }

    private class Port(val link: CardLink, val ready: CompletableDeferred<Long?>) {
        val disconnectedAt = MutableStateFlow<Long?>(null)
        val failures = MutableSharedFlow<DeviceException>(replay = 1)
    }

    private suspend fun runTarget(row: RememberedDevice, installed: List<InstalledCard>) = coroutineScope {
        val card = installed.first { it.card.id == row.cardId }.card
        val ports = MutableStateFlow<Port?>(null)
        val catalog = object : DriverCatalog {
            override val drivers = installed.map { item -> CardDriver(item.card, true,
                plugins.firstOrNull { it.id == item.card.filePlugin },
                report = { report(row.address, it) }, readTimeout = CaptureHealth.STALL_MS,
                zeroLimitMillis = CaptureHealth.ZERO_LIMIT_MS) }
        }
        val hub = DeviceHub(catalog, repository, HubConnector { handle ->
            val transport = connector(handle, card)
            val ready = CompletableDeferred<Long?>()
            val port = Port(CardLink(transport.gatt, identity(handle)) { ready.complete(it) }, ready)
            ports.value = port
            object : HubLink {
                override val gatt = port.link
                override val disconnected = merge(transport.disconnected, port.failures).onEach {
                    // 在中枢释放连接前记时，避免把清理/重试等待时间算进断开起点。
                    port.disconnectedAt.compareAndSet(null,
                        (transport.gatt as? AndroidGattLink)?.disconnectedAt ?: clock.nowEpochMillis())
                    gap(row.address, port.disconnectedAt.value!!)
                }
                override suspend fun close() = transport.close()
            }
        }, toolbox, clock, extras, this)
        runningHub.value = hub
        try {
            hub.select(Advertisement(row.address, row.name, emptySet(), emptyMap(), null), "card:${card.id}")
            hub.connect(row.address)
            launch {
                hub.devices.collect { values -> values.firstOrNull { it.platformHandle == row.address }?.let { device ->
                    if (device.connection == ConnectionState.WaitingReconnect)
                        gap(row.address, ports.value?.disconnectedAt?.value ?: clock.nowEpochMillis())
                    render(row.address, device)
                } }
            }
            hub.devices.map { rows -> rows.firstOrNull { it.platformHandle == row.address }?.connection.let {
                it == ConnectionState.Connected || it == ConnectionState.Backfilling
            } }.distinctUntilChanged().collectLatest { connected ->
                if (connected) consume(row, card, hub, requireNotNull(ports.value))
            }
        } finally {
            withContext(NonCancellable) { hub.close() }
            runningHub.compareAndSet(hub, null)
            update(row.address) { it.copy(status = DeviceStatus.Disconnected, backfill = null,
                lastError = if (cards.cards.value.none { c -> c.enabled && c.card.id == row.cardId }) "设备说明卡不可用" else it.lastError) }
        }
    }

    private suspend fun consume(row: RememberedDevice, card: DeviceCard, hub: DeviceHub, port: Port) = coroutineScope {
        val feed = Feed(deviceKey(row.address))
        var live: LiveAudio? = null
        var failure: AudioSourceException? = null
        val before = remembered.value.firstOrNull { it.address == row.address } ?: return@coroutineScope
        val sync = launch {
            try {
                val offset = port.ready.await()
                val restored = clock.nowEpochMillis()
                if (card.offline == OfflineMode.Files) hub.withConnection(row.address, LeaseOwner.Backfill) {
                    onLinked(before, card, CardDeviceLink(port.link), before.gapStart,
                        before.gapStart?.let { restored }, before.clockOffset) { progress -> update(row.address) { it.copy(backfill = progress) } }
                }
                saveRow(row.address) { if (port.disconnectedAt.value == null) it.copy(gapStart = null, clockOffset = offset) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { port.failures.emit(e as? DeviceException ?: DeviceException(ErrorCode.ProtocolError, cause = e)) }
        }
        try {
            // 租约保护启动/控制，持续通知的收集不占住补传租约；与旧链路的实时+文件并行行为一致。
            live = hub.withConnection(row.address, LeaseOwner.LiveAudio) { connection ->
                (connection.live ?: throw DeviceException(ErrorCode.Unsupported)).also { it.start() }
            }
            live.frames.collect { chunk ->
                if (chunk.pcm.any { it.toInt() != 0 }) CaptureHealth.lastSoundAt = clock.monotonicMillis()
                if (stream.value !== feed) {
                    stream.value = feed
                    update(row.address) { it.copy(status = DeviceStatus.Live, lastError = null) }
                }
                feed.send(AudioEvent.Frame(chunk.pcm))
            }
            throw DeviceException(ErrorCode.Disconnected)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failure = audioError(e)
            port.failures.emit(e as? DeviceException ?: DeviceException(ErrorCode.Disconnected, cause = e))
            gap(row.address, port.disconnectedAt.value ?: clock.nowEpochMillis())
        } finally {
            // 中枢收到 closed 后可能先取消收集；仍保留驱动已确认的全零等具体错误。
            if (failure == null) failure = port.link.failure.value?.let(::audioError)
            stream.compareAndSet(feed, null)
            feed.close(failure ?: AudioSourceException(AudioSourceException.Reason.Disconnected))
            withContext(NonCancellable) {
                sync.cancelAndJoin()
                if (failure != null) gap(row.address, port.disconnectedAt.value ?: clock.nowEpochMillis())
                // 不重新申请租约，物理断开时旧连接已不在中枢；stop 仅取消本连接的声音流。
                runCatching { live?.stop() }
            }
            update(row.address) { it.copy(status = if (failure == null) DeviceStatus.Disconnected else DeviceStatus.Waiting,
                backfill = null, lastError = failure?.message ?: it.lastError) }
        }
    }

    private fun render(address: String, device: HubDevice) = update(address) { old ->
        val available = stream.value?.key == deviceKey(address)
        old.copy(status = when (device.connection) {
            ConnectionState.Connecting -> DeviceStatus.Connecting
            ConnectionState.Connected -> if (available) DeviceStatus.Live else DeviceStatus.Connecting
            ConnectionState.Backfilling -> if (available) DeviceStatus.Backfilling else DeviceStatus.Connecting
            ConnectionState.WaitingReconnect -> DeviceStatus.Waiting
            else -> DeviceStatus.Disconnected
        }, battery = device.status.batteries.firstOrNull { it.part == 1 }?.percent ?: old.battery,
            lastError = when {
                device.disabledReason != null -> "设备驱动已停用"
                device.connection == ConnectionState.Connecting -> null
                device.lastError == ErrorCode.PermissionDenied -> "缺少蓝牙连接权限"
                device.lastError != null -> old.lastError ?: "设备连接失败"
                else -> old.lastError
            })
    }
    private fun audioError(error: Exception): AudioSourceException = when {
        error is AudioSourceException -> error
        error is DeviceException && error.code == ErrorCode.PermissionDenied -> AudioSourceException(AudioSourceException.Reason.NoPermission, "缺少蓝牙连接权限")
        error is DeviceException && error.detail == "zero-pcm" -> AudioSourceException(AudioSourceException.Reason.Disconnected, CaptureHealth.ZERO_PCM)
        else -> AudioSourceException(AudioSourceException.Reason.Disconnected, "设备连接或声音协议失败")
    }
    fun select(choice: InputChoice) { selected.value = (choice as? InputChoice.Device)?.key }
    fun recording(value: Boolean) { active.value = value }
    fun audioSource(key: String): AudioSource = object : AudioSource {
        override val id = key
        override val displayName = devices.value.find { deviceKey(it.address) == key }?.name ?: "专用录音设备"
        override fun events(): Flow<AudioEvent> = flow {
            val feed = stream.value?.takeIf { it.key == key && selected.value == key }
                ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected)
            emitAll(feed.events())
        }
    }
    private fun update(address: String, change: (DeviceState) -> DeviceState) {
        states.update { rows -> rows.map { if (it.address == address) change(it) else it } }
    }
    private suspend fun saveRow(address: String, change: (RememberedDevice) -> RememberedDevice) = savedLock.withLock {
        val next = remembered.value.map { if (it.address == address) change(it) else it }
        store.write(next); remembered.value = next
    }
    private suspend fun gap(address: String, at: Long) = saveRow(address) { if (it.gapStart == null) it.copy(gapStart = at) else it }

    override fun startScan() {
        stopScan(); discoveries.value = emptyList(); error.value = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(30_000) {
                    scan().collect { ad ->
                        val match = cards.cards.value.firstOrNull { CardDriver(it.card, it.enabled).match(ad) == MatchConfidence.Certain } ?: return@collect
                        val device = FoundDevice(ad.platformHandle, ad.name?.takeIf { it.isNotBlank() } ?: match.card.name,
                            match.card.id, match.card.status == CardStatus.Trial, ad.rssi)
                        discoveries.update { (it.filterNot { row -> row.address == device.address } + device).takeLast(100) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is SecurityException || (e is DeviceException && e.code == ErrorCode.PermissionDenied))
                    "缺少蓝牙搜索或连接权限" else "蓝牙搜索不可用，请检查权限和蓝牙开关"
                error.value = message; states.update { rows -> rows.map { it.copy(lastError = message) } }
            }
        }
        scanJob.value = job; job.start()
    }
    override fun stopScan() { scanJob.getAndUpdate { null }?.cancel() }
    override fun pair(device: FoundDevice) {
        val card = cards.cards.value.find { it.enabled && it.card.id == device.cardId }?.card ?: return
        if (found.value.none { it.address == device.address && it.cardId == device.cardId }) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                bond(device.address, card.link.bond)
                savedLock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (cards.cards.value.none { it.enabled && it.card.id == card.id }) return@withLock
                    val row = RememberedDevice(device.address, card.id, device.name)
                    val next = remembered.value.filterNot { it.address == row.address } + row
                    store.write(next); remembered.value = next
                    states.update { it.filterNot { old -> old.address == row.address } + row.state() }
                    error.value = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = if (e is SecurityException) "缺少蓝牙连接权限" else "配对失败，请检查设备和系统配对提示" }
        }
        while (true) {
            val prior = pairing.value
            if (prior[device.address]?.let { !it.isCompleted } == true) { job.cancel(); return }
            if (pairing.compareAndSet(prior, prior + (device.address to job))) break
        }
        job.start()
    }
    override fun forget(address: String) {
        pairing.getAndUpdate { it - address }[address]?.cancel()
        scope.launch {
            savedLock.withLock {
                val next = remembered.value.filterNot { it.address == address }
                store.write(next); remembered.value = next
                states.update { it.filterNot { row -> row.address == address } }
            }
            runningHub.value?.forget(address) ?: repository.forget(address)
        }
    }
    companion object { fun deviceKey(address: String) = "CardDevice:$address" }

    /** 有界广播加独立结束信号：迟来的收集器也能收到断开，取消收集不会关闭后台连接。 */
    private class Feed(val key: String) {
        private val frames = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
        private val ended = CompletableDeferred<AudioSourceException>()
        fun events(): Flow<AudioEvent> = channelFlow {
            launch { frames.collect { send(it) } }
            throw ended.await()
        }.buffer(0)
        fun send(event: AudioEvent) {
            if (!frames.tryEmit(event)) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备声音消费超时")
        }
        fun close(error: AudioSourceException) { ended.complete(error) }
    }
}
