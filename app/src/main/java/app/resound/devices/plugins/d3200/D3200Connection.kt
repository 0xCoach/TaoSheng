package app.resound.devices.plugins.d3200

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import app.resound.devices.LinkedDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Owns exactly one BLE/SPP pair; a dedicated reader keeps QC receipts alive even during file ingestion. */
internal class D3200Connection private constructor(
    context: Context, private val device: BluetoothDevice, val settings: D3200Coordinator,
    private val release: (D3200Connection) -> Unit,
) : LinkedDevice {
    val address: String = device.address
    private val transport = D3200Transport(context, device)
    val crypto = D3200Crypto()
    private val light = BeanLightState()
    private val incoming = Channel<ByteArray>(1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val operations = Mutex()
    private val replies = D3200Replies()
    @Volatile private var info = D3200DeviceInfo()
    @Volatile private var closed = false
    @Volatile var controlling = false; private set
    @Volatile private var streaming = false
    @Volatile private var batteryObserver: ((Int) -> Unit)? = null
    @Volatile private var prepared = false
    @Volatile private var firmware = ""
    private val generation = settings.request(address)
    @Volatile var recording = 0; private set
    override val mtu = 517
    override val backfillBeforeLive = true
    @Volatile private var lost: Long? = null
    override val disconnectedAt get() = lost
    fun battery(observer: (Int) -> Unit) {
        batteryObserver = observer; info.battery?.let(observer)
    }
    @Synchronized private fun status(command: Int, payload: ByteArray) {
        if (command == 0x1882 && controlling) return // control echoes are confirmed by a separate device-info readback
        val next = if (command == 0x0101) D3200DeviceInfo.parse(payload) else D3200DeviceInfo.pushed(command, payload, info)
        if (command != 0x0101 && next == info) return
        info = next; next.recording?.let { recording = it }
        firmware = next.firmware
        settings.deviceInfo(address, next)
        next.battery?.let { batteryObserver?.invoke(it) }
    }
    private suspend fun initialize() {
        settings.report(address) { it.copy(connecting = true, connected = false, receiving = false, backfilling = false, error = null) }
        transport.connect()
        scope.launch {
            try {
                while (isActive) {
                    if (settings.request(address) != generation) throw IOException("已请求重新检查离线录音")
                    val now = SystemClock.elapsedRealtime()
                    if (prepared) {
                        light.select(BeanLight.Preference(settings.mode(address), settings.mode(address)))
                        if (light.expired(now)) throw IOException("灯控回执超时，正在重连")
                        light.outgoing(now)?.let(transport::writeSpp)
                        val state = light.snapshot()
                        settings.report(address) { it.copy(light = when (state["state"]) {
                            "confirmed" -> "设备已回执：${state["confirmed_mode"]}"
                            "stopped" -> "停止控灯；保留共用音频通道"
                            else -> "灯控：${state["state"]}"
                        }) }
                    } else if (firmware.startsWith("04.92") && settings.mode(address) != "none") {
                        // initialize() prepares the first channel; later mode changes need an orderly reconnect.
                        if (firmware.isNotEmpty() && ready) throw IOException("正在准备灯控共用通道")
                    }
                    val frame = transport.receive(100) ?: continue
                    if (BeanLight.qc(frame)) {
                        if (light.acknowledge(frame, now, System.currentTimeMillis()) == false) throw IOException("设备拒绝灯控命令")
                    } else {
                        val command = D3200Protocol.command(frame)
                        if (frame[4].toInt() and 15 == 1 || command in setOf(0x0103, 0x0104, 0x1a06))
                            status(command, D3200Protocol.payload(frame))
                        if (!replies.dispatch(frame) && command !in setOf(0x0101, 0x0103, 0x0104, 0x1882)) {
                            if (!incoming.trySend(frame).isSuccess) throw IOException("设备数据队列溢出，需要重连")
                        }
                    }
                }
            } catch (e: Exception) {
                lost = System.currentTimeMillis(); incoming.close(e); replies.fail(e)
                settings.report(address) { it.copy(connected = false, connecting = false, receiving = false, error = e.message) }
            }
        }
        val info = ask(D3200Protocol.frame(1, 1), 0x0101)
        require(info.size >= 51) { "录音豆设备信息不完整" }
        require(D3200DeviceInfo.parse(info).recording != null) { "录音豆录音状态未知" }
        crypto.handshake(ask(D3200Protocol.frame(0x2e, 1, crypto.publicKey()), 0x2e01))
        if (settings.mode(address) != "none") {
            require(firmware.startsWith("04.92")) { "当前固件未验证灯控，请先停止控灯" }
            ask(BeanLight.enableSpp(), 0x1a0f)
            transport.openSpp(requireNotNull(settings.classic(address)) { "未取得灯控地址，请重新搜索设备" })
            prepared = true
        }
        ready = true
        settings.report(address) { it.copy(connected = true, connecting = false) }
        scope.launch {
            while (isActive) {
                delay(30_000)
                // Never insert status queries into an offline file download or an interactive control operation.
                if (!operations.tryLock()) continue
                try { refreshRecording() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { settings.report(address) { it.copy(error = e.message) } }
                finally { operations.unlock() }
            }
        }
    }
    @Volatile private var ready = false
    suspend fun receive(timeout: Long = 100): ByteArray? = withTimeoutOrNull(timeout) { incoming.receive() }
    suspend fun ask(frame: ByteArray, expected: Int, file: Long? = null): ByteArray = commands.withLock {
        check(!closed && lost == null) { "设备连接已断开" }
        // A matching file header is a transfer boundary: discard only the previous transfer's queued tail.
        val pending = replies.begin(expected, file) { if (file != null) discardTail() }
        try { transport.write(frame); withTimeout(8000) { pending.reply.await() } }
        catch (e: TimeoutCancellationException) { close(); throw java.io.IOException("设备回执超时，需重新连接", e) }
        finally { replies.clear(pending) }
    }
    suspend fun listCurrent() = ask(D3200Protocol.frame(0x1a, 0x0e, byteArrayOf(0, 0)), 0x1a0e)
    suspend fun refreshRecording(): Int {
        val info = ask(D3200Protocol.frame(1, 1), 0x0101)
        require(info.size >= 51)
        return requireNotNull(D3200DeviceInfo.parse(info).recording) { "录音豆录音状态未知" }
    }
    suspend fun <T> backfill(block: suspend () -> T): T = operations.withLock {
        settings.report(address) { it.copy(backfilling = true, receiving = false) }
        try { block() } finally { settings.report(address) { it.copy(backfilling = false) } }
    }
    suspend fun inspect() = interactive { refreshRecording() }
    private suspend fun <T> interactive(block: suspend () -> T): T {
        check(operations.tryLock()) { "正在补传或执行设备操作，请稍后重试" }
        controlling = true
        try { return block() } finally { controlling = false; operations.unlock() }
    }
    suspend fun control(start: Boolean, automatic: Boolean = false) = interactive {
        if (automatic && !settings.shouldAutoStart(address)) return@interactive
        if (!start) settings.hold(address, true)
        refreshRecording()
        if (!D3200ControlPolicy.confirmed(recording, start)) {
            ask(D3200Protocol.frame(0x18, 0x82, byteArrayOf(if (start) 1 else 2)), 0x1882)
            // ACK alone is not evidence of the resulting recording state.
            var confirmed = false
            repeat(6) {
                if (!confirmed) { delay(250); confirmed = D3200ControlPolicy.confirmed(refreshRecording(), start) }
            }
            check(confirmed) { "设备尚未确认录音状态，请刷新后重试" }
        }
        if (start) settings.hold(address, false)
    }
    suspend fun startRecording() = control(true, automatic = true)
    fun receiving() { if (!streaming) { streaming = true; settings.report(address) { it.copy(receiving = true) } } }
    private fun discardTail() { while (incoming.tryReceive().isSuccess) { } }
    fun requestLive(file: Long, offset: Long) { discardTail(); transport.write(D3200Protocol.request(file, offset)) }
    override suspend fun read(service: String, characteristic: String): ByteArray = error("该设备使用私有命令协议")
    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        require(characteristic.equals(D3200Protocol.WRITE, true)); transport.write(bytes)
    }
    override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
        require(characteristic.equals(D3200Protocol.NOTIFY, true)); for (frame in incoming) emit(frame)
    }
    override suspend fun close() {
        synchronized(this) { if (closed) return; closed = true }
        if (lost == null) lost = System.currentTimeMillis()
        replies.fail(IOException("设备连接已断开"))
        scope.cancel(); transport.close(); incoming.cancel(); batteryObserver = null
        settings.report(address) { it.copy(light = "会话已断开；灯光偏好保留", connected = false,
            connecting = false, receiving = false, backfilling = false) }
        release(this)
    }
    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice, settings: D3200Coordinator,
            release: (D3200Connection) -> Unit): D3200Connection {
            val link = D3200Connection(context, device, settings, release)
            try { withContext(Dispatchers.IO) { link.initialize() }; return link }
            catch (e: Exception) { link.close(); throw e }
        }
    }
}
