package app.resound.devices

import app.resound.devices.core.FrameCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 测试夹具，仅保留拼包和固定字节布局。
internal class SyntheticFileFrames {
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
