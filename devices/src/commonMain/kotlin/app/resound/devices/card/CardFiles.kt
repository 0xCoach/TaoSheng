package app.resound.devices.card

import app.resound.devices.*
import app.resound.devices.driver.*
import app.resound.devices.driver.FileCaps
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 旧文件插件只看到 DeviceLink。不会假造保存确认，校验算法未知时明确拒绝接入。 */
class CardFiles(
    private val plugin: FileSourcePlugin,
    private val link: DeviceLink,
    checksum: ChecksumAlgo? = null,
) : DeviceFiles {
    init {
        if (plugin.caps.checksum && checksum == null) throw DeviceException(ErrorCode.Unsupported, "file checksum algorithm missing")
    }
    override val caps = FileCaps(plugin.caps.resume, false, plugin.caps.delete,
        checksum.takeIf { plugin.caps.checksum }, if (plugin.caps.delete) Reclaim.Manual else Reclaim.Never)
    private val lock = Mutex()
    private var opened = false
    private var listed = emptyMap<String, DeviceFile>()
    override suspend fun list(): List<RecordingFile> = lock.withLock {
        if (!opened) { plugin.open(link); opened = true }
        val files = plugin.list(link).sortedBy { it.startedAt ?: Long.MIN_VALUE }
        listed = files.associateBy { it.id }
        files.map { file ->
            RecordingFile(file.id, FileState.Complete, file.bytes, when (file.format) {
                FileFormat.Wav -> AudioFileFormat.Wav
                FileFormat.Opus -> AudioFileFormat.Opus20
                FileFormat.Ogg -> AudioFileFormat.OggOpus
                FileFormat.Mp3 -> AudioFileFormat.Mp3
                FileFormat.M4a -> AudioFileFormat.M4a
                FileFormat.Aac -> AudioFileFormat.Aac
                FileFormat.Pcm16 -> AudioFileFormat.Pcm16k
            }, DeviceTime(epochMillis = file.startedAt), file.durationMillis,
                if (plugin.caps.checksum) plugin.checksum(link, file) else null)
        }
    }
    private fun original(file: RecordingFile) = listed[file.id] ?: throw DeviceException(ErrorCode.NotFound)
    override fun read(file: RecordingFile, fromOffset: Long) = plugin.download(link, original(file), fromOffset.also {
        if (it < 0 || (it != 0L && !caps.resume)) throw DeviceException(ErrorCode.BadArgs)
    })
    override suspend fun confirm(file: RecordingFile, checksum: ByteArray?) { /* 老协议无确认命令。 */ }
    override suspend fun delete(file: RecordingFile) {
        if (!caps.delete) throw DeviceException(ErrorCode.Unsupported)
        plugin.delete(link, original(file))
    }
}
