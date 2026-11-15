package app.resound.devices

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

internal fun deviceSourceKey(device: String, file: DeviceFile): String =
    "source-${file.format.name}-${deviceTransferKey(device, file, 0, file.durationMillis ?: 0)}"

/** Platform file adapter. A completed source is reusable only with a full-file SHA-256 receipt. */
internal class DeviceSourceCache(root: File, key: String) {
    val file = File(root, key)
    private val receipt = AtomicFile(File(root, "$key.sha256"))
    fun hasReceipt() = receipt.baseFile.exists() || File(receipt.baseFile.path + ".bak").exists()
    fun verified(): Boolean {
        if (!file.isFile) return false
        val expected = runCatching { receipt.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return false
        return expected.matches(Regex("[a-f0-9]{64}")) && expected == sha256(file)
    }
    fun commit(): String {
        val hash = sha256(file)
        val output = receipt.startWrite()
        try { output.write(hash.toByteArray()); receipt.finishWrite(output) }
        catch (e: Exception) { receipt.failWrite(output); throw e }
        return hash
    }
    fun invalidate() { receipt.delete() }
    fun discard() { file.delete(); receipt.delete() }
    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
