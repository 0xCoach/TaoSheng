package app.resound.devices.android

import app.resound.devices.driver.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class JcaCryptoPortTest {
    private val crypto = JcaCryptoPort()
    private fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun rfc5869Case1Hkdf() {
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            crypto.hkdfSha256(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42))
    }
    @Test fun nist80038AF51CtrAllFourBlocks() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
        val encrypted = hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee")
        assertArrayEquals(encrypted, crypto.aesCtr(key, iv, plain))
        assertArrayEquals(plain, crypto.aesCtr(key, iv, encrypted))
    }
    @Test fun nistGcmExample2AndTamperedTag() {
        val key = hex("FEFFE9928665731C6D6A8F9467308308")
        val nonce = hex("CAFEBABEFACEDBADDECAF888")
        val plain = hex("D9313225F88406E5A55909C5AFF5269A86A7A9531534F7DA2E4C303D8A318A721C3C0C95956809532FCF0E2449A6B525B16AEDF5AA0DE657BA637B391AAFD255")
        val sealed = hex("42831EC2217774244B7221B784D0D49CE3AA212F2C02A4E035C17E2329ACA12E21D514B25466931C7D8F6A5AAC84AA051BA30B396A0AAC973D58E091473F59854D5C2AF327CD64A62CF35ABD2BA6FAB4")
        assertArrayEquals(sealed, crypto.aesGcmEncrypt(key, nonce, plain))
        assertArrayEquals(plain, crypto.aesGcmDecrypt(key, nonce, sealed))
        val bad = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(ErrorCode.CryptoFailed, assertThrows(DeviceException::class.java) { crypto.aesGcmDecrypt(key, nonce, bad) }.code)
    }
    @Test fun pythonP256FixedEcdhAndDerSignature() {
        val vector = JSONObject(javaClass.getResource("/devices/hub-p256-python.json")!!.readText())
        fun bytes(key: String) = hex(vector.getString(key))
        val factory = KeyFactory.getInstance("EC")
        val own = JcaCryptoPort.Key(KeyPair(factory.generatePublic(X509EncodedKeySpec(bytes("ownSpki"))),
            factory.generatePrivate(PKCS8EncodedKeySpec(bytes("ownPkcs8")))))
        assertArrayEquals(bytes("ownPoint"), own.publicKey)
        assertArrayEquals(bytes("shared"), crypto.p256Ecdh(own, bytes("peerPoint")))
        assertTrue(crypto.p256VerifyDer(bytes("peerSpki"), bytes("message"), bytes("signature")))
        assertFalse(crypto.p256VerifyDer(bytes("peerSpki"), bytes("message") + 0, bytes("signature")))
        assertEquals(ErrorCode.CryptoFailed, assertThrows(DeviceException::class.java) {
            crypto.p256Ecdh(own, ByteArray(65).also { it[0] = 4 })
        }.code)
        val generated = crypto.p256KeyPair()
        assertEquals(65, generated.publicKey.size)
        assertArrayEquals(crypto.p256Ecdh(generated, own.publicKey), crypto.p256Ecdh(own, generated.publicKey))
    }
}
