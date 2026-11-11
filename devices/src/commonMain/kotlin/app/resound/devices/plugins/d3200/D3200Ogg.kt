package app.resound.devices.plugins.d3200

/** Ogg 页字段按小端，granule 使用 Opus 的 48 kHz 时钟。调用方负责流式读写。 */
object D3200Ogg {
    fun page(packets: List<ByteArray>, serial: Int, sequence: Int, granule: Long, flags: Int = 0): ByteArray {
        require(packets.isNotEmpty() && packets.size <= 255 && packets.all { it.size in 1..254 })
        val b = ByteArray(27 + packets.size + packets.sumOf { it.size })
        fun le(at: Int, value: Long, count: Int) { repeat(count) { b[at + it] = (value ushr (8 * it)).toByte() } }
        "OggS".encodeToByteArray().copyInto(b)
        b[5] = flags.toByte(); le(6, granule, 8); le(14, serial.toLong(), 4); le(18, sequence.toLong(), 4)
        b[26] = packets.size.toByte()
        var at = 27 + packets.size
        packets.forEachIndexed { i, packet -> b[27 + i] = packet.size.toByte(); packet.copyInto(b, at); at += packet.size }
        le(22, crc(b).toLong(), 4)
        return b
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
}
