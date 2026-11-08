package app.resound.voiceprint

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅保存回放副本，不保存识别向量；目录不参与系统及应用备份。 */
class SampleAudioStore(private val dir: File?) {
    private val revision = MutableStateFlow(0L)
    internal val changes = revision.asStateFlow()
    private val size = MutableStateFlow(audioFiles().sumOf { it.length() })
    val bytes = size.asStateFlow()
    private fun audioFiles() = dir?.listFiles()?.filter { it.isFile && it.name != "no-fallback" }.orEmpty()
    private fun changed() { size.value = audioFiles().sumOf { it.length() }; revision.value++ }
    private fun path(name: String): File? {
        require(name.matches(Regex("local-[0-9]+")))
        return dir?.let { File(it, "$name.wav") }
    }
    @Synchronized fun file(name: String): File? = path(name)?.takeIf { it.isFile }
    @Synchronized fun exists(name: String) = file(name) != null
    @Synchronized fun fallbackAllowed() = dir?.let { !File(it, "no-fallback").exists() } ?: true
    @Synchronized fun save(name: String, pcm: ShortArray) {
        val target = path(name) ?: return
        if (pcm.isEmpty()) return
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val tmp = File(target.path + ".tmp")
        try { writeWav(tmp, pcm); check(tmp.renameTo(target)) }
        finally { tmp.delete(); changed() }
    }
    @Synchronized fun delete(name: String) {
        path(name)?.let { check(!it.exists() || it.delete()) }
        // 临时片段也可能来自被删除的样本。
        dir?.let { File(it, "playback.tmp.wav") }?.let { check(!it.exists() || it.delete()) }
        changed()
    }
    @Synchronized fun temp(pcm: ShortArray): File? {
        val d = dir ?: return null
        if (pcm.isEmpty() || !fallbackAllowed()) return null
        check(d.isDirectory || d.mkdirs())
        return File(d, "playback.tmp.wav").also { writeWav(it, pcm); changed() }
    }
    /** 先持久化禁回退标记；失败会返回错误，不能误报已释放空间。 */
    @Synchronized fun clear() {
        val d = dir ?: return
        check(d.isDirectory || d.mkdirs())
        File(d, "no-fallback").writeText("")
        try { audioFiles().forEach { check(it.delete()) } } finally { changed() }
    }
    companion object {
        fun directory(context: Context) = File(context.noBackupFilesDir, "voiceprint-samples")
        internal fun afterRestore(context: Context) = SampleAudioStore(directory(context)).clear()
        private fun writeWav(file: File, pcm: ShortArray) {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
            file.outputStream().use { out ->
                out.write(h.array()); val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in pcm) {
                    if (buffer.remaining() < 2) { out.write(buffer.array(), 0, buffer.position()); buffer.clear() }
                    buffer.putShort(sample)
                }
                out.write(buffer.array(), 0, buffer.position())
            }
        }
    }
}
