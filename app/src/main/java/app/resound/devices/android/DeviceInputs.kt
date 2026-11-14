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
import kotlinx.coroutines.selects.select

/** 原配置行及字段不变；配对配置和中枢选择各司其职。 */
internal data class RememberedDevice(val address: String, val cardId: String, val name: String,
    val gapStart: Long? = null, val clockOffset: Long? = null)
internal interface PairedDeviceStore {
    fun read(): List<RememberedDevice>
    fun write(devices: List<RememberedDevice>)
}

/** 注册表由装配层提供；卡片只为旧蓝牙连接工厂提供链路参数。 */
internal data class RegisteredDriver(val driver: DeviceDriver, val transport: DeviceCard)
internal data class ManualSyncState(val running: Boolean, val pausing: Boolean = false, val message: String? = null,
                                    val errorCode: ErrorCode? = null)

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
    private val registered: List<RegisteredDriver> = emptyList(),
    private val extrasFor: (String, String) -> PlatformExtras = { _, _ -> extras },
    private val onDriverLinked: suspend (RememberedDevice, DriverConnection, DeviceLink, Boolean, (BackfillProgress?) -> Unit) -> Unit = { _, _, _, _, _ -> },
) : Devices {
    private val savedLock = Mutex()
    private val remembered = MutableStateFlow(store.read())
    private val cachedStatus = MutableStateFlow(remembered.value.mapNotNull { row ->
        if (registered.none { it.driver.meta.id == row.cardId }) return@mapNotNull null
        val selection = repository.selections().firstOrNull { it.platformHandle == row.address && it.driverId == row.cardId }
            ?: return@mapNotNull null
        val key = selection.deviceId?.let { "id:${it.value}" } ?: "handle:${row.address}"
        repository.store(selection.driverId,key).getString("lastStatus")?.let(DeviceStatusCache::decode)?.let { row.address to it }
    }.toMap())
    /** 上次成功读取的状态；断开后保留原始读取时间供界面标记新鲜度。 */
    val lastDeviceStatus = cachedStatus.asStateFlow()
    private fun RememberedDevice.state() = DeviceState(address, name, cardId, DeviceStatus.Disconnected,
        battery = cachedStatus.value[address]?.batteries?.firstOrNull { it.part == 1 }?.percent,
        lastError = if (available(cardId, cards.cards.value)) null else "设备说明卡不可用")
    private fun available(id: String, cards: List<InstalledCard>) =
        registered.any { it.driver.meta.id == id } || cards.any { it.enabled && it.card.id == id }
    fun hasLiveInput(id: String, installed: List<InstalledCard>) =
        registered.any { it.driver.meta.id == id } || installed.any { it.enabled && it.card.id == id && it.card.audio != null }
    private val states = MutableStateFlow(remembered.value.map { it.state() })
    override val devices = states.asStateFlow()
    private val discoveries = MutableStateFlow<List<FoundDevice>>(emptyList())
    override val found = discoveries.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val lastError = error.asStateFlow()
    private data class ManualSyncEntry(val task: Job, val state: ManualSyncState)
    private val syncTasks = MutableStateFlow<Map<String, ManualSyncEntry>>(emptyMap())
    val manualSyncStates = syncTasks.map { rows -> rows.mapValues { it.value.state } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val selected = MutableStateFlow<String?>(null)
    private val active = MutableStateFlow(false)
    private val stream = MutableStateFlow<Feed?>(null)
    private val scanJob = MutableStateFlow<Job?>(null)
    private val pairing = MutableStateFlow<Map<String, Job>>(emptyMap())
    private data class ActiveHub(val handle: String, val hub: DeviceHub)
    private val runningHub = MutableStateFlow<ActiveHub?>(null)
    private val connectionLocks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private fun connectionLock(handle: String): Mutex {
        while (true) {
            val old = connectionLocks.value
            old[handle]?.let { return it }
            val lock = Mutex()
            if (connectionLocks.compareAndSet(old, old + (handle to lock))) return lock
        }
    }

    init {
        scope.launch {
            cards.cards.collect { installed ->
                discoveries.update { rows -> rows.filter { d -> available(d.cardId, installed) } }
                states.update { rows -> rows.map { row ->
                    if (!available(row.cardId, installed))
                        row.copy(status = DeviceStatus.Disconnected, backfill = null, lastError = "设备说明卡不可用")
                    else if (row.lastError == "设备说明卡不可用") row.copy(lastError = null) else row
                } }
            }
        }
        scope.launch {
            combine(selected, active, remembered, cards.cards) { key, recording, rows, installed ->
                val row = rows.find { deviceKey(it.address) == key }
                val enabled = installed.filter { it.enabled }
                if (recording && row != null && (registered.any { it.driver.meta.id == row.cardId } || enabled.any { it.card.id == row.cardId && it.card.audio != null }))
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

    private suspend fun runTarget(row: RememberedDevice, installed: List<InstalledCard>) =
        connectionLock(row.address).withLock { runOwnedTarget(row,installed) }

    private suspend fun runOwnedTarget(row: RememberedDevice, installed: List<InstalledCard>) = coroutineScope {
        val registeredDriver = registered.firstOrNull { it.driver.meta.id == row.cardId }
        val card = registeredDriver?.transport ?: installed.first { it.card.id == row.cardId }.card
        val driverId = registeredDriver?.driver?.meta?.id ?: "card:${card.id}"
        val ports = MutableStateFlow<Port?>(null)
        val catalog = object : app.resound.devices.driver.DriverCatalog {
            override val drivers = installed.map { item -> CardDriver(item.card, true,
                plugins.firstOrNull { it.id == item.card.filePlugin },
                report = { report(row.address, it) }, readTimeout = CaptureHealth.STALL_MS,
                zeroLimitMillis = CaptureHealth.ZERO_LIMIT_MS) } + registered.map { it.driver }
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
        }, toolbox, clock, extrasFor(row.address, driverId), this)
        val activeHub = ActiveHub(row.address,hub)
        runningHub.value = activeHub
        try {
            hub.select(Advertisement(row.address, row.name, emptySet(), emptyMap(), null), driverId)
            if (registeredDriver != null) {
                val prior = repository.selections().firstOrNull { it.platformHandle == row.address && it.driverId == driverId }
                val key = prior?.deviceId?.let { "id:${it.value}" } ?: "handle:${row.address}"
                repository.store(driverId, key).apply {
                    putString("platformHandle", row.address)
                    putString("platformIdentity", identity(row.address).id.value)
                }
            }
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
                if (connected) consume(row, card, hub, requireNotNull(ports.value), registeredDriver != null)
            }
        } finally {
            withContext(NonCancellable) { hub.close() }
            runningHub.compareAndSet(activeHub, null)
            update(row.address) { it.copy(status = DeviceStatus.Disconnected, backfill = null,
                lastError = if (!available(row.cardId, cards.cards.value)) "设备说明卡不可用" else it.lastError) }
        }
    }

    private suspend fun consume(row: RememberedDevice, card: DeviceCard, hub: DeviceHub, port: Port, plugin: Boolean) = coroutineScope {
        val feed = Feed(deviceKey(row.address))
        var live: LiveAudio? = null
        var failure: AudioSourceException? = null
        val before = remembered.value.firstOrNull { it.address == row.address } ?: return@coroutineScope
        val beforeLive = try {
            if (plugin) hub.withConnection(row.address,LeaseOwner.Management) { it.policy.backfillBeforeLive } else false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            port.failures.emit(e as? DeviceException ?: DeviceException(ErrorCode.Disconnected,cause=e))
            return@coroutineScope
        }
        val sync = launch(start = if (plugin && !beforeLive) CoroutineStart.LAZY else CoroutineStart.DEFAULT) {
            try {
                if (plugin) {
                    hub.withConnection(row.address, LeaseOwner.Backfill) { connection ->
                        try { onDriverLinked(before, connection, CardDeviceLink(port.link), false) { progress -> update(row.address) { it.copy(backfill = progress) } } }
                        catch (e: DeviceException) { if (e.code != ErrorCode.StateConflict) throw e }
                    }
                    return@launch
                }
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
            if (plugin && beforeLive) sync.join()
            // 租约保护启动/控制，持续通知的收集不占住补传租约；与旧链路的实时+文件并行行为一致。
            live = hub.withConnection(row.address, LeaseOwner.LiveAudio) { connection ->
                (connection.live ?: throw DeviceException(ErrorCode.Unsupported)).also { it.start() }
            }
            if (plugin && !beforeLive) sync.start()
            live.frames.collect { chunk ->
                if (chunk.pcm.any { it.toInt() != 0 }) CaptureHealth.lastSoundAt = clock.monotonicMillis()
                if (stream.value !== feed) {
                    stream.value = feed
                    update(row.address) { it.copy(status = DeviceStatus.Live, lastError = null) }
                }
                feed.send(Feed.Frame(chunk, live, remembered.value.firstOrNull { it.address == row.address }?.clockOffset))
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

    private fun cacheStatus(address: String, status: DeviceStatusModel) {
        if (status == DeviceStatusModel()) return
        val selection = repository.selections().firstOrNull { it.platformHandle == address } ?: return
        if (registered.none { it.driver.meta.id == selection.driverId }) return
        if (cachedStatus.value[address] == status) return
        cachedStatus.update { it + (address to status) }
        val key = selection.deviceId?.let { "id:${it.value}" } ?: "handle:$address"
        runCatching { repository.store(selection.driverId,key).putString("lastStatus",DeviceStatusCache.encode(status)) }
    }
    private fun render(address: String, device: HubDevice) {
        cacheStatus(address,device.status)
        update(address) { old ->
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
    }
    private fun audioError(error: Exception): AudioSourceException = when {
        error is AudioSourceException -> error
        error is DeviceException && error.code == ErrorCode.PermissionDenied -> AudioSourceException(AudioSourceException.Reason.NoPermission, "缺少蓝牙连接权限")
        error is DeviceException && error.detail == "zero-pcm" -> AudioSourceException(AudioSourceException.Reason.Disconnected, CaptureHealth.ZERO_PCM)
        else -> AudioSourceException(AudioSourceException.Reason.Disconnected, "设备连接或声音协议失败")
    }
    /** 卡 144：这台设备当前用的驱动是否被停用（连续出错或用户停用） */
    fun disabledReason(address: String): DisableReason? {
        val selection = repository.selections().firstOrNull { it.platformHandle == address } ?: return null
        val key = selection.deviceId?.let { "id:${it.value}" } ?: "handle:$address"
        return repository.disabled(selection.driverId, key)
    }
    /** 卡 144：用户手动重新启用被停用的驱动；下次收音或管理时重新连接 */
    fun enableDriver(address: String) {
        val selection = repository.selections().firstOrNull { it.platformHandle == address } ?: return
        val key = selection.deviceId?.let { "id:${it.value}" } ?: "handle:$address"
        repository.setDisabled(selection.driverId, key, null)
        update(address) { it.copy(lastError = null) }
    }
    fun select(choice: InputChoice) { selected.value = (choice as? InputChoice.Device)?.key }
    fun recording(value: Boolean) { active.value = value }

    /** 管理操作与收音共用连接租约；未收音时只短暂连接，不启动声音或补传。 */
    suspend fun <T> withDevice(address: String, action: suspend (DriverConnection) -> T): T {
        suspend fun use(hub: DeviceHub): T {
            try {
                withTimeout(20_000) {
                    val device = hub.devices.first { rows -> rows.any { it.platformHandle == address &&
                        it.connection in setOf(ConnectionState.Connected, ConnectionState.Backfilling, ConnectionState.Disabled) } }
                        .first { it.platformHandle == address }
                    if (device.connection == ConnectionState.Disabled) throw DeviceException(ErrorCode.StateConflict)
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw DeviceException(ErrorCode.Timeout,cause=e)
            }
            return hub.withConnection(address,LeaseOwner.Management) { connection ->
                action(connection).also { cacheStatus(address,connection.status.value) }
            }
        }
        runningHub.value?.takeIf { it.handle == address }?.let { return use(it.hub) }
        return coroutineScope {
            val mutex = connectionLock(address)
            val waiter = async(start=CoroutineStart.UNDISPATCHED) { runningHub.first { it?.handle == address }!! }
            val active = try {
                select<ActiveHub?> {
                    mutex.onLock { null }
                    waiter.onAwait { it }
                }
            } finally { waiter.cancel() }
            if (active != null) return@coroutineScope use(active.hub)
            try {
            val row = remembered.value.firstOrNull { it.address == address } ?: throw DeviceException(ErrorCode.NotFound)
            val installed = cards.cards.value.filter { it.enabled }
            val registration = registered.firstOrNull { it.driver.meta.id == row.cardId }
            val card = registration?.transport ?: installed.firstOrNull { it.card.id == row.cardId }?.card
                ?: throw DeviceException(ErrorCode.NotFound)
            val id = registration?.driver?.meta?.id ?: "card:${card.id}"
            val catalog = object : app.resound.devices.driver.DriverCatalog {
                override val drivers = installed.map { item -> CardDriver(item.card,true,plugins.firstOrNull { it.id == item.card.filePlugin }) } + registered.map { it.driver }
            }
            coroutineScope {
                val hub = DeviceHub(catalog,repository,HubConnector { handle ->
                    val transport = connector(handle,card)
                    object : HubLink {
                        override val gatt: GattLink = CardLink(transport.gatt,identity(handle))
                        override val disconnected = transport.disconnected
                        override suspend fun close() = transport.close()
                    }
                },toolbox,clock,extrasFor(address,id),this)
                try {
                    hub.select(Advertisement(address,row.name,emptySet(),emptyMap(),null),id)
                    if (registration != null) {
                        val selection = repository.selections().firstOrNull { it.platformHandle == address && it.driverId == id }
                        val key = selection?.deviceId?.let { "id:${it.value}" } ?: "handle:$address"
                        repository.store(id,key).apply {
                            putString("platformHandle",address)
                            putString("platformIdentity",identity(address).id.value)
                        }
                    }
                    hub.connect(address)
                    use(hub)
                } finally { withContext(NonCancellable) { hub.close() } }
            }
            } finally { mutex.unlock() }
        }
    }

    /** 手动补传由应用持有；关闭详情不取消，暂停则等待连接清理并保留续传记录。 */
    fun startSyncDevice(address: String) {
        var message = "离线录音已检查完；未成功的任务可再次检查"
        var errorCode: ErrorCode? = null
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                syncDevice(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                errorCode = e.code
                message = "检查没有完成，已保留进度，请稍后重试"
            } catch (_: Exception) {
                message = "检查没有完成，已保留进度，请稍后重试"
            }
        }
        while (true) {
            val old = syncTasks.value
            if (old[address]?.state?.running == true) { task.cancel(); return }
            if (syncTasks.compareAndSet(old, old + (address to ManualSyncEntry(task, ManualSyncState(true))))) break
        }
        task.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                message = "补传已暂停，已保存的进度会在下次检查时继续"
                errorCode = null
            }
            update(address) { it.copy(backfill = null) }
            syncTasks.update { rows ->
                if (rows[address]?.task === task) rows + (address to ManualSyncEntry(task, ManualSyncState(false, message = message, errorCode = errorCode))) else rows
            }
        }
        task.start()
    }

    fun pauseSyncDevice(address: String) {
        val entry = syncTasks.value[address]?.takeIf { it.state.running } ?: return
        syncTasks.update { rows ->
            if (rows[address] === entry) rows + (address to entry.copy(state = entry.state.copy(pausing = true))) else rows
        }
        entry.task.cancel()
    }

    /** 显式检查已保存录音；即使关闭自动补传也执行，设备录音中由驱动返回 StateConflict。 */
    suspend fun syncDevice(address: String) {
        val row = remembered.value.firstOrNull { it.address == address } ?: throw DeviceException(ErrorCode.NotFound)
        if (registered.none { it.driver.meta.id == row.cardId }) throw DeviceException(ErrorCode.Unsupported)
        val unused = object : DeviceLink {
            override val mtu = 0
            override suspend fun read(service: String, characteristic: String): ByteArray = throw DeviceException(ErrorCode.Unsupported)
            override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) = throw DeviceException(ErrorCode.Unsupported)
            override fun notifications(service: String, characteristic: String): Flow<ByteArray> = emptyFlow()
        }
        withDevice(address) { connection ->
            if (connection.files == null) throw DeviceException(ErrorCode.Unsupported)
            onDriverLinked(row,connection,unused,true) { progress -> update(address) { it.copy(backfill=progress) } }
        }
    }
    fun audioSource(key: String): AudioSource = object : AudioSource, AudioTimeline {
        override val id = key
        override val displayName = devices.value.find { deviceKey(it.address) == key }?.name ?: "专用录音设备"
        private var current: Feed.Frame? = null
        private var lastLive: LiveAudio? = null
        private var beforeWall = 0L
        private var beforeMono = 0L
        private var anchorId: String? = null
        override val frameEpochMillis: Long?
            get() = current?.let { frame ->
                val time = frame.chunk.deviceTime?.epochMillis
                val offset = frame.clockOffset
                if (time == null || offset == null) null else time + offset
            }
        override val frameArchiveId: String?
            get() = current?.chunk?.origin?.let { origin ->
                anchorId ?: "$key:${origin.fileId}:${origin.offsetMillis}:$beforeWall".also { anchorId = it }
            }
        override fun processed(samples: Int) {
            current?.let { frame -> frame.chunk.origin?.let { origin ->
                frame.live.processed(origin, samples)
                val id = anchorId
                if (id != null) {
                    val afterWall = clock.nowEpochMillis()
                    val afterMono = clock.monotonicMillis()
                    val elapsed = afterMono - beforeMono
                    if (elapsed >= 0 && kotlin.math.abs((afterWall - beforeWall) - elapsed) <= 100) {
                        val exact = frame.chunk.deviceTime?.epochMillis?.let { t -> frame.clockOffset?.let { t + it } }
                        val duration = frame.chunk.pcm.size.toLong() * 1000 / AudioSource.SAMPLE_RATE
                        val lower = exact ?: (beforeWall - duration)
                        val upper = exact ?: (afterWall - duration)
                        val saved = repository.selections().firstOrNull { deviceKey(it.platformHandle) == key }
                        if (saved != null) {
                            val storageKey = saved.deviceId?.let { "id:${it.value}" } ?: "handle:${saved.platformHandle}"
                            repository.store(saved.driverId, storageKey).putString("audioAnchor:$id",
                                "${origin.offsetMillis}|$lower|$upper|${origin.fileId}")
                        }
                    }
                    anchorId = null
                }
            } }
        }
        override fun finished(discarded: Boolean) { lastLive?.captureFinished(discarded); current = null; lastLive = null }
        override fun events(): Flow<AudioEvent> = flow {
            val feed = stream.value?.takeIf { it.key == key && selected.value == key }
                ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected)
            feed.events().collect { frame ->
                current = frame
                lastLive = frame.live
                anchorId = null
                beforeWall = clock.nowEpochMillis()
                beforeMono = clock.monotonicMillis()
                emit(AudioEvent.Frame(frame.chunk.pcm))
            }
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
                        val match = cards.cards.value.firstOrNull { CardDriver(it.card, it.enabled).match(ad) == MatchConfidence.Certain }
                        val plugin = if (match == null) registered.firstOrNull { it.driver.match(ad) == MatchConfidence.Certain } else null
                        if (match == null && plugin == null) return@collect
                        val id = match?.card?.id ?: requireNotNull(plugin).driver.meta.id
                        val name = match?.card?.name ?: requireNotNull(plugin).driver.meta.name.zh
                        val trial = match?.card?.status == CardStatus.Trial || plugin?.driver?.meta?.stability == Stability.Trial
                        val device = FoundDevice(ad.platformHandle, ad.name?.takeIf { it.isNotBlank() } ?: name, id, trial, ad.rssi)
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
        val card = cards.cards.value.find { it.enabled && it.card.id == device.cardId }?.card
            ?: registered.firstOrNull { it.driver.meta.id == device.cardId }?.transport ?: return
        if (found.value.none { it.address == device.address && it.cardId == device.cardId }) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                bond(device.address, card.link.bond)
                savedLock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!available(card.id, cards.cards.value)) return@withLock
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
        pauseSyncDevice(address)
        pairing.getAndUpdate { it - address }[address]?.cancel()
        scope.launch {
            savedLock.withLock {
                val next = remembered.value.filterNot { it.address == address }
                store.write(next); remembered.value = next
                states.update { it.filterNot { row -> row.address == address } }
                cachedStatus.update { it - address }
            }
            connectionLock(address).withLock { repository.forget(address) }
        }
    }
    companion object { fun deviceKey(address: String) = "CardDevice:$address" }

    /** 有界广播加独立结束信号：迟来的收集器也能收到断开，取消收集不会关闭后台连接。 */
    private class Feed(val key: String) {
        data class Frame(val chunk: AudioChunk, val live: LiveAudio, val clockOffset: Long?)
        private val frames = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
        private val ended = CompletableDeferred<AudioSourceException>()
        fun events(): Flow<Frame> = channelFlow {
            launch { frames.collect { send(it) } }
            throw ended.await()
        }.buffer(0)
        fun send(event: Frame) {
            if (!frames.tryEmit(event)) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备声音消费超时")
        }
        fun close(error: AudioSourceException) { ended.complete(error) }
    }
}
