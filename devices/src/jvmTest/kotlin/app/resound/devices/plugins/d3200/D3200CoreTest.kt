package app.resound.devices.plugins.d3200

import app.resound.audio.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

class D3200CoreTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun capturedLightVectorsAndChecksum() {
        assertArrayEquals(hex("55aa022c01000400001905003e"), BeanLight.command(false))
        assertArrayEquals(hex("55aa022c0100040001190500b1"), BeanLight.command(true))
        assertArrayEquals(hex("08ee0000001a0f0b00002a"), BeanLight.enableSpp())
        assertEquals(true, BeanLight.receipt(hex("aa55022c010201000057")))
        assertNull(BeanLight.receipt(hex("aa55022c010201000056")))
    }
    @Test fun mixedTransportFramesSurviveSplitsAndBadCrc() {
        val qc = hex("aa55022c010201000057")
        val normal = hex("09ff00000101010a0015")
        val parser = BeanLight.Decoder()
        assertTrue(parser.push(qc.copyOfRange(0, 4)).isEmpty())
        val result = parser.push(qc.copyOfRange(4, 10) + normal)
        assertEquals(2, result.size); assertArrayEquals(qc, result[0]); assertArrayEquals(normal, result[1])
        assertEquals(1, BeanLight.Decoder().push(hex("aa55022c010201000056") + qc).size)
    }
    @Test fun retransmitIsIgnoredAndMissingAudioIsExplicit() {
        val s = D3200Sequence()
        assertTrue(s.accept(41)); assertFalse(s.accept(41)); assertTrue(s.accept(42))
        try { s.accept(44); fail("gap must fail") } catch (_: IllegalArgumentException) {}
    }
    @Test fun twentyMillisecondPacketsBecomeThirtyMillisecondFrames() {
        val f = PcmInputConverter(16000, 1)
        val pcm = ShortArray(960) { it.toShort() }
        assertTrue(f.accept(pcm.copyOfRange(0, 320), 320).isEmpty())
        val first = f.accept(pcm.copyOfRange(320, 640), 320)
        val second = f.accept(pcm.copyOfRange(640, 960), 320)
        assertArrayEquals(pcm.copyOfRange(0, 480), first.single())
        assertArrayEquals(pcm.copyOfRange(480, 960), second.single())
    }
    @Test fun peerHandshakeAndWrappedFileKeyRoundTrip() {
        val app = D3200Crypto(JcaCryptoPort())
        val peer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = peer.public as ECPublicKey
        fun fixed(v: java.math.BigInteger) = v.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val raw = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
        val ap = app.publicKey()
        val point = java.security.spec.ECPoint(java.math.BigInteger(1, ap.copyOfRange(1, 33)), java.math.BigInteger(1, ap.copyOfRange(33, 65)))
        val appPublic = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(point, pub.params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(peer.private); doPhase(appPublic, true); generateSecret() }
        app.handshake(raw + shared)
        val key = ByteArray(32) { (it + 1).toByte() }; val nonce = ByteArray(16) { it.toByte() }
        val sessionNonce = ByteArray(16) { (20 + it).toByte() }
        val wrapped = JcaCryptoPort().aesCtr(JcaCryptoPort().hkdfSha256(shared, byteArrayOf(1,2,3), byteArrayOf(1,2,3), 32), sessionNonce, "soundcored3200".toByteArray() + key)
        app.fileHeader(D3200Protocol.le32(123) + D3200Protocol.le32(160) + nonce + wrapped + sessionNonce + byteArrayOf(0), 123)
        val pcmPacket = ByteArray(160) { it.toByte() }
        val iv = nonce.copyOf(12) + java.nio.ByteBuffer.allocate(4).putInt(70).array()
        val encrypted = JcaCryptoPort().aesCtr(key, iv, pcmPacket)
        assertArrayEquals(pcmPacket, app.decrypt(D3200Protocol.Slice(7, encrypted)))
    }
    @Test fun audioWithoutFileKeyIsNeverForwarded() {
        try { D3200Crypto(JcaCryptoPort()).decrypt(D3200Protocol.Slice(0, ByteArray(160))); fail("missing file key") }
        catch (_: IllegalArgumentException) {}
        try { D3200Protocol.slices(ByteArray(165)); fail("truncated transport unit") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun realtimeTailOffsetSkipsOldRecording() {
        val p = byteArrayOf(1, 0) + D3200Protocol.le32(123) + D3200Protocol.le32(120000) + D3200Protocol.le32(123) + D3200Protocol.le32(120000)
        assertEquals(123, D3200Protocol.currentFile(p).toInt())
        assertEquals(5950L * 166, D3200Connection.liveOffset(p, 123))
        try { D3200Connection.liveOffset(p.copyOf(14), 123); fail("missing tail metadata") } catch (_: IllegalArgumentException) {}
    }
    @Test fun lightControllerWaitsForAckAndReportsTimeout() {
        val s = BeanLightState()
        s.select(BeanLight.Preference("off", "1"))
        assertNotNull(s.outgoing(100)); assertNull(s.outgoing(101)); assertTrue(s.expired(3100))
        s.waiting()
        assertNull(s.acknowledge(hex("aa55022c010201000057"), 3200, 5000))
        assertNotNull(s.outgoing(3300))
        assertEquals(true, s.acknowledge(hex("aa55022c010201000057"), 3400, 6000))
        s.select(BeanLight.Preference("none", "2")); assertNull(s.outgoing(3500))
    }
}
