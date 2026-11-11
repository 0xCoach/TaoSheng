package app.resound.devices.plugins.d3200

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming Ogg/Opus framing. Preserve the full decrypted packet; never trim trailing bytes. */
internal object D3200Ogg {
    fun page(packets: List<ByteArray>, serial: Int, sequence: Int, granule: Long, flags: Int = 0): ByteArray {
        require(packets.isNotEmpty() && packets.all { it.size in 1..254 })
        val size = 27 + packets.size + packets.sumOf { it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OggS".toByteArray()); b.put(0); b.put(flags.toByte()); b.putLong(granule)
        b.putInt(serial); b.putInt(sequence); b.putInt(0); b.put(packets.size.toByte())
        packets.forEach { b.put(it.size.toByte()) }; packets.forEach(b::put)
        val bytes = b.array(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(bytes))
        return bytes
    }
    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        return crc
    }
    fun packetSamples(p: ByteArray): Int {
        require(p.size == 160)
        val toc = p[0].toInt() and 255
        val config = toc ushr 3
        val per = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            config and 3 == 3 -> 2880
            else -> 480 shl (config and 3)
        }
        val frames = when (toc and 3) { 0 -> 1; 3 -> p[1].toInt() and 63; else -> 2 }
        require(per * frames == 960) { "离线录音包不是已验证的 20 ms 格式" }
        return 960
    }
    fun write(raw: File, target: File, range: D3200HistoryCore.Range) {
        require(range.until > range.from && range.until * 160 <= raw.length())
        RandomAccessFile(raw, "r").use { input ->
            input.seek(range.from * 160)
            val first = ByteArray(160).also(input::readFully)
            val channels = if (first[0].toInt() and 4 != 0) 2 else 1
            val serial = (range.from xor range.until).toInt() xor 0x44333230
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray()); put(1); put(channels.toByte()); putShort(0)
                putInt(48000); putShort(0); put(0)
            }.array()
            val vendor = "TaoSheng D3200".toByteArray()
            val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusTags".toByteArray()); putInt(vendor.size); put(vendor); putInt(0)
            }.array()
            target.outputStream().buffered().use { output ->
                output.write(page(listOf(head), serial, 0, 0, 2)); output.write(page(listOf(tags), serial, 1, 0))
                input.seek(range.from * 160)
                var at = range.from; var sequence = 2; var granule = 0L
                while (at < range.until) {
                    val packets = (0 until minOf(50L, range.until - at).toInt()).map {
                        ByteArray(160).also { p ->
                            input.readFully(p); granule += packetSamples(p)
                            require((if (p[0].toInt() and 4 != 0) 2 else 1) == channels) { "离线录音声道变化" }
                        }
                    }
                    at += packets.size
                    output.write(page(packets, serial, sequence++, granule, if (at == range.until) 4 else 0))
                }
            }
        }
    }
}
