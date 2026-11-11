package app.resound.devices.plugins.d3200

import app.resound.devices.driver.CryptoPort

/** 私有 BLE 协议；长度及序号小端，音频每 166 字节含 4 字节序号、1 字节标志、160 字节密文、1 字节尾部。 */
internal object D3200Protocol {
    const val SERVICE = "020cf5da-0000-1000-8000-00805f9b34fb"
    const val WRITE = "00007777-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "00008888-0000-1000-8000-00805f9b34fb"
    fun u32(b: ByteArray, at: Int): Long = (0..3).fold(0L) { n, i -> n or ((b[at+i].toLong() and 255) shl (8*i)) }
    fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
    fun le32(n: Long) = ByteArray(4) { (n ushr (8*it)).toByte() }
    fun frame(type: Int, id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require((type shl 8 or id) in setOf(0x0101,0x2e01,0x1a0e,0x1b0e,0x1a07,0x1882))
        if (type == 0x18) require(payload.contentEquals(byteArrayOf(1)) || payload.contentEquals(byteArrayOf(2)))
        val b = byteArrayOf(8,0xee.toByte(),0,0,0,type.toByte(),id.toByte(),0,0) + payload + byteArrayOf(0)
        b[7]=b.size.toByte(); b[8]=(b.size ushr 8).toByte()
        b[b.lastIndex]=b.dropLast(1).sumOf { it.toInt() and 255 }.toByte()
        return b
    }
    fun request(file: Long, offset: Long) = frame(0x1a,7,le32(offset)+le32(file)+byteArrayOf(1))
    fun offlineRequest(file: Long, packet: Long) = frame(0x1a,7,le32(packet*166)+le32(file)+byteArrayOf(0))
    fun listPage(page: Int) = frame(0x1b,0x0e,byteArrayOf(page.toByte(),(page ushr 8).toByte()))
    fun currentFile(p: ByteArray): Long { require(p.size>=2); val at=2+u16(p,0)*8; require(at+4<=p.size); return u32(p,at) }
    fun payload(b: ByteArray) = b.copyOfRange(9,b.size-1)
    fun command(b: ByteArray) = ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
    fun headerMatches(p: ByteArray, file: Long) = p.size>=4 && u32(p,0)==file
    data class Slice(val seq: Long,val data: ByteArray)
    fun slices(p: ByteArray): List<Slice> {
        require(p.size%166==0) { "音频分片长度异常" }
        return (p.indices step 166).map { Slice(u32(p,it),p.copyOfRange(it+5,it+165)) }
    }
}

/** P-256 未压缩公钥及 ECDH X 坐标；HKDF salt/info 均为 01 02 03；CTR 最后 4 字节为大端序号×10。 */
internal class D3200Crypto(private val crypto: CryptoPort) {
    private val mine=crypto.p256KeyPair()
    private var session: ByteArray?=null
    private var fileKey: ByteArray?=null
    private var nonce: ByteArray?=null
    fun publicKey()=mine.publicKey
    fun handshake(p: ByteArray) {
        require(p.size>=97 && p[0]==4.toByte()) { "密钥交换响应不完整" }
        val shared=crypto.p256Ecdh(mine,p.copyOfRange(0,65))
        val expected=p.copyOfRange(65,97)
        require(shared.size==32 && shared.indices.fold(0) { v,i -> v or (shared[i].toInt() xor expected[i].toInt()) }==0) { "密钥交换校验失败" }
        session=crypto.hkdfSha256(shared,byteArrayOf(1,2,3),byteArrayOf(1,2,3),32)
    }
    fun fileHeader(p: ByteArray,expected: Long) {
        require(p.size>=87 && D3200Protocol.headerMatches(p,expected)) { "音频文件头不匹配" }
        require((p[86].toInt() and 255) in setOf(0,255)) { "录音豆拒绝读取文件" }
        val plain=crypto.aesCtr(requireNotNull(session),p.copyOfRange(70,86),p.copyOfRange(24,70))
        val magic="soundcored3200".encodeToByteArray()
        require(plain.size==magic.size+32 && plain.copyOfRange(0,magic.size).contentEquals(magic)) { "音频密钥解封失败" }
        fileKey=plain.copyOfRange(magic.size,plain.size); nonce=p.copyOfRange(8,24)
    }
    fun decrypt(slice: D3200Protocol.Slice): ByteArray {
        require(slice.seq in 0..429_496_729L) { "音频序号超出计数范围" }
        val counter=slice.seq*10
        val iv=requireNotNull(nonce).copyOf(12)+ByteArray(4) { (counter ushr (8*(3-it))).toByte() }
        return crypto.aesCtr(requireNotNull(fileKey) { "尚未取得音频密钥" },iv,slice.data)
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
