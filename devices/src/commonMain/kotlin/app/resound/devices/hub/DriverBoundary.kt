package app.resound.devices.hub

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*

/** 只计驱动意外异常/超时；外部取消和预期的协议错误不计故障。 */
internal class DriverBoundary(
    private val clock: ClockPort,
    private val fault: suspend (ErrorCode) -> Unit,
    private val error: (ErrorCode) -> Unit,
    private val timeoutMillis: Long = 15_000,
) {
    suspend fun <T> call(block: suspend () -> T): T {
        val start = clock.monotonicMillis()
        try {
            val result = withTimeout(timeoutMillis) { block() }
            // 同步 match/属性不能抢占；至少在返回后识别超时，不把它当作成功。
            if (clock.monotonicMillis() - start >= timeoutMillis) {
                fault(ErrorCode.Timeout)
                throw DeviceException(ErrorCode.Timeout)
            }
            return result
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            fault(ErrorCode.Timeout)
            throw DeviceException(ErrorCode.Timeout, cause = e)
        } catch (e: CancellationException) { throw e }
        catch (e: DeviceException) { error(e.code); throw e }
        catch (e: Exception) {
            fault(ErrorCode.ProtocolError)
            throw DeviceException(ErrorCode.ProtocolError, cause = e)
        }
    }

    /** 计时只覆盖等待下一块，消费者保存数据的时间不算驱动超时。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> stream(blockTimeout: Long? = null, source: () -> Flow<T>): Flow<T> = flow {
        coroutineScope {
            val input = produce<T>(capacity = 0) {
                try { source().collect { send(it) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { channel.close(e) }
            }
            try {
                while (true) {
                    val item = try {
                        if (blockTimeout == null) input.receiveCatching()
                        else withTimeout(blockTimeout) { input.receiveCatching() }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        fault(ErrorCode.Timeout)
                        throw DeviceException(ErrorCode.Timeout, cause = e)
                    }
                    item.exceptionOrNull()?.let { failure -> call { throw failure } }
                    if (item.isClosed) break
                    emit(item.getOrThrow())
                }
            } finally { input.cancel() }
        }
    }
}

/** 所有交给租约持有者的驱动方法均经过边界；不得把原始连接交给调用方。 */
internal class GuardedConnection private constructor(
    private val raw: DriverConnection,
    private val boundary: DriverBoundary,
    override val identity: DeviceIdentity,
    override val capabilities: Set<Capability>,
    override val live: LiveAudio?,
    override val files: DeviceFiles?,
    override val status: StateFlow<DeviceStatusModel>,
    override val controls: DeviceControls,
    override val settings: DeviceSettings?,
    override val policy: DriverPolicy,
    override val binding: DeviceBinding?,
    override val closed: Flow<DeviceException>,
) : DriverConnection {
    override suspend fun close() = boundary.call { raw.close() }
    companion object {
        suspend fun create(raw: DriverConnection, b: DriverBoundary): GuardedConnection = b.call {
            GuardedConnection(raw, b, raw.identity, raw.capabilities.toSet(), raw.live?.let { live ->
                object : LiveAudio {
                    override suspend fun start() = b.call { live.start() }
                    override suspend fun stop() = b.call { live.stop() }
                    override val frames = b.stream { live.frames }
                }
            }, raw.files?.let { files ->
                val caps = files.caps
                object : DeviceFiles {
                    override val caps = caps
                    override suspend fun list() = b.call { files.list() }
                    override fun read(file: RecordingFile, fromOffset: Long) = b.stream(30_000) { files.read(file, fromOffset) }
                    override suspend fun confirm(file: RecordingFile, checksum: ByteArray?) = b.call { files.confirm(file, checksum) }
                    override suspend fun delete(file: RecordingFile) = b.call { files.delete(file) }
                }
            }, raw.status, raw.controls.let { controls ->
                object : DeviceControls {
                    override suspend fun setRecording(target: RecordingState) = b.call { controls.setRecording(target) }
                    override suspend fun lightModes() = b.call { controls.lightModes() }
                    override suspend fun setLight(modeId: Int) = b.call { controls.setLight(modeId) }
                    override suspend fun syncClock(epochMillis: Long, zoneOffsetMinutes: Int) = b.call { controls.syncClock(epochMillis, zoneOffsetMinutes) }
                    override suspend fun identify(seconds: Int) = b.call { controls.identify(seconds) }
                    override suspend fun refresh() = b.call { controls.refresh() }
                    override suspend fun diagnostics() = b.call { controls.diagnostics() }
                }
            }, raw.settings?.let { settings ->
                object : DeviceSettings {
                    override suspend fun schema() = b.call { settings.schema() }
                    override suspend fun set(key: String, value: SettingValue) = b.call { settings.set(key, value) }
                }
            }, raw.policy, raw.binding?.let { binding ->
                object : DeviceBinding {
                    override suspend fun isBound() = b.call { binding.isBound() }
                    override suspend fun bind(prompt: suspend (BindPrompt) -> Boolean) = b.call { binding.bind(prompt) }
                    override suspend fun unbind() = b.call { binding.unbind() }
                }
            }, b.stream { raw.closed })
        }
    }
}
