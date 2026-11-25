package app.resound.digest

import app.resound.net.decodeNetworkHeaders
import app.resound.net.encodeNetworkHeaders
import app.resound.net.validateNetworkHeaders
import app.resound.platform.SecretVault

class SecretHeaders(private val vault: SecretVault, private val group: String) {
    fun headers(id: String): List<HeaderEntry> = try {
        vault.read(group, "headers-$id").takeIf { it.isNotEmpty() }?.let(::decodeNetworkHeaders).orEmpty()
    } catch (_: Exception) {
        throw IllegalStateException("请求头安全读取失败，请重新保存或恢复备份")
    }

    fun save(id: String, headers: List<HeaderEntry>) {
        validateNetworkHeaders(headers)
        if (headers.isEmpty()) vault.remove(group, "headers-$id")
        else vault.write(group, "headers-$id", encodeNetworkHeaders(headers))
    }
}
