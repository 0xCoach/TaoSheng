package app.resound.voiceprint

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅保存回放副本，不保存识别向量；目录不参与系统及应用备份。 */
class SampleAudioStore(private val dir: File?) {
    private val invalidations = MutableStateFlow(0L)
    internal val changes = invalidations.asStateFlow()
    private val occupiedBytes = MutableStateFlow(storageBytes())
    val bytes = occupiedBytes.asStateFlow()

    private fun clips(): Sequence<File> = dir?.listFiles().orEmpty().asSequence()
        .filter { entry -> entry.name != FallbackMarker && entry.isFile }

    private fun storageBytes() = clips().sumOf(File::length)

    private fun publishStorage() {
        occupiedBytes.value = storageBytes()
        invalidations.value += 1
    }

    private fun sampleFile(name: String): File? {
        require(SampleName.matches(name))
        val root = dir ?: return null
        return root.resolve("$name.wav")
    }

    @Synchronized fun file(name: String): File? = sampleFile(name)?.takeIf { it.isFile }
    @Synchronized fun exists(name: String): Boolean = file(name) != null
    @Synchronized fun fallbackAllowed(): Boolean = dir?.resolve(FallbackMarker)?.exists() != true

    @Synchronized fun save(name: String, pcm: ShortArray) {
        val destination = sampleFile(name) ?: return
        if (pcm.isEmpty()) return
        val folder = destination.parentFile!!
        check(folder.isDirectory || folder.mkdirs())
        val staging = folder.resolve(destination.name + ".tmp")
        try {
            writeWav(staging, pcm)
            check(staging.renameTo(destination))
        } finally {
            staging.delete()
            publishStorage()
        }
    }

    @Synchronized fun delete(name: String) {
        val savedClip = sampleFile(name)
        val transientClip = dir?.resolve(TempClipName)
        for (entry in listOfNotNull(savedClip, transientClip)) {
            if (entry.exists()) check(entry.delete())
        }
        publishStorage()
    }

    @Synchronized fun temp(pcm: ShortArray): File? {
        val root = dir ?: return null
        if (!fallbackAllowed() || pcm.isEmpty()) return null
        check(root.isDirectory || root.mkdirs())
        val clip = root.resolve(TempClipName)
        writeWav(clip, pcm)
        publishStorage()
        return clip
    }

    /** 恢复或清空后，历史样本只允许播放后续录入时留下的副本。 */
    @Synchronized fun clear() {
        val root = dir ?: return
        check(root.isDirectory || root.mkdirs())
        root.resolve(FallbackMarker).outputStream().close()
        try {
            for (clip in clips()) check(clip.delete())
        } finally {
            publishStorage()
        }
    }

    companion object {
        private const val FallbackMarker = "no-fallback"
        private const val TempClipName = "playback.tmp.wav"
        private val SampleName = Regex("local-[0-9]+")

        fun directory(context: Context) = File(context.noBackupFilesDir, "voiceprint-samples")
        internal fun afterRestore(context: Context) = SampleAudioStore(directory(context)).clear()

        /** WAV 仍为 16 kHz、单声道、16 位 PCM，头字段和音频样本都用小端序。 */
        private fun writeWav(file: File, pcm: ShortArray) {
            val payloadSize = pcm.size * Short.SIZE_BYTES
            DataOutputStream(file.outputStream().buffered()).use { out ->
                fun int32(value: Int) = out.writeInt(Integer.reverseBytes(value))
                fun int16(value: Short) = out.writeShort(java.lang.Short.reverseBytes(value).toInt())

                out.writeBytes("RIFF")
                int32(36 + payloadSize)
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                int32(16)
                int16(1) // PCM
                int16(1) // 单声道
                int32(16_000)
                int32(32_000)
                int16(2)
                int16(16)
                out.writeBytes("data")
                int32(payloadSize)
                for (sample in pcm) {
                    int16(sample)
                }
            }
        }
    }
}
