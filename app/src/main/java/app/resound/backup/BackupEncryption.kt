package app.resound.backup

import android.content.Context
import app.resound.digest.SecretStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal interface BackupPassphrases {
    fun has(): Boolean
    fun read(): String
    fun write(value: String?)
}

internal class SecureBackupPassphrases(context: Context) : BackupPassphrases {
    private val store = SecretStore(context, "backup-encryption-secrets", "resound-backup-passphrase-v1")
    override fun has() = store.has("passphrase")
    override fun read() = store.read("passphrase")
    override fun write(value: String?) {
        if (value == null) store.remove("passphrase") else store.write("passphrase", value)
    }
}

/** v1：固定 PBKDF2 参数，16 字节随机盐、12 字节随机 nonce；头部也参与认证。 */
internal object BackupEncryption {
    private val magic = "RESOUND-ENC-1\n".toByteArray(Charsets.US_ASCII)
    private const val iterations = 210_000
    private fun cipher(mode: Int, passphrase: String, header: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase.toCharArray(), header.copyOfRange(magic.size, magic.size + 16), iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(magic.size + 16, header.size)))
                updateAAD(header)
            }
        } finally { key.fill(0) }
    }
    fun isEncrypted(source: File): Boolean = source.inputStream().use {
        val header = ByteArray(magic.size)
        it.read(header) == header.size && header.contentEquals(magic)
    }
    fun encrypt(source: File, target: File, passphrase: String) {
        val random = SecureRandom()
        val header = magic + ByteArray(16).also(random::nextBytes) + ByteArray(12).also(random::nextBytes)
        transform(source, target, passphrase, header)
    }
    private fun transform(source: File, target: File, passphrase: String, encryptHeader: ByteArray? = null) {
        try {
            source.inputStream().buffered().use { input ->
                val header = encryptHeader ?: ByteArray(magic.size + 28).also {
                    java.io.DataInputStream(input).readFully(it)
                    if (!it.copyOfRange(0, magic.size).contentEquals(magic)) throw BackupFailure("加密备份格式不支持或已损坏")
                }
                val cipher = cipher(if (encryptHeader != null) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, passphrase, header)
                target.outputStream().buffered().use { output ->
                    if (encryptHeader != null) output.write(header)
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > 2L * 1024 * 1024 * 1024) throw BackupFailure("备份文件过大")
                        cipher.update(buffer, 0, size)?.let(output::write)
                    }
                    output.write(cipher.doFinal())
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is AEADBadTagException) throw BackupFailure("口令不对")
            throw e
        }
    }
    fun decrypt(source: File, target: File, stored: String?, supplied: String?) {
        val candidates = listOfNotNull(stored, supplied).filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) throw BackupFailure("这份备份加了密，请输入口令")
        for (candidate in candidates) {
            try { transform(source, target, candidate); return }
            catch (e: BackupFailure) { if (e.human != "口令不对") throw e }
        }
        throw BackupFailure("口令不对")
    }
    fun prepare(zip: File, passphrase: String?): File = if (passphrase == null) zip else
        File(zip.path + ".enc").also { encrypt(zip, it, passphrase) }
}
