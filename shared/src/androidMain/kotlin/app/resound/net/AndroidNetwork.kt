package app.resound.net

import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/** 使用调用方现有客户端，保留代理、TLS、write timeout 与 retryOnConnectionFailure。 */
fun androidNetwork(client: OkHttpClient, sockets: AndroidSocketControl? = null) = networkClient(OkHttp.create {
    preconfigured = client
    if (sockets != null) webSocketFactory = sockets
    config {
        connectionPool(okhttp3.ConnectionPool())
    }
}, NetworkTimeouts(client.connectTimeoutMillis.toLong(), client.readTimeoutMillis.toLong(), client.callTimeoutMillis.toLong()), redirects = client.followRedirects, engineOwnsSocketTimeout = true)
