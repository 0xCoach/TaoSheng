package app.resound.devices.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** All sockets are bound to the user-approved device network; no process-wide network binding. */
internal class AndroidLocalFileNetwork(
    private val context: Context,
    private val parent: CoroutineScope,
    private val detach: suspend () -> Unit,
    private val readPin: () -> String?,
    private val savePin: (String?) -> Unit,
    private val publish: (LocalFileNetworkStatus) -> Unit,
) : LocalFileNetworkPort {
    private val state = MutableStateFlow(LocalFileNetworkStatus(LocalFileNetworkPhase.Idle))
    override val status = state.asStateFlow()
    private fun show(phase: LocalFileNetworkPhase, ssid: String? = state.value.ssid, failure: LocalFileNetworkFailure? = null, diagnostic: String? = null) {
        val value = LocalFileNetworkStatus(phase,ssid,failure,diagnostic);state.value = value;publish(value)
        android.util.Log.i("ResoundDeviceNetwork","phase=$phase failure=$failure")
    }
    override fun clearPeerTrust() = savePin(null)
    override suspend fun detachBluetooth() {
        requirePermission()
        show(LocalFileNetworkPhase.OpeningHotspot)
        detach()
    }
    private fun requirePermission() {
        val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context,needed) != PackageManager.PERMISSION_GRANTED) {
            show(LocalFileNetworkPhase.Failed,failure = LocalFileNetworkFailure.PermissionDenied)
            throw DeviceException(ErrorCode.PermissionDenied,"nearby Wi-Fi permission required")
        }
    }
    override suspend fun open(config: LocalFileNetworkConfig): LocalFileNetworkChannel {
        var created: LocalFileNetworkChannel? = null
        try { return withContext(Dispatchers.IO) { openConnected(config).also { created = it } } }
        catch (e: CancellationException) {
            withContext(NonCancellable) { created?.close() }
            throw e
        }
    }
    private suspend fun openConnected(config: LocalFileNetworkConfig): LocalFileNetworkChannel {
        requirePermission()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = CompletableDeferred<Network>()
        val input = Channel<ByteArray>(64)
        var socket: WebSocket? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) { network.complete(value) }
            override fun onUnavailable() { network.completeExceptionally(DeviceException(ErrorCode.Disconnected,"device Wi-Fi unavailable")) }
            override fun onLost(value: Network) {
                network.completeExceptionally(DeviceException(ErrorCode.Disconnected,"device Wi-Fi disconnected"))
                input.close(DeviceException(ErrorCode.Disconnected,"device Wi-Fi disconnected"));socket?.cancel()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(config.ssid).setWpa2Passphrase(config.password).build()).build()
        show(LocalFileNetworkPhase.Joining,config.ssid)
        var client: OkHttpClient? = null
        var udp: DatagramSocket? = null
        var heartbeat: Job? = null
        var ownedJob: Job? = null
        val wire = MutableStateFlow("before-WebSocket")
        fun types(error: Throwable) = generateSequence(error) { it.cause }.take(8)
            .map { it.javaClass.simpleName }.distinct().joinToString(" > ")
        try {
            manager.requestNetwork(request,callback,60_000)
            val joined = withTimeout(65_000) { network.await() }
            show(LocalFileNetworkPhase.Connecting,config.ssid)
            // Trust is scoped to this random-password SoftAP and exact private endpoint. After first
            // authenticated join, pin the device leaf certificate; a changed certificate fails closed.
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("missing device certificate")
                    verifyDeviceFilePeer(certificate,config,readPin) { savePin(it) }
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
            client = OkHttpClient.Builder().socketFactory(joined.socketFactory).proxy(Proxy.NO_PROXY)
                .addInterceptor(deviceFileWebSocketProfile(config))
                .sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier { host,_ -> host == config.ipv4 }
                .followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS)
                .readTimeout(0,TimeUnit.MILLISECONDS).build()
            socket = connectDeviceFileWebSocket(requireNotNull(client),Request.Builder().url("wss://${config.ipv4}:${config.port}").build(),input,config) { wire.value=it }
            wire.value = "WebSocket-ready; starting-UDP"
            udp = DatagramSocket(null).apply { bind(java.net.InetSocketAddress(0));joined.bindSocket(this) }
            val datagram = requireNotNull(udp)
            val target = InetAddress.getByName(config.ipv4)
            val job = SupervisorJob(parent.coroutineContext[Job])
            ownedJob = job
            val ownedScope = CoroutineScope(parent.coroutineContext+job+Dispatchers.IO)
            heartbeat = ownedScope.launch {
                try {
                    while (isActive) { datagram.send(DatagramPacket(config.udpPayload,config.udpPayload.size,target,config.udpPort));delay(2_000) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { input.close(DeviceException(ErrorCode.Disconnected,"device keepalive failed",e)) }
            }
            show(LocalFileNetworkPhase.Transferring,config.ssid)
            val activeSocket = requireNotNull(socket);val activeClient = requireNotNull(client);val activeHeartbeat = requireNotNull(heartbeat)
            return object : LocalFileNetworkChannel {
                private val closeGate = Mutex()
                private var closed = false
                override val incoming = input.receiveAsFlow().catch { error ->
                    if(error !is CancellationException) show(LocalFileNetworkPhase.Failed,config.ssid,LocalFileNetworkFailure.NetworkLost,"Transfer: ${wire.value}; ${types(error)}")
                    throw error
                }
                override suspend fun sendText(value: String) { check(activeSocket.send(value)) { "device WebSocket closed" } }
                override suspend fun close() = withContext(NonCancellable) { closeGate.withLock {
                    if (closed) return@withLock
                    closed = true
                    try {
                        activeHeartbeat.cancelAndJoin();job.cancelAndJoin();datagram.close()
                        // Let the driver's queued FINISH reach the device before cancelling the socket.
                        withTimeoutOrNull(750) { while(activeSocket.queueSize()>0)delay(20) }
                        activeSocket.close(1000,"finished");delay(100);activeSocket.cancel();input.cancel()
                    } finally {
                        runCatching { manager.unregisterNetworkCallback(callback) }
                        activeClient.connectionPool.evictAll();activeClient.dispatcher.executorService.shutdown()
                        if(state.value.phase != LocalFileNetworkPhase.Failed) show(LocalFileNetworkPhase.Finished,config.ssid)
                    }
                } }
            }
        } catch (e: Exception) {
            val reason = when {
                e is TimeoutCancellationException && state.value.phase == LocalFileNetworkPhase.Joining -> LocalFileNetworkFailure.JoinTimeout
                e is TimeoutCancellationException -> LocalFileNetworkFailure.SocketTimeout
                e is CancellationException -> LocalFileNetworkFailure.Cancelled
                e is DeviceException && e.detail == "device Wi-Fi unavailable" -> LocalFileNetworkFailure.JoinUnavailable
                e is DeviceException && e.detail == "device Wi-Fi disconnected" -> LocalFileNetworkFailure.NetworkLost
                generateSequence<Throwable>(e) { it.cause }.take(8).any { it is SSLException || it is java.security.cert.CertificateException } -> LocalFileNetworkFailure.TlsPeer
                else -> LocalFileNetworkFailure.SocketFailed
            }
            withContext(NonCancellable) {
                heartbeat?.cancelAndJoin();ownedJob?.cancelAndJoin();udp?.close();socket?.cancel();input.cancel()
                runCatching { manager.unregisterNetworkCallback(callback) }
                client?.connectionPool?.evictAll();client?.dispatcher?.executorService?.shutdown()
                show(LocalFileNetworkPhase.Failed,config.ssid,reason,"${state.value.phase}: ${wire.value}; ${types(e)}")
            }
            throw e
        }
    }
}
