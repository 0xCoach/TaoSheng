package app.resound.devices.plugins.sonicloud

import app.resound.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 独立协议实现；合并卡 118 时可收拢至 FrameCodec。依据官方协议 3、6、7 节。 */
internal object FileFrames {
    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 65535 }
        }
        return crc
    }
    fun command(seq: Int, cmd: Int, params: ByteArray = byteArrayOf(), type: Int = 2): ByteArray {
        val data = byteArrayOf(type.toByte(), cmd.toByte()) + params
        val len = byteArrayOf(data.size.toByte(), (data.size shr 8).toByte())
        val crc = crc(len + data)
        return byteArrayOf(0x5a, seq.toByte(), crc.toByte(), (crc shr 8).toByte()) + len + data
    }
    fun download(seq: Int, name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 24)
        return command(seq, 2, ByteArray(4) + bytes.copyOf(24))
    }
    class Parser {
        private var buffer = byteArrayOf()
        fun feed(bytes: ByteArray): List<ByteArray> {
            buffer += bytes
            val result = mutableListOf<ByteArray>()
            while (buffer.isNotEmpty()) {
                if (buffer[0] != 0x5a.toByte()) { buffer = buffer.drop(1).toByteArray(); continue }
                if (buffer.size < 6) break
                val len = (buffer[4].toInt() and 255) or ((buffer[5].toInt() and 255) shl 8)
                require(len in 1..8192) { "协议长度异常" }
                if (buffer.size < len + 6) break
                val expected = (buffer[2].toInt() and 255) or ((buffer[3].toInt() and 255) shl 8)
                check(crc(buffer.copyOfRange(4, len + 6)) == expected) { "协议 CRC 错误" }
                result += buffer.copyOfRange(6, len + 6); buffer = buffer.copyOfRange(len + 6, buffer.size)
            }
            return result
        }
    }
}

internal class SonicloudFiles : FileSourcePlugin, DeviceFileDetails {
    override val id = "sonicloud"
    override val caps = FileCaps(resume = false, delete = true, checksum = false)
    private var seq = 0
    private val rawEntries = mutableMapOf<String, ByteArray>()
    companion object {
        const val SERVICE = "0000ae20-0000-1000-8000-00805f9b34fb"
        const val WRITE = "0000ae21-0000-1000-8000-00805f9b34fb"
        const val NOTIFY = "0000ae22-0000-1000-8000-00805f9b34fb"
    }
    private suspend fun <T> session(link: DeviceLink, request: ByteArray, action: suspend (Channel<ByteArray>) -> T): T = coroutineScope {
        val channel = Channel<ByteArray>(64)
        val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
            val parser = FileFrames.Parser()
            try { link.notifications(SERVICE, NOTIFY).collect { bytes -> parser.feed(bytes).forEach { channel.send(it) } } }
            finally { channel.close() }
        }
        try {
            link.write(SERVICE, WRITE, request, withResponse = false)
            action(channel)
        } finally { receiver.cancelAndJoin(); channel.cancel() }
    }
    override suspend fun open(link: DeviceLink) {
        session(link, FileFrames.command(seq++, 3, type = 0)) { channel ->
            withTimeout(12000) { while (true) {
                val frame = channel.receive()
                if (frame.size >= 3 && frame[0] == 0.toByte() && frame[1] == 4.toByte()) break
            } }
        }
    }
    override suspend fun list(link: DeviceLink): List<DeviceFile> = session(link, FileFrames.command(seq++, 0)) { channel ->
        rawEntries.clear()
        val result = mutableListOf<DeviceFile>()
        withTimeout(60000) {
            while (true) {
                val frame = withTimeoutOrNull(if (result.isEmpty()) 12000L else 1200L) { channel.receive() }
                if (frame == null) { check(result.isNotEmpty()) { "列表超时" }; break }
                if (frame.size < 2 || frame[0] != 2.toByte()) continue
                if (frame[1] == 18.toByte()) { check(frame.size >= 3 && frame[2] == 0.toByte()); break }
                if (frame[1] != 1.toByte()) continue
                val body = ByteBuffer.wrap(frame, 2, frame.size - 2).order(ByteOrder.BIG_ENDIAN)
                require(body.remaining() >= 4)
                val count = body.int
                require(count >= 0 && count <= body.remaining() / 28)
                repeat(count) {
                    val raw = ByteArray(28); body.get(raw)
                    val fields = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                    val seconds = fields.int.toLong() and 0xffffffffL
                    val size = fields.int.toLong() and 0xffffffffL
                    val name = raw.copyOfRange(8, 28).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                    val base = name.trimEnd('.').removeSuffix(".opus").removeSuffix(".wav")
                    val start = runCatching { LocalDateTime.parse(base.removePrefix("note"), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                    val file = DeviceFile(name, "$base.wav", size, start, seconds.takeIf { it < 1_000_000_000 }?.times(1000), FileFormat.Wav)
                    rawEntries[name] = raw; result += file
                }
            }
        }
        result
    }
    override fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long): Flow<ByteArray> = flow {
        require(fromOffset == 0L)
        session(link, FileFrames.download(seq++, file.name)) { channel ->
            var started = false
            while (true) {
                val frame = withTimeout(12000) { channel.receive() }
                if (frame.size < 2 || frame[0] != 2.toByte()) continue
                when (frame[1].toInt()) {
                    3 -> started = true
                    4 -> { check(started); emit(frame.copyOfRange(2, frame.size)) }
                    5 -> { check(frame.size >= 3 && frame[2] == 0.toByte()) { "设备下载失败" }; check(started); break }
                }
            }
        }
    }
    override suspend fun delete(link: DeviceLink, file: DeviceFile) {
        val raw = requireNotNull(rawEntries[file.id])
        session(link, FileFrames.command(seq++, 8, raw)) { channel ->
            withTimeout(12000) { while (true) {
                val frame = channel.receive()
                if (frame.size >= 3 && frame[0] == 2.toByte() && frame[1] == 13.toByte()) {
                    check(frame[2] == 0.toByte()) { "删除失败" }; break
                }
            } }
        }
    }
    // 协议未提供修改时间，不把重连时刻冒充文件结束时间；无对时能力时交由核心记录问题。
    override fun validSize(file: DeviceFile, local: File): Boolean = RandomAccessFile(local, "r").use { input ->
        if (input.length() < 44) return@use false
        val header = ByteArray(12); input.readFully(header)
        header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) &&
            (ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL) + 8 == input.length()
    }
}
