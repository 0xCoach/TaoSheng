package app.resound.devices.plugins.d3200

// Protocol reference/adaptation: AllDayRec (MIT), tacshi/Soundcore (MIT).
// AllDayRec credits ws/soundcore (0BSD) and open-feishu-recording-bean (MIT).
// Copyright notices and scope: vendor/d3200/THIRD_PARTY_NOTICES.md; bundled in assets/licenses/d3200/.

import app.resound.devices.driver.CryptoPort

/** 私有 BLE 协议；长度及序号小端，音频每 166 字节含 4 字节序号、1 字节标志、160 字节密文、1 字节尾部。 */
internal object D3200Protocol {
    const val SERVICE = "020cf5da-0000-1000-8000-00805f9b34fb"
    const val WRITE = "00007777-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "00008888-0000-1000-8000-00805f9b34fb"
    const val TRANSPORT_PACKET_BYTES = 166
    const val AUDIO_PACKET_BYTES = 160
    private const val HEADER_BYTES = 9
    private val commands = setOf(0x0101, 0x2e01, 0x1a0e, 0x1b0e, 0x1a07, 0x1882, 0x1a05, 0x1a02, 0x1a10)

    fun u32(b: ByteArray, at: Int): Long = (0..3).fold(0L) { n, i -> n or ((b[at+i].toLong() and 255) shl (8*i)) }
    fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
    fun le32(n: Long) = ByteArray(4) { (n ushr (8*it)).toByte() }

    fun frame(type: Int, id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require((type shl 8 or id) in commands)
        if (type == 0x18) require(payload.size == 1 && payload[0].toInt() in 1..2)
        if (type == 0x1a && id == 0x10) require(payload.size == 4 && u32(payload, 0) != 0L)
        if (type == 0x1a && id == 0x02) require(payload.isEmpty())
        val bytes = envelope(type, id, payload, checksum = true)
        var sum = 0
        for (i in 0 until bytes.lastIndex) sum += bytes[i].toInt() and 0xff
        bytes[bytes.lastIndex] = sum.toByte()
        return bytes
    }

    private fun envelope(type: Int, id: Int, payload: ByteArray, checksum: Boolean): ByteArray {
        val length = HEADER_BYTES + payload.size + if (checksum) 1 else 0
        return ByteArray(length).apply {
            this[0] = 8
            this[1] = 0xee.toByte()
            this[5] = type.toByte()
            this[6] = id.toByte()
            this[7] = length.toByte()
            this[8] = (length ushr 8).toByte()
            payload.copyInto(this, HEADER_BYTES)
        }
    }

    private fun fileRead(file: Long, byteOffset: Long, mode: Byte): ByteArray =
        ByteArray(9).also {
            le32(byteOffset).copyInto(it)
            le32(file).copyInto(it, 4)
            it[8] = mode
        }

    fun request(file: Long, offset: Long) = frame(0x1a, 7, fileRead(file, offset, 1))

    fun offlineRequest(file: Long, packet: Long) =
        frame(0x1a, 7, fileRead(file, packet * TRANSPORT_PACKET_BYTES, 0))

    fun wifiConfig(ssid: String, password: String): ByteArray {
        val network = ssid.encodeToByteArray()
        val secret = password.encodeToByteArray()
        require(network.size in 1..32 && secret.size in 8..63)
        val fields = ByteArray(2 + network.size + secret.size)
        fields[0] = network.size.toByte()
        network.copyInto(fields, 1)
        fields[network.size + 1] = secret.size.toByte()
        secret.copyInto(fields, network.size + 2)
        return frame(0x1a, 5, fields)
    }

    fun wifiEndpoint(payload: ByteArray): Pair<String, Int> {
        require(payload.size >= 6)
        val port = u16(payload, 4)
        require(port > 0)
        val ip = (3 downTo 0).joinToString(".") { (payload[it].toInt() and 255).toString() }
        return ip to port
    }

    fun wifiRequest(file: Long, packet: Long): String {
        require(file in 1..0xffffffffL && packet in 0..0xffffffffL / TRANSPORT_PACKET_BYTES)
        val bytes = envelope(0x1a, 7, fileRead(file, packet * TRANSPORT_PACKET_BYTES, 0x1b), checksum = false)
        return bytes.toHexString()
    }
    fun listPage(page: Int) = frame(0x1b,0x0e,byteArrayOf(page.toByte(),(page ushr 8).toByte()))
    fun currentFile(p: ByteArray): Long { require(p.size>=2); val at=2+u16(p,0)*8; require(at+4<=p.size); return u32(p,at) }
    fun payload(b: ByteArray) = b.copyOfRange(HEADER_BYTES, b.lastIndex)
    fun command(b: ByteArray) = ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
    fun headerMatches(p: ByteArray, file: Long) = p.size>=4 && u32(p,0)==file
    data class Slice(val seq: Long,val data: ByteArray)
    fun slices(payload: ByteArray): List<Slice> {
        require(payload.size % TRANSPORT_PACKET_BYTES == 0) { "音频分片长度异常" }
        return List(payload.size / TRANSPORT_PACKET_BYTES) { index ->
            val start = index * TRANSPORT_PACKET_BYTES
            val audio = start + 5
            Slice(u32(payload, start), payload.copyOfRange(audio, audio + AUDIO_PACKET_BYTES))
        }
    }
}

/** P-256 未压缩公钥及 ECDH X 坐标；HKDF salt/info 均为 01 02 03；CTR 最后 4 字节为大端序号×10。 */
internal class D3200Crypto(private val crypto: CryptoPort) {
    private class FileCipher(val key: ByteArray, val counterPrefix: ByteArray)

    private val keyPair = crypto.p256KeyPair()
    private var sessionKey: ByteArray? = null
    private var currentFile: FileCipher? = null

    fun publicKey() = keyPair.publicKey

    fun handshake(reply: ByteArray) {
        val publicKeySize = 65
        val secretBytes = 32
        require(reply.size >= publicKeySize + secretBytes && reply[0] == 4.toByte()) { "密钥交换响应不完整" }
        val secret = crypto.p256Ecdh(keyPair, reply.copyOf(publicKeySize))
        require(secret.size == secretBytes) { "密钥交换校验失败" }
        var difference = 0
        for (i in secret.indices) difference = difference or (secret[i].toInt() xor reply[publicKeySize + i].toInt())
        require(difference == 0) { "密钥交换校验失败" }
        sessionKey = crypto.hkdfSha256(secret, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3), secretBytes)
    }

    fun fileHeader(header: ByteArray, expected: Long) {
        require(header.size >= 87 && D3200Protocol.headerMatches(header, expected)) { "音频文件头不匹配" }
        val status = header[86].toInt() and 255
        require(status == 0 || status == 255) { "录音豆拒绝读取文件" }
        val wrapped = header.copyOfRange(24, 70)
        val iv = header.copyOfRange(70, 86)
        val plain = crypto.aesCtr(requireNotNull(sessionKey), iv, wrapped)
        val marker = "soundcored3200".encodeToByteArray()
        require(plain.size == marker.size + 32 && marker.indices.all { plain[it] == marker[it] }) { "音频密钥解封失败" }
        currentFile = FileCipher(plain.copyOfRange(marker.size, plain.size), header.copyOfRange(8, 20))
    }

    fun decrypt(slice: D3200Protocol.Slice): ByteArray {
        require(slice.seq in 0..429_496_729L) { "音频序号超出计数范围" }
        val cipher = requireNotNull(currentFile)
        val counter = slice.seq * 10
        val iv = cipher.counterPrefix.copyOf(16)
        for (i in 0..3) iv[15 - i] = (counter ushr (i * 8)).toByte()
        return crypto.aesCtr(cipher.key, iv, slice.data)
    }
}

internal class D3200Sequence {
    private var next: Long?=null
    fun accept(seq: Long): Boolean {
        val expected=next
        if (expected!=null && seq<expected) return false
        require(expected==null || seq==expected) { "录音豆音频缺包，需要重新连接" }
        next=seq+1
        return true
    }
}
