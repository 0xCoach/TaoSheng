package app.resound.devices.plugins.d3200

// Protocol research attribution and licenses: vendor/d3200/THIRD_PARTY_NOTICES.md.

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
        fun parse(payload: ByteArray): D3200DeviceInfo {
            val total = payload.storageField(24)?.takeIf { it > 0 }
            val free = total?.let { capacity -> payload.storageField(28)?.takeIf { it <= capacity } }
            return D3200DeviceInfo(
                battery = payload.number(1)?.let(::battery),
                charging = flag(payload.number(2)),
                boxBattery = payload.number(38)?.let(::battery),
                boxCharging = flag(payload.number(32)),
                firmware = payload.firmwareAt(3),
                boxFirmware = payload.firmwareAt(33),
                totalKb = total,
                freeKb = free,
                recording = payload.number(50)?.takeIf { it in 0..2 },
                serial = payload.serialField(),
            )
        }

        private fun ByteArray.number(at: Int) = getOrNull(at)?.toInt()?.and(255)

        private fun ByteArray.storageField(at: Int) =
            if (size - at >= 4) D3200Protocol.u32(this, at) else null

        private fun ByteArray.firmwareAt(at: Int): String {
            if (size - at < 5) return ""
            return decodeToString(at, at + 5).substringBefore('\u0000').trim()
        }

        private fun ByteArray.serialField(): String? {
            val start = 8
            val limit = 24
            if (size < limit) return null
            var end = start
            var nonZeroDigit = false
            while (end < limit && this[end] != 0.toByte()) {
                val code = this[end].toInt()
                if (code !in 0x21..0x7e) return null
                if (code != '0'.code) nonZeroDigit = true
                end++
            }
            if (end - start < 8 || !nonZeroDigit) return null
            if ((end until limit).any { this[it] != 0.toByte() }) return null
            return decodeToString(start, end).lowercase()
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
