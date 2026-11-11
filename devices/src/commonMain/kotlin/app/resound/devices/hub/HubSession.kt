package app.resound.devices.hub

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** 同步 schedule/log 用 StateFlow CAS；不依赖线程、阻塞锁或平台对象。 */
internal class HubSession(
    override val link: GattLink,
    providedToolbox: Toolbox,
    override val clock: ClockPort,
    override val extras: PlatformExtras,
    private val repository: HubRepository,
    private val driverId: String,
    initialKey: String,
    parent: CoroutineScope,
    private val boundary: DriverBoundary,
    private val logger: (LogLevel, String) -> Unit,
) : DriverSession {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val tasks = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val decoders = MutableStateFlow<List<OpusDecoder>>(emptyList())
    override val toolbox = object : Toolbox {
        override val crypto get() = providedToolbox.crypto
        override fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder {
            if (!job.isActive) throw DeviceException(ErrorCode.Disconnected)
            val decoder = providedToolbox.opusDecoder(sampleRate, channels)
            val open = MutableStateFlow(true)
            val tracked = object : OpusDecoder {
                override fun decode(packet: ByteArray) = decoder.decode(packet)
                override fun close() {
                    if (open.compareAndSet(true, false)) {
                        try { decoder.close() }
                        finally { decoders.update { all -> all.filterNot { it === this } } }
                    }
                }
            }
            decoders.update { it + tracked }
            if (!job.isActive) tracked.close()
            return tracked
        }
    }
    private val delegate = MutableStateFlow(repository.store(driverId, initialKey))
    private var storeKey = initialKey
    // open 中保存的 store 引用在身份确定后仍指向正式命名空间。
    override val store = object : DriverStore {
        override fun getString(key: String) = delegate.value.getString(key)
        override fun putString(key: String, value: String?) = delegate.value.putString(key, value)
        override fun getBytes(key: String) = delegate.value.getBytes(key)
        override fun putBytes(key: String, value: ByteArray?) = delegate.value.putBytes(key, value)
        override fun keys() = delegate.value.keys()
    }
    fun identify(id: DeviceId) {
        val nextKey = "id:${id.value}"
        if (storeKey == nextKey) return
        val source = delegate.value
        val target = repository.store(driverId, nextKey)
        val keys = source.keys()
        keys.forEach { key ->
            // open 新写入的认证/进度优先；未被改写的历史字段仍在正式身份空间保留。
            source.getString(key)?.let { target.putString(key, it) }
            source.getBytes(key)?.let { target.putBytes(key, it) }
        }
        delegate.value = target
        storeKey = nextKey
        keys.forEach { key -> source.putString(key, null); source.putBytes(key, null) }
    }
    override fun schedule(name: String, everyMillis: Long, task: suspend () -> Unit): ScheduledTask {
        if (!job.isActive) throw DeviceException(ErrorCode.Disconnected)
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                delay(everyMillis.coerceAtLeast(30_000))
                try { boundary.call { task() } }
                catch (_: DeviceException) { /* 边界已记账，下一次仍可执行。 */ }
            }
        }
        while (true) {
            val old = tasks.value
            if (name in old || old.size >= 4) { timer.cancel(); throw DeviceException(ErrorCode.BusyRetry) }
            if (tasks.compareAndSet(old, old + (name to timer))) break
        }
        timer.invokeOnCompletion { tasks.update { if (it[name] === timer) it - name else it } }
        timer.start()
        return object : ScheduledTask { override fun cancel() { timer.cancel() } }
    }
    private val heartbeat = MutableStateFlow<Job?>(null)
    override fun keepAlive(everyMillis: Long, task: suspend () -> Unit): ScheduledTask {
        if (!job.isActive) throw DeviceException(ErrorCode.Disconnected)
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                delay(everyMillis.coerceAtLeast(1_000))
                try { boundary.call { task() } }
                catch (_: DeviceException) { /* 边界已记账，下一次仍可执行。 */ }
            }
        }
        heartbeat.getAndUpdate { timer }?.cancel()
        timer.invokeOnCompletion { heartbeat.compareAndSet(timer, null) }
        timer.start()
        return object : ScheduledTask { override fun cancel() { timer.cancel() } }
    }
    override fun log(level: LogLevel, message: String) = logger(level, message)
    suspend fun close() {
        job.cancelAndJoin()
        decoders.value.forEach { decoder ->
            try { boundary.call { decoder.close() } } catch (_: DeviceException) { }
        }
        decoders.value = emptyList()
    }
}
