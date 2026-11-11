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
) : LinkedDevice {
    val address: String = device.address
    private val transport = D3200Transport(context, device)
    val crypto = D3200Crypto()
    private val light = BeanLightState()
    private val incoming = Channel<ByteArray>(1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    @Volatile private var prepared = false
    @Volatile private var firmware = ""
    private val generation = settings.request(address)
    var recording = 0; private set
    override val mtu = 517
    override val backfillBeforeLive = true
    @Volatile private var lost: Long? = null
    override val disconnectedAt get() = lost
    private fun note(value: String) {
        android.util.Log.i("D3200", value)
        settings.report(address) { it.copy(sync = value) }
    }
    private suspend fun initialize() {
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
                    } else if (!incoming.trySend(frame).isSuccess) throw IOException("设备数据队列溢出，需要重连")
                }
            } catch (e: Exception) {
                lost = System.currentTimeMillis(); incoming.close(e)
            }
        }
        val info = ask(D3200Protocol.frame(1, 1), 0x0101)
        require(info.size >= 51) { "录音豆设备信息不完整" }
        recording = info[50].toInt()
        firmware = String(info, 3, 5, Charsets.US_ASCII).trimEnd('\u0000')
        settings.report(address) { it.copy(firmware = firmware) }
        crypto.handshake(ask(D3200Protocol.frame(0x2e, 1, crypto.publicKey()), 0x2e01))
        if (settings.mode(address) != "none") {
            require(firmware.startsWith("04.92")) { "当前固件未验证灯控，请先停止控灯" }
            ask(BeanLight.enableSpp(), 0x1a0f)
            transport.openSpp(requireNotNull(settings.classic(address)) { "未取得灯控地址，请重新搜索设备" })
            prepared = true
        }
        ready = true
    }
    @Volatile private var ready = false
    suspend fun receive(timeout: Long = 100): ByteArray? = withTimeoutOrNull(timeout) { incoming.receive() }
    suspend fun ask(frame: ByteArray, expected: Int, file: Long? = null): ByteArray = commands.withLock {
        transport.write(frame)
        withTimeout(8000) {
            while (true) {
                val response = incoming.receive()
                if (D3200Protocol.command(response) != expected) continue
                val payload = D3200Protocol.payload(response)
                if (file != null && !D3200Protocol.headerMatches(payload, file)) continue
                require(response[4].toInt() and 15 == 1) { "录音豆拒绝命令 ${expected.toString(16)}" }
                return@withTimeout payload
            }
            @Suppress("UNREACHABLE_CODE") byteArrayOf()
        }
    }
    suspend fun listCurrent() = ask(D3200Protocol.frame(0x1a, 0x0e, byteArrayOf(0, 0)), 0x1a0e)
    suspend fun refreshRecording(): Int {
        val info = ask(D3200Protocol.frame(1, 1), 0x0101)
        require(info.size >= 51); recording = info[50].toInt(); return recording
    }
    suspend fun startRecording() { ask(D3200Protocol.frame(0x18, 0x82, byteArrayOf(1)), 0x1882); recording = 1 }
    fun requestLive(file: Long, offset: Long) = transport.write(D3200Protocol.request(file, offset))
    override suspend fun read(service: String, characteristic: String): ByteArray = error("该设备使用私有命令协议")
    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        require(characteristic.equals(D3200Protocol.WRITE, true)); transport.write(bytes)
    }
    override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
        require(characteristic.equals(D3200Protocol.NOTIFY, true)); for (frame in incoming) emit(frame)
    }
    override suspend fun close() {
        if (lost == null) lost = System.currentTimeMillis()
        scope.cancel(); transport.close(); incoming.cancel()
        settings.report(address) { it.copy(light = "会话已断开；灯光偏好保留") }
    }
    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice, settings: D3200Coordinator): D3200Connection {
            val link = D3200Connection(context, device, settings)
            try { withContext(Dispatchers.IO) { link.initialize() }; return link }
            catch (e: Exception) { link.close(); throw e }
        }
    }
}
