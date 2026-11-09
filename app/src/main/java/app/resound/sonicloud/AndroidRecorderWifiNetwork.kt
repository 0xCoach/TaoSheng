/*
 * [INPUT]: Android ConnectivityManager 与录音卡提供的准确 SSID。
 * [OUTPUT]: 系统确认的局域网连接及可恢复的临时进程路由。
 * [POS]: RecorderWifi 的平台边界；保留用户 Wi-Fi 开关，不保存热点配置。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

internal class AndroidRecorderWifiNetwork(context: Context) : RecorderWifiNetwork {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var network: Network? = null
    private var previous: Network? = null
    private var bound = false

    @Synchronized
    override fun request(ssid: String, available: () -> Unit, lost: () -> Unit) {
        check(callback == null)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).build())
            .build()
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                synchronized(this@AndroidRecorderWifiNetwork) {
                    if (callback !== this) return
                    network = value
                    available()
                }
            }
            override fun onLost(value: Network) {
                if (callback === this && network == value) lost()
            }
            override fun onUnavailable() { if (callback === this) lost() }
        }
        callback = listener
        try { manager.requestNetwork(request, listener, 45_000) }
        catch (e: Exception) { callback = null; throw e }
    }

    @Synchronized
    override fun bind() {
        previous = manager.boundNetworkForProcess
        check(manager.bindProcessToNetwork(checkNotNull(network))) { "无法将 TCP 连接路由到录音卡热点" }
        bound = true
    }

    @Synchronized
    override fun restore() {
        if (!bound) return
        if (manager.boundNetworkForProcess == network) {
            check(manager.bindProcessToNetwork(previous) || manager.bindProcessToNetwork(null)) { "恢复应用网络路由失败" }
        }
        bound = false
        previous = null
    }

    @Synchronized
    override fun release() {
        val listener = callback
        callback = null
        try { restore() }
        finally {
            network = null
            if (listener != null) runCatching { manager.unregisterNetworkCallback(listener) }
        }
    }
}
