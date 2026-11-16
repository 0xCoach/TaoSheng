package app.resound.devices.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Optional extension; existing DriverSession/PlatformExtras contracts and BLE-only drivers are unchanged. */
interface LocalFileNetworkExtras : PlatformExtras { val fileNetwork: LocalFileNetworkPort }
interface LocalFileTransferDriver
/** Optional bounded setup window. Core still enforces the usual timeout after the first data block. */
interface LocalFileTransferConnection { val preparingFileNetwork: Boolean }
/** Optional maintenance mode: revalidation/deletion keep their Bluetooth control connection. */
interface DeviceFileMaintenanceConnection { suspend fun prepareFileMaintenance() }
enum class LocalFileNetworkPhase { Idle, OpeningHotspot, Joining, Connecting, Transferring, Finished, Failed }
enum class LocalFileNetworkFailure { PermissionDenied, JoinUnavailable, JoinTimeout, NetworkLost, TlsPeer, SocketTimeout, SocketFailed, Cancelled }
data class LocalFileNetworkStatus(val phase: LocalFileNetworkPhase, val ssid: String? = null,
                                  val failure: LocalFileNetworkFailure? = null, val diagnostic: String? = null)
data class LocalFileNetworkConfig(val ssid: String, val password: String, val ipv4: String, val port: Int,
                                  val udpPort: Int, val udpPayload: ByteArray,
                                  val webSocketUserAgent: String? = null, val requestCompression: Boolean = true,
                                  val webSocketAttempts: Int = 1, val webSocketRetryMillis: Long = 2000,
                                  val allowExpiredPeerCertificate: Boolean = false) {
    init {
        val parts = ipv4.split('.').map { it.toIntOrNull() }
        require(parts.size == 4 && parts.all { it != null && it in 0..255 })
        require(parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1]?.let { it in 16..31 } == true)
        require(port in 1..65535 && udpPort in 1..65535)
        require(ssid.encodeToByteArray().size in 1..32 && password.length in 8..63)
        require(webSocketUserAgent == null || webSocketUserAgent.length in 1..128 && webSocketUserAgent.all { it in ' '..'~' })
        require(webSocketAttempts in 1..3 && webSocketRetryMillis in 0..5000)
    }
    override fun toString() = "LocalFileNetworkConfig(ssid=$ssid, endpoint=$ipv4:$port)" // Never log passwords.
}
interface LocalFileNetworkPort {
    val status: StateFlow<LocalFileNetworkStatus>
    suspend fun detachBluetooth()
    suspend fun open(config: LocalFileNetworkConfig): LocalFileNetworkChannel
    fun clearPeerTrust()
}
interface LocalFileNetworkChannel {
    val incoming: Flow<ByteArray>
    suspend fun sendText(value: String)
    suspend fun close()
}
