/*
 * [INPUT]: SDK 串行文件字节与文件清单，AndroidAudioImporter。
 * [OUTPUT]: 可续传的私有暂存文件、幂等待确认导入及两种卡内文件名的时间解析。
 * [POS]: 只接收完整文件；不删除设备原件，不把远端文件名当路径。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.content.Context
import app.resound.importer.AndroidAudioImporter
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun recorderFileKey(device: String, file: RecorderFile): String =
    MessageDigest.getInstance("SHA-256").digest((device + "\u0000" + file.name + "\u0000" + file.size).toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun recorderStart(name: String): Long? = runCatching {
    val stamp = Regex("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}|\\d{8}-\\d{6})").find(name)?.value ?: return null
    val pattern = if ('_' in stamp) "uuuu-MM-dd_HH-mm-ss" else "uuuuMMdd-HHmmss"
    LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern(pattern).withResolverStyle(java.time.format.ResolverStyle.STRICT))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

internal class RecorderTransfer(context: Context, private val importer: AndroidAudioImporter) {
    private val root = File(context.filesDir, "sonicloud-downloads").apply { mkdirs() }
    private var output: RandomAccessFile? = null
    private var raw: File? = null
    var current: RecorderFile? = null
        private set
    private var key = ""
    val offset: Long get() = output?.length() ?: 0
    fun received(device: String, file: RecorderFile) = importer.receivedRecorder(recorderFileKey(device, file))
    fun begin(device: String, file: RecorderFile): Int {
        require(file.name.isNotBlank() && file.name.length <= 255 && file.size in 1..Int.MAX_VALUE.toLong()) { "录音卡文件信息无效" }
        close()
        key = recorderFileKey(device, file)
        raw = File(root, "${key}.part")
        output = RandomAccessFile(raw!!, "rw")
        if (output!!.length() > file.size) output!!.setLength(0)
        output!!.seek(output!!.length())
        current = file
        return output!!.length().toInt()
    }
    fun append(bytes: ByteArray) {
        val file = current ?: return
        val out = output ?: return
        check(out.length() + bytes.size <= file.size) { "收到的音频超过文件声明长度，请重新下载" }
        out.write(bytes)
    }
    fun checkpoint() { output?.fd?.sync() }
    suspend fun complete() {
        val file = checkNotNull(current)
        val source = checkNotNull(raw)
        checkpoint()
        check(source.length() == file.size) { "文件未完整下载，请重试续传" }
        output?.close(); output = null
        val converted = File(root, "${key}.wav")
        try {
            val standard = source.inputStream().use { input ->
                val header = ByteArray(12); val n = input.read(header)
                n >= 12 && ((String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE") ||
                    String(header, 0, 4, Charsets.US_ASCII) == "OggS")
            }
            val audio = if (standard) source else { recorderWav(source, converted); converted }
            importer.receiveRecorder(audio, key, file.name, recorderStart(file.name))
            source.delete()
        } finally { converted.delete() }
        current = null
    }
    fun close() {
        try { checkpoint() } finally { output?.close(); output = null; current = null }
    }
}
