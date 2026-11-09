package app.resound.devices.plugins.sonicloud

import app.resound.devices.*
import app.resound.devices.core.FrameCodec
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

/** 文件通道按官方协议使用小端；仅保留 BLE 字节块到完整包的缓存和文件命令参数。 */
internal class FileFrames {
    private val encoder = FrameCodec(spec)
    fun command(cmd: Int, params: ByteArray = byteArrayOf(), type: Int = 2): ByteArray =
        encoder.encode(CardCommand.Framed(type, cmd, params))

    fun download(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 24)
        return command(2, ByteArray(4) + bytes.copyOf(24))
    }

    class Parser {
        private var buffer = byteArrayOf()
        // 文件应答沿用旧行为：不按音频序号去重/丢迟到包，交给文件命令状态机处理。
        private val decoder = FrameCodec(spec.copy(fields = spec.fields.map {
            if (it.role == FieldRole.Seq) it.copy(role = FieldRole.Other) else it
        }), maxPayloadBytes = 8192)
        fun feed(bytes: ByteArray): List<ByteArray> {
            buffer += bytes
            val result = mutableListOf<ByteArray>()
            while (buffer.isNotEmpty()) {
                if (buffer[0] != 0x5a.toByte()) { buffer = buffer.drop(1).toByteArray(); continue }
                if (buffer.size < 6) break
                // FrameCodec 接收完整协议包；这里只按 LEN 确定缓存边界，不重复实现校验/拆包。
                val len = ByteBuffer.wrap(buffer, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
                require(len in 1..8192) { "协议长度异常" }
                if (buffer.size < len + 6) break
                val packet = buffer.copyOfRange(0, len + 6)
                result += checkNotNull(decoder.accept(packet)) { "协议 CRC 错误" }.payload
                buffer = buffer.copyOfRange(len + 6, buffer.size)
            }
            return result
        }
    }

    companion object {
        private val spec = FrameSpec(listOf(
            FrameField("magic", 1, const = byteArrayOf(0x5a), role = FieldRole.Magic),
            FrameField("seq", 1, role = FieldRole.Seq),
            FrameField("crc", 2, role = FieldRole.Crc,
                crc = CrcSpec(CrcAlgo.Crc16Xmodem, listOf("len", "payload"))),
            FrameField("len", 2, role = FieldRole.Len),
        ), PayloadSpec.LengthFrom("len"))
    }
}

internal class SonicloudFiles : FileSourcePlugin, DeviceFileDetails {
    override val id = "sonicloud"
    override val caps = FileCaps(resume = false, delete = true, checksum = false)
    private val frames = FileFrames()
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
        session(link, frames.command(3, type = 0)) { channel ->
            withTimeout(12000) { while (true) {
                val frame = channel.receive()
                if (frame.size >= 3 && frame[0] == 0.toByte() && frame[1] == 4.toByte()) break
            } }
        }
    }
    override suspend fun list(link: DeviceLink): List<DeviceFile> = session(link, frames.command(0)) { channel ->
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
        session(link, frames.download(file.name)) { channel ->
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
        session(link, frames.command(8, raw)) { channel ->
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
