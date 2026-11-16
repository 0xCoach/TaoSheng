package app.resound.transcribe.local

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.security.MessageDigest

/** 服务进程写进度；主进程只在轮次开始清理。状态文件仅由主进程写。 */
internal class LocalResumeFiles(private val directory: File, private val note: (String) -> Unit) {
    private fun file(segment: String, suffix: String) = File(directory, "${hash(segment.toByteArray())}.$suffix")
    fun load(segment: String): LocalResumeProgress? = read(file(segment, "progress"))?.let {
        try { LocalResumeCodec.decode(it) }
        catch (_: Exception) { note("progress-corrupt"); deleteProgress(segment); null }
    }
    fun restore(key: LocalResumeKey): LocalResumeProgress? {
        val previous = load(key.segment) ?: return null
        if (previous.matches(key)) return previous
        note("progress-invalidated"); deleteProgress(key.segment); return null
    }
    suspend fun prepare(key: LocalResumeKey, create: suspend () -> LocalResumeProgress): LocalResumeProgress =
        restore(key) ?: create().also { save(it) }
    fun hasDeferred(): Boolean = directory.listFiles()?.filter { it.extension == "state" }?.any {
        read(it)?.let { text -> runCatching { LocalInterruptionCodec.decode(text).count >= 2 }.getOrDefault(false) } == true
    } == true
    fun save(value: LocalResumeProgress) = write(file(value.key.segment, "progress"), LocalResumeCodec.encode(value))
    fun deleteProgress(segment: String) { file(segment, "progress").delete(); File(file(segment, "progress").path + ".tmp").delete() }
    fun state(segment: String): LocalInterruptionState = read(file(segment, "state"))?.let {
        try { LocalInterruptionCodec.decode(it) } catch (_: Exception) { note("state-corrupt"); null }
    } ?: LocalInterruptionState()
    fun state(segment: String, value: LocalInterruptionState) = write(file(segment, "state"), LocalInterruptionCodec.encode(value))
    fun clear(segment: String) { deleteProgress(segment); file(segment, "state").delete() }
    fun sweep(live: Set<String>, now: Long) {
        val names = live.map { hash(it.toByteArray()) }.toSet()
        directory.listFiles()?.forEach { f ->
            if (f.name.substringBefore('.') !in names || now - f.lastModified() > 3 * 86_400_000L) f.delete()
        }
    }
    private fun read(file: File): String? {
        if (!file.exists()) return null
        return try { file.bufferedReader().use { reader ->
            val checksum = reader.readLine()
            val text = reader.readText()
            check(hash(text.toByteArray()) == checksum)
            text
        } } catch (_: Exception) { note("progress-read-failed"); file.delete(); null }
    }
    private fun write(file: File, text: String) {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(file.path + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write((hash(text.toByteArray()) + "\n" + text).toByteArray()); stream.fd.sync()
            }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object {
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun pcmHash(pcm: ShortArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            pcm.forEach { digest.update(it.toByte()); digest.update((it.toInt() shr 8).toByte()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
