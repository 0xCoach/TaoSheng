package app.resound.devices.plugins.d3200

/** Payload offsets from Soundcore's DeviceInfoModel / PROTOCOL.md §6.1. */
data class D3200DeviceInfo(
    val battery: Int? = null, val charging: Boolean? = null,
    val boxBattery: Int? = null, val boxCharging: Boolean? = null,
    val firmware: String = "", val boxFirmware: String = "",
    val totalKb: Long? = null, val freeKb: Long? = null, val recording: Int? = null,
    val serial: String? = null,
) {
    val storage: String get() {
        val total = totalKb ?: return "未知"
        val free = freeKb ?: return "未知"
        fun size(kb: Long): String = if (kb >= 1048576) {
            val tenths = (kb * 10 + 524288) / 1048576
            "${tenths / 10}.${tenths % 10} GB"
        } else "${(kb + 512) / 1024} MB"
        return "已用 ${size(total - free)} / 共 ${size(total)}，剩余 ${size(free)}"
    }
    companion object {
        fun battery(raw: Int): Int? = when (raw) { in 0..9 -> (raw + 1) * 10; in 10..100 -> raw; else -> null }
        private fun flag(raw: Int?) = when (raw) { 0 -> false; 1 -> true; else -> null }
        fun parse(p: ByteArray): D3200DeviceInfo {
            fun u8(at: Int) = p.getOrNull(at)?.toInt()?.and(255)
            fun ascii(at: Int, n: Int) = if (p.size >= at + n)
                p.copyOfRange(at, at + n).decodeToString().substringBefore('\u0000').trim() else ""
            fun u32(at: Int) = if (p.size >= at + 4) D3200Protocol.u32(p, at) else null
            val total = u32(24)?.takeIf { it > 0 }
            val free = u32(28)?.takeIf { total != null && it <= total }
            val serial = if (p.size >= 24) {
                val field = p.copyOfRange(8,24)
                val raw = field.takeWhile { it != 0.toByte() }
                if (raw.size in 8..16 && field.drop(raw.size).all { it == 0.toByte() } &&
                    raw.all { it.toInt() in 0x21..0x7e } && raw.any { it != '0'.code.toByte() })
                    raw.toByteArray().decodeToString().lowercase() else null
            } else null
            return D3200DeviceInfo(u8(1)?.let(::battery), flag(u8(2)), u8(38)?.let(::battery), flag(u8(32)),
                ascii(3, 5), ascii(33, 5), total, free, u8(50)?.takeIf { it in 0..2 },serial)
        }
        fun pushed(command: Int, p: ByteArray, old: D3200DeviceInfo): D3200DeviceInfo = when {
            command == 0x0103 && p.size >= 2 -> old.copy(battery = battery(p[0].toInt() and 255), boxBattery = battery(p[1].toInt() and 255))
            command == 0x0104 && p.size >= 2 -> old.copy(charging = flag(p[0].toInt() and 255), boxCharging = flag(p[1].toInt() and 255))
            command == 0x1882 && p.isNotEmpty() && p[0].toInt() in 0..2 -> old.copy(recording = p[0].toInt())
            command == 0x1a06 && p.size >= 4 && D3200Protocol.u32(p, 0) != 0L -> old.copy(recording = 1)
            else -> old
        }
    }
}
