package app.resound.devices.plugins.d3200

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

/** D3200 protocol adapted from the MIT-licensed AllDayRec Bean implementation (suyiiyii).
 * See vendor/d3200/LICENSE-AllDayRec for attribution.
 * No bind, erase, delete, firmware, filesystem or device-memory commands. */
internal object D3200Protocol {
    const val SERVICE = "020cf5da-0000-1000-8000-00805f9b34fb"
    const val WRITE = "00007777-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "00008888-0000-1000-8000-00805f9b34fb"
    fun u32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    fun le32(n: Long) = ByteArray(4) { (n ushr (8 * it)).toByte() }
    fun frame(type: Int, id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require((type shl 8 or id) in setOf(0x0101, 0x2e01, 0x1a0e, 0x1b0e, 0x1a07, 0x1882))
        if (type == 0x18) require(payload.contentEquals(byteArrayOf(1)))
        val b = byteArrayOf(8, 0xee.toByte(), 0, 0, 0, type.toByte(), id.toByte(), 0, 0) + payload + byteArrayOf(0)
        b[7] = b.size.toByte(); b[8] = (b.size ushr 8).toByte()
        b[b.lastIndex] = b.dropLast(1).sumOf { it.toInt() and 255 }.toByte()
        return b
    }
    fun request(file: Long, offset: Long) = frame(0x1a, 7, le32(offset) + le32(file) + byteArrayOf(1))
    fun offlineRequest(file: Long, packet: Long) = frame(0x1a, 7, le32(packet * 166) + le32(file) + byteArrayOf(0))
    fun listPage(page: Int) = frame(0x1b, 0x0e, byteArrayOf(page.toByte(), (page ushr 8).toByte()))
    fun currentFile(p: ByteArray): Long {
        require(p.size >= 2)
        val at = 2 + u16(p, 0) * 8
        require(at + 4 <= p.size)
        return u32(p, at)
    }
    fun payload(b: ByteArray) = b.copyOfRange(9, b.size - 1)
    fun command(b: ByteArray) = (b[5].toInt() and 255) shl 8 or (b[6].toInt() and 255)
    fun headerMatches(p: ByteArray, file: Long) = p.size >= 4 && u32(p, 0) == file
    data class Slice(val seq: Long, val data: ByteArray)
    fun slices(p: ByteArray): List<Slice> {
        require(p.size % 166 == 0) { "音频分片长度异常" }
        return (p.indices step 166).map { Slice(u32(p, it), p.copyOfRange(it + 5, it + 165)) }
    }
}

internal class D3200Crypto {
    private val mine = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var session: ByteArray? = null
    private var fileKey: ByteArray? = null
    private var nonce: ByteArray? = null
    private fun fixed(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
    fun publicKey() = (mine.public as ECPublicKey).let { byteArrayOf(4) + fixed(it.w.affineX) + fixed(it.w.affineY) }
    fun handshake(p: ByteArray) {
        require(p.size >= 97 && p[0] == 4.toByte()) { "密钥交换响应不完整" }
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, p.copyOfRange(1, 33)), BigInteger(1, p.copyOfRange(33, 65))), (mine.public as ECPublicKey).params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(mine.private); doPhase(peer, true); generateSecret() }
        require(MessageDigest.isEqual(shared, p.copyOfRange(65, 97))) { "密钥交换校验失败" }
        session = hkdf(shared)
    }
    fun fileHeader(p: ByteArray, expected: Long) {
        require(p.size >= 87 && D3200Protocol.headerMatches(p, expected)) { "音频文件头不匹配" }
        require((p[86].toInt() and 255) in setOf(0, 255)) { "录音豆拒绝读取文件" }
        val plain = ctr(requireNotNull(session), p.copyOfRange(70, 86), p.copyOfRange(24, 70))
        val magic = "soundcored3200".toByteArray(Charsets.US_ASCII)
        require(plain.size == magic.size + 32 && plain.copyOfRange(0, magic.size).contentEquals(magic)) { "音频密钥解封失败" }
        fileKey = plain.copyOfRange(magic.size, plain.size); nonce = p.copyOfRange(8, 24)
    }
    fun decrypt(slice: D3200Protocol.Slice): ByteArray {
        require(slice.seq in 0..429_496_729L) { "音频序号超出计数范围" }
        return ctr(requireNotNull(fileKey) { "尚未取得音频密钥" }, requireNotNull(nonce).copyOf(12) +
            ByteBuffer.allocate(4).putInt((slice.seq * 10).toInt()).array(), slice.data)
    }
    companion object {
        private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
        fun hkdf(shared: ByteArray) = hmac(hmac(byteArrayOf(1, 2, 3), shared), byteArrayOf(1, 2, 3, 1))
        fun ctr(key: ByteArray, iv: ByteArray, b: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(b)
        }
    }
}

/** Never silently splice a gap or duplicate a retransmitted unit into the waveform. */
internal class D3200Sequence {
    private var next: Long? = null
    fun accept(seq: Long): Boolean {
        val expected = next
        if (expected != null && seq < expected) return false
        require(expected == null || seq == expected) { "录音豆音频缺包，需要重新连接" }
        next = seq + 1
        return true
    }
}
