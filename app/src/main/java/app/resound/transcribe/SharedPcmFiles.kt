package app.resound.transcribe

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 只缓存文件路径，不持有整段数组。由流水线在声纹完成/失败/取消后释放。 */
class SharedPcmFiles(private val cacheDir: File, private val decode: suspend (File, File) -> Unit) {
    private val files = ConcurrentHashMap<String, File>()
    fun find(source: File): File? = files[source.absolutePath]?.takeIf { it.isFile }

    suspend fun prepare(source: File): ShortArray {
        val pcm = find(source) ?: File.createTempFile("asr-shared-", ".pcm", cacheDir).also { target ->
            try { decode(source, target); files[source.absolutePath] = target }
            catch (e: Throwable) { target.delete(); throw e }
        }
        return readPcm16(pcm)
    }

    suspend fun read(source: File, fallback: suspend (File) -> ShortArray): ShortArray =
        find(source)?.let(::readPcm16) ?: fallback(source)

    fun release(source: File) { files.remove(source.absolutePath)?.delete() }
    fun clear() { files.keys.toList().forEach { release(File(it)) } }
}

/** 16k 单声道小端 PCM；一次只保留最终 ShortArray 和 8KB 缓冲。 */
internal fun readPcm16(file: File): ShortArray {
    require(file.length() > 0 && file.length() % 2 == 0L && file.length() / 2 <= Int.MAX_VALUE)
    val result = ShortArray((file.length() / 2).toInt())
    file.inputStream().buffered().use { input ->
        for (i in result.indices) {
            val lo = input.read(); val hi = input.read()
            check(lo >= 0 && hi >= 0)
            result[i] = (lo or (hi shl 8)).toShort()
        }
    }
    return result
}
