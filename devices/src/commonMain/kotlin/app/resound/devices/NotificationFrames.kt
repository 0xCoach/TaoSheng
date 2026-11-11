package app.resound.devices

import app.resound.devices.core.FrameCodec

/** LengthFrom 卡按声明长度拼半包/拆多包；Rest 保留 notify 边界（含分片号）。 */
class NotificationFrames(private val spec: FrameSpec) {
    private var buffer = byteArrayOf()
    fun accept(bytes: ByteArray, codec: FrameCodec): List<ByteArray> {
        val payload = spec.payload as? PayloadSpec.LengthFrom ?: return listOf(bytes)
        require(buffer.size + bytes.size <= 65536) { "通知缓存超限" }
        buffer += bytes
        val header = spec.fields.sumOf { it.bytes }
        val lengthField = spec.fields.single { it.name == payload.field }
        val offset = spec.fields.takeWhile { it != lengthField }.sumOf { it.bytes }
        val result = mutableListOf<ByteArray>()
        while (buffer.size >= header) {
            var cursor = 0
            val magicMatches = spec.fields.all { field ->
                val matches = field.const?.contentEquals(buffer.copyOfRange(cursor, cursor + field.bytes)) ?: true
                cursor += field.bytes; matches
            }
            if (!magicMatches) { buffer = buffer.copyOfRange(1, buffer.size); continue }
            val orders = if (lengthField.endian == Endian.Probe) codec.lockedEndian?.let(::listOf) ?: listOf(Endian.Le, Endian.Be)
                else listOf(lengthField.endian)
            val sizes = orders.map { endian ->
                var length = 0L
                for (i in 0 until lengthField.bytes) {
                    val shift = if (endian == Endian.Le) i * 8 else (lengthField.bytes - 1 - i) * 8
                    length = length or ((buffer[offset + i].toLong() and 255) shl shift)
                }
                length + header
            }.filter { it in header.toLong()..32768L }.map { it.toInt() }.distinct()
            if (sizes.isEmpty()) { buffer = buffer.copyOfRange(1, buffer.size); continue }
            val size = if (orders.size == 1) sizes.single().takeIf { it <= buffer.size }
            else sizes.firstOrNull { it <= buffer.size && codec.probe(buffer.copyOf(it)) != null }
            if (size == null) {
                if (sizes.all { it <= buffer.size }) { buffer = buffer.copyOfRange(1, buffer.size); continue }
                break
            }
            result += buffer.copyOf(size); buffer = buffer.copyOfRange(size, buffer.size)
        }
        return result
    }
}
