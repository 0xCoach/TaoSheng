package app.resound.devices.replay

import app.resound.devices.driver.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** sink 必须快速、非阻塞；平台负责文件头、串行落盘和生命周期。录包失败不改变蓝牙调用结果。 */
class RecordingGattLink(
    private val inner: GattLink,
    private val clock: ClockPort,
    private val sink: (String) -> Unit,
) : GattLink {
    private val start = clock.monotonicMillis()
    private val lengths = MutableStateFlow<Map<Boolean, Int>>(emptyMap())
    private val disconnected = MutableStateFlow(false)
    private fun at() = (clock.monotonicMillis() - start).coerceAtLeast(0)
    private fun record(value: () -> String) {
        try { sink(value()) } catch (_: Exception) { /* 诊断失败不能遮蔽原始结果或异常。 */ }
    }
    private fun error(operation: Char, service: String, characteristic: String, cause: Throwable) = record {
        val code = when (cause) {
            is DeviceException -> cause.code.name
            is CancellationException -> "Cancelled"
            else -> "Failure"
        }
        "${at()} E $service $characteristic ${"$operation|$code|${cause.message.orEmpty()}".encodeToByteArray().hex()}"
    }
    override fun maxWriteLength(withResponse: Boolean): Int = try {
        inner.maxWriteLength(withResponse).also { size ->
            while (true) {
                val old = lengths.value
                if (old[withResponse] == size) break
                if (lengths.compareAndSet(old, old + (withResponse to size))) {
                    record { "M ${at()} mtu=$size mode=${if (withResponse) 'W' else 'w'}" }; break
                }
            }
        }
    } catch (e: Throwable) { error('M', "-", "-", e); throw e }

    override suspend fun read(service: String, characteristic: String): ByteArray = try {
        inner.read(service, characteristic).also { bytes -> record { "${at()} R $service $characteristic ${bytes.hex()}" } }
    } catch (e: Throwable) { error('R', service, characteristic, e); throw e }

    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        val direction = if (withResponse) 'W' else 'w'
        // 先记请求，保证在 write 尚未返回时收到的通知仍位于命令之后。
        record { "${at()} $direction $service $characteristic ${bytes.hex()}" }
        try { inner.write(service, characteristic, bytes, withResponse) }
        catch (e: Throwable) { error(direction, service, characteristic, e); throw e }
    }

    override fun notifications(service: String, characteristic: String): Flow<ByteArray> {
        val source = try { inner.notifications(service, characteristic) }
        catch (e: Throwable) { error('N', service, characteristic, e); throw e }
        return flow {
            var downstreamFailure: Throwable? = null
            try {
                source.collect { bytes ->
                    record { "${at()} N $service $characteristic ${bytes.hex()}" }
                    try { emit(bytes) }
                    catch (e: Throwable) { downstreamFailure = e; throw e }
                }
            } catch (e: Throwable) {
                // 包括上游取消；消费者的异常不应被误记成蓝牙故障。
                if (e !== downstreamFailure) error('N', service, characteristic, e)
                throw e
            }
        }
    }

    /** GattLink 没有断开事件；持有物理连接的平台调用，重复调用只记一次。 */
    fun recordDisconnected() {
        if (disconnected.compareAndSet(false, true)) record { "${at()} D - - -" }
    }
}
