package app.resound.devices.hub

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 不扫描、不写文件；所有平台能力与生命周期均由装配层注入。 */
class DeviceHub(
    catalog: DriverCatalog,
    private val repository: HubRepository,
    private val connector: HubConnector,
    private val toolbox: Toolbox,
    private val clock: ClockPort,
    private val extras: PlatformExtras,
    parent: CoroutineScope,
) {
    private val drivers = catalog.drivers.associateBy { it.meta.id }.also {
        require(it.size == catalog.drivers.size) { "Duplicate driver id" }
    }
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Mutex()
    private val lifecycle = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val leases = mutableMapOf<String, Mutex>()
    private val faults = mutableMapOf<Pair<String, String>, List<Long>>()
    private val snapshots = MutableStateFlow<List<HubDevice>>(emptyList())
    val devices: StateFlow<List<HubDevice>> = snapshots.asStateFlow()
    private val logs = MutableStateFlow<Map<String, List<Diagnostic>>>(emptyMap())
    private class Entry(val handle: String, val driver: DeviceDriver) {
        var runner: Job? = null
        val connection = MutableStateFlow<GuardedConnection?>(null)
    }
    private fun selection(handle: String) = repository.selections().firstOrNull { it.platformHandle == handle }
    private fun key(handle: String) = selection(handle)?.deviceId?.let { "id:${it.value}" } ?: "handle:$handle"
    private fun reason(handle: String, driver: String) = repository.disabled(driver, key(handle))
    private fun update(handle: String, transform: (HubDevice) -> HubDevice) {
        snapshots.update { rows -> rows.map { if (it.platformHandle == handle) transform(it) else it } }
    }
    private fun log(handle: String, level: LogLevel, text: String) {
        val entry = Diagnostic(clock.nowEpochMillis(), level, text.take(2048))
        logs.update { it + (handle to (it[handle].orEmpty() + entry).takeLast(500)) }
    }
    fun diagnostics(platformHandle: String): List<Diagnostic> = logs.value[platformHandle].orEmpty()
    private fun boundary(handle: String, driver: String) = DriverBoundary(clock,
        fault = { code -> recordFault(handle, driver, code) },
        error = { code -> update(handle) { it.copy(lastError = code) } })

    private suspend fun recordFault(handle: String, driver: String, code: ErrorCode) = lock.withLock {
        val index = key(handle) to driver
        val now = clock.monotonicMillis()
        val recent = faults[index].orEmpty().filter { now - it in 0 until 600_000 } + now
        faults[index] = recent
        log(handle, LogLevel.Error, "driver=$driver error=$code faults=${recent.size}")
        update(handle) { it.copy(lastError = code) }
        if (recent.size >= 5) {
            repository.setDisabled(driver, key(handle), DisableReason.FaultLimit)
            if (snapshots.value.none { it.platformHandle == handle }) {
                drivers[driver]?.meta?.let { meta -> snapshots.update { it + HubDevice(handle, meta.name.zh, meta, lastError = code) } }
            }
            val affected = snapshots.value.filter { it.driver.id == driver && key(it.platformHandle) == key(handle) }
            affected.forEach { row ->
                update(row.platformHandle) { it.copy(connection = ConnectionState.Disabled, disabledReason = DisableReason.FaultLimit, retryInMillis = null) }
                entries[row.platformHandle]?.runner?.cancel()
            }
        }
    }

    /** 只认设备，不建立连接。缓存驱动缺失时重新匹配；停用不会悄悄改选其他驱动。 */
    suspend fun match(ad: Advertisement): MatchResult {
        selection(ad.platformHandle)?.let { saved -> drivers[saved.driverId]?.let { driver ->
            reason(ad.platformHandle, saved.driverId)?.let { return MatchResult.Disabled(saved.driverId, it) }
            return MatchResult.Selected(driver.meta)
        } }
        val certain = mutableListOf<DriverMeta>()
        val possible = mutableListOf<DriverMeta>()
        for ((id, driver) in drivers) {
            if (reason(ad.platformHandle, id) != null) continue
            val confidence = try { boundary(ad.platformHandle, id).call { driver.match(ad) } }
                catch (_: DeviceException) { MatchConfidence.None }
            when (confidence) {
                MatchConfidence.Certain -> certain += driver.meta
                MatchConfidence.Possible -> possible += driver.meta
                MatchConfidence.None -> Unit
            }
        }
        return when {
            certain.size > 1 -> MatchResult.Conflict(certain.map { it.id }).also {
                log(ad.platformHandle, LogLevel.Error, "conflict=${it.driverIds.joinToString()}")
            }
            certain.size == 1 -> select(ad, certain.single().id)
            possible.isNotEmpty() -> MatchResult.Candidates(possible)
            else -> drivers.keys.firstNotNullOfOrNull { id -> reason(ad.platformHandle, id)?.let { MatchResult.Disabled(id, it) } } ?: MatchResult.None
        }
    }

    /** 用户从候选中选择时调用。只保存选择；connect 单独启动生命周期。 */
    suspend fun select(ad: Advertisement, driverId: String): MatchResult = lock.withLock {
        val driver = drivers[driverId] ?: throw DeviceException(ErrorCode.NotFound)
        reason(ad.platformHandle, driverId)?.let { return@withLock MatchResult.Disabled(driverId, it) }
        if (entries[ad.platformHandle]?.runner?.isActive == true && selection(ad.platformHandle)?.driverId != driverId)
            throw DeviceException(ErrorCode.BusyRetry)
        val old = selection(ad.platformHandle)
        repository.save(DriverSelection(ad.platformHandle, driverId, old?.deviceId))
        if (snapshots.value.none { it.platformHandle == ad.platformHandle }) {
            snapshots.update { it + HubDevice(ad.platformHandle, ad.name?.takeIf(String::isNotBlank) ?: driver.meta.name.zh, driver.meta) }
        } else update(ad.platformHandle) { it.copy(driver = driver.meta) }
        MatchResult.Selected(driver.meta)
    }

    suspend fun connect(platformHandle: String) = lifecycle.withLock { startConnection(platformHandle) }

    private suspend fun startConnection(platformHandle: String) = lock.withLock {
        job.ensureActive()
        val saved = selection(platformHandle) ?: throw DeviceException(ErrorCode.NotFound)
        val driver = drivers[saved.driverId] ?: throw DeviceException(ErrorCode.NotFound)
        if (snapshots.value.none { it.platformHandle == platformHandle })
            snapshots.update { it + HubDevice(platformHandle, driver.meta.name.zh, driver.meta) }
        reason(platformHandle, saved.driverId)?.let { why ->
            update(platformHandle) { it.copy(connection = ConnectionState.Disabled, disabledReason = why) }
            return@withLock
        }
        if (entries[platformHandle]?.runner?.isActive == true) return@withLock
        val entry = Entry(platformHandle, driver)
        entries[platformHandle] = entry
        entry.runner = scope.launch { runConnection(entry) }
    }

    private suspend fun runConnection(entry: Entry) {
        val handle = entry.handle
        val driverId = entry.driver.meta.id
        val b = boundary(handle, driverId)
        var retry = 3_000L
        try {
            while (currentCoroutineContext().isActive && reason(handle, driverId) == null) {
                var link: HubLink? = null
                var session: HubSession? = null
                var raw: DriverConnection? = null
                var connectedAt: Long? = null
                update(handle) { it.copy(connection = ConnectionState.Connecting, retryInMillis = null) }
                try {
                    coroutineScope {
                        withTimeout(15_000) { connector.connect(handle).also { link = it } }
                        val transport = requireNotNull(link)
                        // 从 open 起监听物理断开，不能等驱动返回后才发现失联。
                        val lost = async { transport.disconnected.firstOrNull() ?: DeviceException(ErrorCode.Disconnected) }
                        val connectionScope = this
                        val opened = async {
                            session = HubSession(transport.gatt, toolbox, clock, extras, repository, driverId, key(handle),
                                connectionScope, b) { level, message -> log(handle, level, message) }
                            b.call { entry.driver.open(requireNotNull(session)).also { raw = it } }
                            GuardedConnection.create(requireNotNull(raw), b)
                        }
                        val connection = kotlinx.coroutines.selects.select<GuardedConnection> {
                            opened.onAwait { it }
                            lost.onAwait { throw it }
                        }
                        lock.withLock {
                            val priorKey = key(handle)
                            val faultKey = priorKey to driverId
                            session!!.identify(connection.identity.id)
                            repository.save(DriverSelection(handle, driverId, connection.identity.id))
                            val identityKey = key(handle)
                            if (priorKey != identityKey) faults.remove(faultKey)?.let {
                                faults[identityKey to driverId] = (faults[identityKey to driverId].orEmpty() + it).sorted()
                            }
                        }
                        reason(handle, driverId)?.let { throw DeviceException(ErrorCode.StateConflict) }
                        connectedAt = clock.monotonicMillis()
                        val initialStatus = b.call { connection.status.value }
                        update(handle) { it.copy(identity = connection.identity, connection = ConnectionState.Connected,
                            disabledReason = null, status = initialStatus, lastError = null) }
                        entry.connection.value = connection
                        val status = launch { b.stream { connection.status }.collect { value -> update(handle) { it.copy(status = value) } } }
                        val closed = async { connection.closed.firstOrNull() ?: DeviceException(ErrorCode.Disconnected) }
                        try {
                            throw kotlinx.coroutines.selects.select<DeviceException> {
                                lost.onAwait { it }
                                closed.onAwait { it }
                            }
                        } finally { status.cancel(); closed.cancel(); lost.cancel() }
                    }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    update(handle) { it.copy(lastError = ErrorCode.Timeout) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { update(handle) { it.copy(lastError = (e as? DeviceException)?.code ?: ErrorCode.Disconnected) } }
                finally {
                    entry.connection.value = null
                    withContext(NonCancellable) {
                        session?.close()
                        try { raw?.let { b.call { it.close() } } } catch (_: DeviceException) { }
                        try { withTimeout(15_000) { link?.close() } } catch (_: Exception) { }
                    }
                }
                if (reason(handle, driverId) != null) break
                if (connectedAt?.let { clock.monotonicMillis() - it >= 60_000 } == true) retry = 3_000
                update(handle) { it.copy(connection = ConnectionState.WaitingReconnect, retryInMillis = retry) }
                delay(retry)
                retry = (retry * 2).coerceAtMost(60_000)
            }
        } finally {
            val why = reason(handle, driverId)
            update(handle) { it.copy(connection = if (why == null) ConnectionState.Disconnected else ConnectionState.Disabled,
                disabledReason = why, retryInMillis = null) }
        }
    }

    /** 租约块内所有子任务结束后才释放。断连取消租约块；外部取消保留原取消语义。 */
    suspend fun <T> withConnection(platformHandle: String, owner: LeaseOwner, wait: Boolean = true,
        block: suspend (DriverConnection) -> T): T {
        val (entry, mutex) = lock.withLock {
            val e = entries[platformHandle] ?: throw DeviceException(ErrorCode.Disconnected)
            e to leases.getOrPut(key(platformHandle)) { Mutex() }
        }
        if (wait) mutex.lock() else if (!mutex.tryLock()) throw DeviceException(ErrorCode.BusyRetry)
        try {
            val connection = entry.connection.value ?: throw DeviceException(ErrorCode.Disconnected)
            if (reason(platformHandle, entry.driver.meta.id) != null) throw DeviceException(ErrorCode.StateConflict)
            if (owner == LeaseOwner.Backfill) update(platformHandle) { it.copy(connection = ConnectionState.Backfilling) }
            return coroutineScope {
                val operation = async { block(connection) }
                val watcher = launch { entry.connection.first { it !== connection }; operation.cancel() }
                try { operation.await() }
                catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DeviceException(ErrorCode.Disconnected, cause = e)
                } finally { watcher.cancel() }
            }
        } finally {
            update(platformHandle) { if (it.connection == ConnectionState.Backfilling) it.copy(connection = ConnectionState.Connected) else it }
            mutex.unlock()
        }
    }

    suspend fun disable(platformHandle: String, reason: DisableReason = DisableReason.User) = lifecycle.withLock {
        val runners = lock.withLock {
            val saved = selection(platformHandle) ?: throw DeviceException(ErrorCode.NotFound)
            val deviceKey = key(platformHandle)
            repository.setDisabled(saved.driverId, deviceKey, reason)
            snapshots.value.filter { it.driver.id == saved.driverId && key(it.platformHandle) == deviceKey }.mapNotNull { row ->
                update(row.platformHandle) { it.copy(connection = ConnectionState.Disabled, disabledReason = reason) }
                entries[row.platformHandle]?.runner
            }
        }
        runners.forEach { it.cancel() }; runners.forEach { it.join() }
    }
    suspend fun enable(platformHandle: String) = lifecycle.withLock {
        val saved = lock.withLock {
            val saved = selection(platformHandle)
            val driverId = saved?.driverId ?: snapshots.value.firstOrNull { it.platformHandle == platformHandle }?.driver?.id
                ?: throw DeviceException(ErrorCode.NotFound)
            repository.setDisabled(driverId, key(platformHandle), null)
            faults.remove(key(platformHandle) to driverId)
            update(platformHandle) { it.copy(connection = ConnectionState.Disconnected, disabledReason = null, lastError = null) }
            saved
        }
        // 自动停用的清理可能仍在进行，先释放旧会话再重启，不能复用正在退出的 runner。
        lock.withLock { entries[platformHandle]?.runner }?.cancelAndJoin()
        if (saved != null) startConnection(platformHandle)
    }
    suspend fun forget(platformHandle: String) = lifecycle.withLock {
        val runner = lock.withLock { entries.remove(platformHandle)?.runner }
        runner?.cancelAndJoin()
        lock.withLock {
            selection(platformHandle)?.let { faults.remove(key(platformHandle) to it.driverId) }
            repository.forget(platformHandle)
            snapshots.update { it.filterNot { row -> row.platformHandle == platformHandle } }
            logs.update { it - platformHandle }
        }
    }
    suspend fun close() = lifecycle.withLock { job.cancelAndJoin() }
}
