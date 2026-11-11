package app.resound.devices.replay

import app.resound.devices.driver.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 只忽略匹配通道上的指定零基字节位置；长度、方向与其他字节仍严格比较。 */
data class WriteMask(val service: String, val characteristic: String, val positions: Set<Int>) {
    init { require(positions.all { it >= 0 }) }
}

/**
 * 测试通道：在测试调度器的单一执行上下文中使用；每次调用同步推进到下个 R/W/w。
 * 不创建后台任务或真实时间等待。订阅者使用独立队列，慢消费者不阻塞写入；D/EOF 结束通知。
 */
class ReplayGattLink(capture: String, private val masks: List<WriteMask> = emptyList()) : GattLink {
    private val parsed = CaptureText(capture)
    val header: CaptureHeader get() = parsed.header
    private var cursor = 0
    private val lengths = mutableMapOf(true to 20, false to 20)
    private var ended = false
    private var disconnected = false
    private data class Delivery(val bytes: ByteArray? = null, val error: Throwable? = null)
    private val listeners = mutableMapOf<Pair<String, String>, MutableList<Channel<Delivery>>>()
    private val pending = mutableMapOf<Pair<String, String>, MutableList<Delivery>>()
    private val disconnectEvents = MutableSharedFlow<DeviceException>(replay = 1)
    val disconnections: Flow<DeviceException> = disconnectEvents.asSharedFlow()
    val elapsedMillis: Long get() = parsed.records.getOrNull(cursor - 1)?.at ?: 0

    init { pump() }

    override fun maxWriteLength(withResponse: Boolean): Int {
        pump()
        val next = parsed.records.getOrNull(cursor)
        if (next?.kind == 'E' && errorOperation(next) == 'M') { cursor++; throw recordedError(next) }
        return lengths.getValue(withResponse)
    }

    override suspend fun read(service: String, characteristic: String): ByteArray {
        pump()
        val record = next("R $service $characteristic")
        if (record.kind == 'E' && errorOperation(record) == 'R' &&
            record.service == service && record.characteristic == characteristic) {
            cursor++; throw recordedError(record)
        }
        if (record.kind != 'R' || record.service != service || record.characteristic != characteristic)
            mismatch(record, "R $service $characteristic")
        cursor++
        pump()
        return record.bytes.copyOf()
    }

    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        pump()
        val direction = if (withResponse) 'W' else 'w'
        val actual = "$direction $service $characteristic ${bytes.hex()}"
        val record = next(actual)
        val ignored = masks.filter { it.service == service && it.characteristic == characteristic }.flatMap { it.positions }.toSet()
        if (record.kind != direction || record.service != service || record.characteristic != characteristic ||
            record.bytes.size != bytes.size || bytes.indices.any { it !in ignored && bytes[it] != record.bytes[it] })
            mismatch(record, actual)
        cursor++
        pump(direction)
    }

    override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
        val key = service to characteristic
        val queue = Channel<Delivery>(Channel.UNLIMITED)
        listeners.getOrPut(key) { mutableListOf() }.add(queue)
        pending.remove(key)?.forEach { queue.trySend(it) }
        if (ended) queue.close()
        try {
            for (event in queue) {
                event.error?.let { throw it }
                emit(event.bytes!!.copyOf())
            }
        } finally {
            listeners[key]?.remove(queue)
            if (listeners[key].isNullOrEmpty()) listeners.remove(key)
            queue.cancel()
        }
    }

    fun assertExhausted() {
        pump()
        if (parsed.stopped) throw AssertionError("录包带有 capture-stopped 标记，不能作为完整回放通过")
        parsed.records.getOrNull(cursor)?.let { mismatch(it, "录包执行结束") }
        if (pending.isNotEmpty()) throw AssertionError("录包仍有未订阅的通知通道：${pending.keys}")
    }

    fun close() {
        ended = true
        listeners.values.flatten().forEach { it.close() }
    }

    private fun next(actual: String): CaptureRecord = parsed.records.getOrNull(cursor)
        ?: throw AssertionError("录包第 ${(parsed.records.lastOrNull()?.line ?: 1) + 1} 行：期望 EOF，实际 $actual")
    private fun mismatch(expected: CaptureRecord, actual: String): Nothing =
        throw AssertionError("录包第 ${expected.line} 行：期望 ${expected.describe()}，实际 $actual")

    private fun deliver(record: CaptureRecord, event: Delivery) {
        val key = record.service to record.characteristic
        val active = listeners[key].orEmpty()
        if (active.isEmpty()) pending.getOrPut(key) { mutableListOf() }.add(event)
        else active.forEach { it.trySend(event) }
    }

    private fun pump(write: Char? = null) {
        while (cursor < parsed.records.size) {
            val record = parsed.records[cursor]
            when (record.kind) {
                'M' -> {
                    if (record.mode != 'w') lengths[true] = record.limit
                    if (record.mode != 'W') lengths[false] = record.limit
                }
                'N' -> deliver(record, Delivery(bytes = record.bytes))
                'D' -> {
                    disconnected = true
                    disconnectEvents.tryEmit(DeviceException(ErrorCode.Disconnected))
                    close()
                }
                'E' -> when (val operation = errorOperation(record)) {
                    'R', 'M' -> return
                    'N' -> deliver(record, Delivery(error = recordedError(record)))
                    else -> {
                        if (operation != write) mismatch(record, "没有对应失败写入")
                        cursor++; throw recordedError(record)
                    }
                }
                else -> {
                    if (disconnected) mismatch(record, "连接已经断开")
                    return
                }
            }
            cursor++
        }
        close()
    }
    private fun errorOperation(record: CaptureRecord) = record.bytes.decodeToString()[0]
    private fun recordedError(record: CaptureRecord): Throwable {
        val parts = record.bytes.decodeToString().split('|', limit = 3)
        val message = "录包第 ${record.line} 行：${parts[2]}"
        if (parts[1] == "Cancelled") return CancellationException(message)
        val code = ErrorCode.entries.firstOrNull { it.name == parts[1] } ?: ErrorCode.ProtocolError
        return DeviceException(code, message)
    }
}
