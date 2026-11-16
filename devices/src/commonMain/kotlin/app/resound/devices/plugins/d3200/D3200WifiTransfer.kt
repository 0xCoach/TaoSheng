package app.resound.devices.plugins.d3200

// Wi-Fi protocol research: tacshi/Soundcore (MIT); indirect AllDayRec sources are
// disclosed in vendor/d3200/THIRD_PARTY_NOTICES.md and assets/licenses/d3200/.

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Same BLE ECDH and file crypto survive a deliberate, core-owned BLE -> local-network handoff. */
internal class D3200WifiTransfer(
    private val session: DriverSession,
    private val crypto: D3200Crypto,
    private val ask: suspend (ByteArray,Int) -> ByteArray,
    private val stopBluetoothReaders: suspend () -> Unit,
) {
    private val port get() = (session.extras as? LocalFileNetworkExtras)?.fileNetwork
    private var channel: LocalFileNetworkChannel? = null
    var handedOff = false
        private set
    val enabled get() = port != null && session.store.getString("fastTransfer") == "true" &&
        session.clock.nowEpochMillis() >= (session.store.getString("fastTransferRetryAt")?.toLongOrNull() ?: 0)
    suspend fun read(file: RecordingFile, fromOffset: Long, emitBytes: suspend (ByteArray) -> Unit) {
        try {
            val network = channel ?: run {
                val local = port ?: throw DeviceException(ErrorCode.Unsupported)
                val random = session.toolbox.crypto.randomBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
                val ssid = "TaoSheng-${random.take(12)}"; val password = random.takeLast(32)
                val reply = ask(D3200Protocol.wifiConfig(ssid,password),0x1a05)
                val endpoint = D3200Protocol.wifiEndpoint(reply)
                session.store.putString("wifiPendingClose","true")
                stopBluetoothReaders()
                handedOff = true
                local.detachBluetooth()
                delay(10_000)
                local.open(LocalFileNetworkConfig(ssid,password,endpoint.first,endpoint.second,32003,
                    "soundcore-keep-alive-unicast".encodeToByteArray(),webSocketUserAgent = "okhttp/3.12.13.18",
                    requestCompression = false,webSocketAttempts = 3,allowExpiredPeerCertificate = true)).also { channel = it }
            }
            coroutineScope {
                val incoming = Channel<ByteArray>(128)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    val decoder = BeanLight.Decoder()
                    try { network.incoming.collect { bytes -> decoder.push(bytes).forEach { incoming.send(it) } } }
                    finally { incoming.close() }
                }
                try {
                    network.sendText(D3200Protocol.wifiRequest(file.id.toLong(),D3200HistoryCore.resumeAt(fromOffset/160)))
                    val header = withTimeout(20_000) {
                        var frame = incoming.receive()
                        while (D3200Protocol.command(frame) !in setOf(0x1a07,0x1b07)) frame = incoming.receive()
                        frame
                    }
                    run {
                        val payload = D3200Protocol.payload(header)
                        crypto.fileHeader(payload,file.id.toLong())
                        val total = D3200HistoryCore.packets(D3200Protocol.u32(payload,4))
                        val cursor = D3200DownloadCursor(fromOffset,total)
                        val batch = ByteArray(8_000);var used = 0
                        while (!cursor.complete) {
                            val frame = withTimeout(45_000) { incoming.receive() }
                            when (D3200Protocol.command(frame)) {
                                0x1a08,0x1a12,0x1b08,0x1b12 -> for (slice in D3200Protocol.slices(D3200Protocol.payload(frame))) {
                                    cursor.accept(slice.seq,crypto.decrypt(slice))?.let { packet ->
                                        packet.copyInto(batch,used);used += packet.size
                                        if (used == batch.size) { emitBytes(batch.copyOf());used = 0 }
                                    }
                                }
                                0x1a0a,0x1b0a -> if (!cursor.complete) throw DeviceException(ErrorCode.Disconnected,"incomplete Wi-Fi transfer")
                            }
                        }
                        if (used > 0) emitBytes(batch.copyOf(used))
                    }
                } finally { collector.cancelAndJoin();incoming.cancel() }
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            session.store.putString("fastTransferRetryAt",(session.clock.nowEpochMillis()+300_000).toString())
            throw DeviceException(ErrorCode.Disconnected,"fast transfer timeout; retry on Bluetooth",e)
        }
        catch (e: Exception) {
            session.store.putString("fastTransferRetryAt",(session.clock.nowEpochMillis()+300_000).toString())
            throw DeviceException(ErrorCode.Disconnected,"fast transfer failed; retry on Bluetooth",e)
        }
    }
    suspend fun close() {
        val network = channel;channel = null
        try { runCatching { withTimeout(1500) { network?.sendText("FINISH") } } }
        finally { network?.close() }
    }
}
