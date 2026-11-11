package app.resound.devices

import app.resound.devices.driver.AudioFileFormat
import app.resound.devices.driver.DeviceException
import app.resound.devices.driver.DeviceFiles
import app.resound.devices.driver.ErrorCode
import app.resound.devices.driver.RecordingFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** 将通用驱动文件模型接到现有传输队列，不改变队列和数据库结构。 */
internal class DriverFileAdapter(override val id: String, private val files: DeviceFiles) : FileSourcePlugin, DeviceFileDetails {
    override val caps = FileCaps(files.caps.resume, files.caps.delete, files.caps.checksum != null)
    private var originals = emptyMap<String, RecordingFile>()
    private var snapshot: List<DeviceFile>? = null
    private val completed = mutableMapOf<String, Long>()
    override suspend fun list(link: DeviceLink): List<DeviceFile> {
        snapshot?.let { return it }
        val listed = files.list()
        originals = listed.associateBy { it.id }
        return listed.map { file -> DeviceFile(file.id, file.id, file.bytes, file.start.epochMillis, file.durationMillis, when (file.format) {
            AudioFileFormat.Opus20 -> FileFormat.Opus
            AudioFileFormat.OggOpus -> FileFormat.Ogg
            AudioFileFormat.Pcm16k -> FileFormat.Pcm16
            AudioFileFormat.Wav -> FileFormat.Wav
            AudioFileFormat.Mp3 -> FileFormat.Mp3
            AudioFileFormat.M4a -> FileFormat.M4a
            AudioFileFormat.Aac -> FileFormat.Aac
            else -> throw DeviceException(ErrorCode.Unsupported, "unsupported file format")
        }) }.also { snapshot = it }
    }
    fun original(file: DeviceFile) = originals[file.id] ?: throw DeviceException(ErrorCode.NotFound)
    override fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long): Flow<ByteArray> = flow {
        completed.remove(file.id)
        var total = fromOffset
        files.read(original(file), fromOffset).collect { chunk -> total += chunk.size; emit(chunk) }
        completed[file.id] = total
    }
    override fun validSize(file: DeviceFile, local: File) = completed[file.id] == local.length()
    override suspend fun delete(link: DeviceLink, file: DeviceFile) = files.delete(original(file))
    override suspend fun checksum(link: DeviceLink, file: DeviceFile) = original(file).checksum
    override fun checksumAlgorithm(file: DeviceFile) = when (files.caps.checksum) {
        app.resound.devices.driver.ChecksumAlgo.Sha256 -> "SHA-256"
        else -> null
    }
}
