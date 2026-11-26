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
            val network = channel ?: connect()
            coroutineScope {
                val frames = Channel<ByteArray>(128)
                val receive = launch(start = CoroutineStart.UNDISPATCHED) {
                    val decoder = BeanLight.Decoder()
                    try {
                        network.incoming.collect { bytes ->
                            for (frame in decoder.push(bytes)) frames.send(frame)
                        }
                    } finally {
                        frames.close()
                    }
                }
                try {
                    val id = file.id.toLong()
                    val firstPacket = D3200HistoryCore.resumeAt(fromOffset / D3200Protocol.AUDIO_PACKET_BYTES)
                    network.sendText(D3200Protocol.wifiRequest(id, firstPacket))
                    val header = withTimeout(20_000) {
                        frames.receiveAsFlow().first {
                            val command = D3200Protocol.command(it)
                            command == 0x1a07 || command == 0x1b07
                        }
                    }
                    val download = D3200FileDownload(D3200Protocol.payload(header), id, fromOffset, crypto, emitBytes)
                    while (!download.complete) {
                        val frame = withTimeout(45_000) { frames.receive() }
                        when (D3200Protocol.command(frame)) {
                            0x1a08, 0x1a12, 0x1b08, 0x1b12 -> download.audio(frame)
                            0x1a0a, 0x1b0a -> throw DeviceException(ErrorCode.Disconnected, "incomplete Wi-Fi transfer")
                        }
                    }
                    download.finishChunk()
                } finally {
                    receive.cancelAndJoin()
                    frames.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            throw deferRetry("fast transfer timeout; retry on Bluetooth", e)
        } catch (e: Exception) {
            throw deferRetry("fast transfer failed; retry on Bluetooth", e)
        }
    }

    private suspend fun connect(): LocalFileNetworkChannel {
        val local = port ?: throw DeviceException(ErrorCode.Unsupported)
        val credentials = session.toolbox.crypto.randomBytes(24).toHexString()
        val ssid = "TaoSheng-${credentials.take(12)}"
        val password = credentials.takeLast(32)
        val (ip, port) = D3200Protocol.wifiEndpoint(ask(D3200Protocol.wifiConfig(ssid, password), 0x1a05))
        session.store.putString("wifiPendingClose", "true")
        stopBluetoothReaders()
        handedOff = true
        local.detachBluetooth()
        delay(10_000)
        val config = LocalFileNetworkConfig(ssid, password, ip, port, 32003,
            "soundcore-keep-alive-unicast".encodeToByteArray(), webSocketUserAgent = "okhttp/3.12.13.18",
            requestCompression = false, webSocketAttempts = 3, allowExpiredPeerCertificate = true)
        return local.open(config).also { channel = it }
    }

    private fun deferRetry(message: String, cause: Exception): DeviceException {
        session.store.putString("fastTransferRetryAt", (session.clock.nowEpochMillis() + 300_000).toString())
        return DeviceException(ErrorCode.Disconnected, message, cause)
    }

    suspend fun close() {
        val network = channel;channel = null
        try { runCatching { withTimeout(1500) { network?.sendText("FINISH") } } }
        finally { network?.close() }
    }
}
