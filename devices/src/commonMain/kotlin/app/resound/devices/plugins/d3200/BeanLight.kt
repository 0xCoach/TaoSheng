package app.resound.devices.plugins.d3200

// Adapted from the MIT-licensed AllDayRec Bean implementation (suyiiyii).
// Copyright (c) 2026 suyiiyii. License and provenance: vendor/d3200/THIRD_PARTY_NOTICES.md.
// The original MIT notice is also distributed in assets/licenses/d3200/LICENSE-AllDayRec.
/** Verified D3200 04.92 RGB command and fixed SPP enablement. No flash or recording commands. */
object BeanLight {
    const val SPP = "0cf12d31-fac3-4553-bd80-d6832e7d3200"
    private val replyHeader = byteArrayOf(0xaa.toByte(), 0x55, 2, 0x2c, 1, 2, 1, 0)
    private val crcTable = IntArray(256) { entry ->
        var remainder = entry
        repeat(8) {
            val polynomial = if (remainder and 1 == 1) 0x8c else 0
            remainder = (remainder ushr 1) xor polynomial
        }
        remainder
    }
    /** Previously verified server enablement; fixed zero payload, no arbitrary transfer-mode selection. */
    fun enableSpp() = byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 0x1a, 0x0f, 11, 0, 0, 0x2a)
    data class Preference(val mode: String = "none", val version: String = "0") {
        init { require(mode in setOf("none", "off", "on") && version.matches(Regex("[a-zA-Z0-9-]{1,64}"))) }
    }

    fun command(on: Boolean): ByteArray {
        val request = byteArrayOf(0x55, 0xaa.toByte(), 2, 0x2c, 1, 0, 4, 0, 0, 25, 5, 0, 0)
        if (on) request[8] = 1
        request[12] = crc(request, 0, 12).toByte()
        return request
    }

    private fun crc(bytes: ByteArray, from: Int, until: Int): Int {
        var checksum = 0
        for (index in from until until) checksum = crcTable[checksum xor bytes.unsigned(index)]
        return checksum
    }

    private fun ByteArray.unsigned(at: Int) = this[at].toInt() and 0xff

    fun qc(frame: ByteArray) = frame.size >= 9 && frame.unsigned(0) == 0xaa && frame.unsigned(1) == 0x55

    fun receipt(frame: ByteArray): Boolean? {
        if (frame.size != 10 || replyHeader.indices.any { frame[it] != replyHeader[it] }) return null
        if (crc(frame, 0, frame.lastIndex) != frame.unsigned(frame.lastIndex)) return null
        return frame.unsigned(8) == 0
    }

    fun classicAddress(company: Int, payload: ByteArray): String? {
        if (payload.size < 4) return null
        val octets = intArrayOf(company and 255, (company ushr 8) and 255,
            payload.unsigned(0), payload.unsigned(1), payload.unsigned(2), payload.unsigned(3))
        if (octets.all { it == 0 } || octets.all { it == 255 }) return null
        val digits = "0123456789ABCDEF"
        return buildString(17) {
            for ((i, octet) in octets.withIndex()) {
                if (i > 0) append(':')
                append(digits[octet ushr 4])
                append(digits[octet and 15])
            }
        }
    }

    /** Each transport has its own decoder, so a partial BLE frame cannot absorb an SPP audio frame.
     * Normal replies (including encrypted audio) are preserved byte for byte. */
    class Decoder {
        private var buffer = ByteArray(256)
        private var end = 0

        fun push(bytes: ByteArray): List<ByteArray> {
            require(bytes.size <= BUFFER_LIMIT)
            if (bytes.size > BUFFER_LIMIT - end) end = 0
            val required = end + bytes.size
            if (required > buffer.size) buffer = buffer.copyOf(maxOf(required, buffer.size * 2).coerceAtMost(BUFFER_LIMIT))
            bytes.copyInto(buffer, end)
            end = required

            val frames = ArrayList<ByteArray>()
            var start = 0
            while (end - start >= 2) {
                val prefix = (buffer.unsigned(start) shl 8) or buffer.unsigned(start + 1)
                val length = when (prefix) {
                    0x09ff -> encodedLength(start, headerBytes = 9, trailerBytes = 0, limit = 65535)
                    0xaa55 -> encodedLength(start, headerBytes = 8, trailerBytes = 9, limit = 1024)
                    else -> INVALID
                }
                if (length == INCOMPLETE) break
                if (length == INVALID || prefix == 0x09ff && (buffer[start + 2] != 0.toByte() || buffer[start + 3] != 0.toByte())) {
                    start++
                    continue
                }
                if (length > end - start) break
                val checksumAt = start + length - 1
                val checksum = if (prefix == 0xaa55) crc(buffer, start, checksumAt) else checksum(start, checksumAt)
                if (buffer.unsigned(checksumAt) == checksum) {
                    frames += buffer.copyOfRange(start, checksumAt + 1)
                    start = checksumAt + 1
                } else {
                    start++
                }
            }
            if (start > 0) buffer.copyInto(buffer, startIndex = start, endIndex = end)
            end -= start
            return frames
        }

        private fun encodedLength(start: Int, headerBytes: Int, trailerBytes: Int, limit: Int): Int {
            if (end - start < headerBytes) return INCOMPLETE
            val sizeAt = start + headerBytes - 2
            val length = D3200Protocol.u16(buffer, sizeAt) + trailerBytes
            return if (length in (headerBytes + 1)..limit) length else INVALID
        }

        private fun checksum(start: Int, until: Int): Int {
            var sum = 0
            for (index in start until until) sum += buffer.unsigned(index)
            return sum and 255
        }

        private companion object {
            const val BUFFER_LIMIT = 131072
            const val INVALID = -1
            const val INCOMPLETE = -2
        }
    }
}

/** One outstanding RGB command. The reply carries no transaction ID: after timeout the socket must be discarded. */
class BeanLightState {
    private data class Flight(val preference: BeanLight.Preference, val expiresAt: Long)
    private data class Confirmation(val preference: BeanLight.Preference, val wallTime: Long)

    private var wanted = BeanLight.Preference()
    private var flight: Flight? = null
    private var nextSend = 0L
    private var state = "stopped"
    private var detail = ""
    private var confirmation: Confirmation? = null

    fun select(p: BeanLight.Preference) {
        if (wanted == p) return
        wanted = p
        nextSend = 0
        status("pending")
    }

    fun outgoing(now: Long): ByteArray? {
        if (wanted.mode == "none" || flight != null || now < nextSend) return null
        flight = Flight(wanted, now + 3000)
        if (confirmation?.preference != wanted) state = "pending"
        return BeanLight.command(wanted.mode == "on")
    }

    fun acknowledge(frame: ByteArray, now: Long, wall: Long): Boolean? {
        val current = flight ?: return null
        val accepted = BeanLight.receipt(frame) ?: return null
        if (!accepted) {
            failed("设备拒绝灯光指令")
            return false
        }
        flight = null
        confirmation = Confirmation(current.preference, wall)
        val latestPreference = current.preference == wanted
        nextSend = now + if (latestPreference) 2000 else 0
        status(if (latestPreference) "confirmed" else "pending")
        return true
    }

    fun expired(now: Long) = flight?.let { now >= it.expiresAt } ?: false

    fun waiting() = resetFlight("waiting", detail)

    fun failed(why: String) = resetFlight("error", why)

    private fun resetFlight(nextState: String, reason: String) {
        flight = null
        nextSend = 0
        status(nextState, reason)
    }

    private fun status(nextState: String, reason: String = "") {
        state = if (wanted.mode == "none") "stopped" else nextState
        detail = reason
    }

    fun snapshot(): Map<String, Any?> = mapOf("capability" to 1, "state" to state, "detail" to detail,
        "mode" to wanted.mode, "version" to wanted.version, "confirmed_mode" to confirmation?.preference?.mode,
        "confirmed_version" to confirmation?.preference?.version, "confirmed_at" to confirmation?.wallTime?.takeIf { it > 0 })
}
