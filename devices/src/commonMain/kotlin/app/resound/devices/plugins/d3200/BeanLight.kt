package app.resound.devices.plugins.d3200

// Adapted from the MIT-licensed AllDayRec Bean implementation (suyiiyii).
// See vendor/d3200/LICENSE-AllDayRec and docs/任务卡/D3200-协议与灯控-交付说明.md.
/** Verified D3200 04.92 RGB command and fixed SPP enablement. No flash or recording commands. */
object BeanLight {
    const val SPP = "0cf12d31-fac3-4553-bd80-d6832e7d3200"
    /** Previously verified server enablement; fixed zero payload, no arbitrary transfer-mode selection. */
    fun enableSpp() = byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 0x1a, 0x0f, 11, 0, 0, 0x2a)
    data class Preference(val mode: String = "none", val version: String = "0") {
        init { require(mode in setOf("none", "off", "on") && version.matches(Regex("[a-zA-Z0-9-]{1,64}"))) }
    }

    fun command(on: Boolean): ByteArray {
        val b = byteArrayOf(0x55, 0xaa.toByte(), 2, 0x2c, 1, 0, 4, 0, if (on) 1 else 0, 25, 5, 0, 0)
        b[b.lastIndex] = crc(b, 0, b.lastIndex).toByte()
        return b
    }
    private fun crc(b: ByteArray, start: Int, end: Int): Int {
        var c = 0
        for (i in start until end) {
            c = c xor (b[i].toInt() and 255)
            repeat(8) { c = (c ushr 1) xor if (c and 1 != 0) 0x8c else 0 }
        }
        return c
    }
    fun qc(b: ByteArray) = b.size >= 9 && b[0] == 0xaa.toByte() && b[1] == 0x55.toByte()
    fun receipt(b: ByteArray): Boolean? {
        if (b.size != 10 || !qc(b) || b[2] != 2.toByte() || b[3] != 0x2c.toByte() || b[4] != 1.toByte() ||
            b[5] != 2.toByte() || b[6] != 1.toByte() || b[7] != 0.toByte() || crc(b, 0, 9) != (b[9].toInt() and 255)) return null
        return b[8] == 0.toByte()
    }
    fun classicAddress(company: Int, payload: ByteArray): String? {
        val b = byteArrayOf(company.toByte(), (company ushr 8).toByte()) + payload
        if (b.size < 6) return null
        val mac = b.take(6)
        if (mac.all { it == 0.toByte() } || mac.all { it == 0xff.toByte() }) return null
        return mac.joinToString(":") { (it.toInt() and 255).toString(16).padStart(2, '0').uppercase() }
    }

    /** Each transport has its own decoder, so a partial BLE frame cannot absorb an SPP audio frame.
     * Normal replies (including encrypted audio) are preserved byte for byte. */
    class Decoder {
        private var pending = ByteArray(0)
        fun push(bytes: ByteArray): List<ByteArray> {
            require(bytes.size <= 131072)
            if (pending.size + bytes.size > 131072) pending = ByteArray(0)
            pending += bytes
            val out = mutableListOf<ByteArray>()
            var i = 0
            while (pending.size - i >= 2) {
                val normal = pending[i] == 9.toByte() && pending[i + 1] == 0xff.toByte()
                val qc = pending[i] == 0xaa.toByte() && pending[i + 1] == 0x55.toByte()
                if (!normal && !qc) { i++; continue }
                val header = if (normal) 9 else 8
                if (pending.size - i < header) break
                val at = i + if (normal) 7 else 6
                val n = (pending[at].toInt() and 255) + ((pending[at + 1].toInt() and 255) shl 8) + if (normal) 0 else 9
                if (n < header + 1 || n > (if (normal) 65535 else 1024) ||
                    normal && (pending[i + 2] != 0.toByte() || pending[i + 3] != 0.toByte())) { i++; continue }
                if (pending.size - i < n) break
                val check = if (normal) (i until i + n - 1).sumOf { pending[it].toInt() and 255 } and 255
                    else crc(pending, i, i + n - 1)
                if (check != (pending[i + n - 1].toInt() and 255)) { i++; continue }
                out += pending.copyOfRange(i, i + n)
                i += n
            }
            pending = pending.copyOfRange(i, pending.size)
            return out
        }
    }
}

/** One outstanding RGB command. The reply carries no transaction ID: after timeout the socket must be discarded. */
class BeanLightState {
    private var wanted = BeanLight.Preference()
    private var sent: BeanLight.Preference? = null
    private var deadline = 0L
    private var next = 0L
    private var state = "stopped"
    private var detail = ""
    private var confirmed: BeanLight.Preference? = null
    private var confirmedAt = 0L

    fun select(p: BeanLight.Preference) {
        if (wanted == p) return
        wanted = p
        next = 0
        detail = ""
        state = if (p.mode == "none") "stopped" else "pending"
    }
    fun outgoing(now: Long): ByteArray? {
        if (wanted.mode == "none" || sent != null || now < next) return null
        sent = wanted
        deadline = now + 3000
        if (confirmed != wanted) state = "pending"
        return BeanLight.command(wanted.mode == "on")
    }
    fun acknowledge(frame: ByteArray, now: Long, wall: Long): Boolean? {
        val ok = BeanLight.receipt(frame) ?: return null
        val p = sent ?: return null
        sent = null
        if (!ok) { failed("设备拒绝灯光指令"); return false }
        confirmed = p
        confirmedAt = wall
        state = if (wanted.mode == "none") "stopped" else if (p == wanted) "confirmed" else "pending"
        detail = ""
        next = if (p == wanted) now + 2000 else now
        return true
    }
    fun expired(now: Long) = sent != null && now >= deadline
    fun waiting() { sent = null; next = 0; state = if (wanted.mode == "none") "stopped" else "waiting" }
    fun failed(why: String) { waiting(); detail = why; if (wanted.mode != "none") state = "error" }
    fun snapshot(): Map<String, Any?> = mapOf("capability" to 1, "state" to state, "detail" to detail,
        "mode" to wanted.mode, "version" to wanted.version, "confirmed_mode" to confirmed?.mode,
        "confirmed_version" to confirmed?.version, "confirmed_at" to confirmedAt.takeIf { it > 0 })
}
