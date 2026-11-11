package app.resound.devices.plugins.d3200

import app.resound.devices.driver.*
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.*

/** JCA 编码边界：P-256 公钥为 04||X||Y，验签 SPKI/DER，GCM 固定 128 位标签。 */
internal class JcaCryptoPort : CryptoPort {
    private val random = SecureRandom()
    private val curve = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    internal class Key(val pair: KeyPair) : P256KeyPair {
        override val publicKey: ByteArray get() {
            val point = (pair.public as ECPublicKey).w
            fun coordinate(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let {
                ByteArray(32 - it.size) + it
            }
            return byteArrayOf(4) + coordinate(point.affineX) + coordinate(point.affineY)
        }
    }
    private inline fun <T> crypto(block: () -> T): T = try { block() }
        catch (e: DeviceException) { throw e }
        catch (e: Exception) { throw DeviceException(ErrorCode.CryptoFailed, cause = e) }
    override fun randomBytes(size: Int) = crypto { require(size >= 0); ByteArray(size).also(random::nextBytes) }
    override fun sha256(data: ByteArray) = crypto { MessageDigest.getInstance("SHA-256").digest(data) }
    override fun hmacSha256(key: ByteArray, data: ByteArray) = crypto {
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256")); doFinal(data)
        }
    }
    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) = crypto {
        require(length in 0..(255 * 32))
        val prk = hmacSha256(salt, ikm)
        try {
            val result = ByteArray(length)
            var previous = byteArrayOf()
            var offset = 0
            var counter = 1
            while (offset < length) {
                val next = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
                previous.fill(0); previous = next
                val count = minOf(32, length - offset)
                previous.copyInto(result, offset, 0, count); offset += count; counter++
            }
            previous.fill(0)
            result
        } finally { prk.fill(0) }
    }
    override fun p256KeyPair(): P256KeyPair = crypto {
        Key(KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1"), random); generateKeyPair() })
    }
    override fun p256Ecdh(own: P256KeyPair, peerPublic: ByteArray) = crypto {
        require(own is Key && peerPublic.size == 65 && peerPublic[0] == 4.toByte())
        val point = ECPoint(BigInteger(1, peerPublic.copyOfRange(1, 33)), BigInteger(1, peerPublic.copyOfRange(33, 65)))
        val p = (curve.curve.field as ECFieldFp).p
        val x = point.affineX; val y = point.affineY
        require(x < p && y < p && y.modPow(BigInteger.valueOf(2), p) ==
            (x.modPow(BigInteger.valueOf(3), p) + curve.curve.a * x + curve.curve.b).mod(p))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
        KeyAgreement.getInstance("ECDH").run {
            init(own.pair.private); doPhase(peer, true); generateSecret().also { require(it.size == 32) }
        }
    }
    override fun p256VerifyDer(spkiPublicKey: ByteArray, data: ByteArray, derSignature: ByteArray): Boolean = crypto {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiPublicKey)) as ECPublicKey
        require(key.params.order == curve.order && key.params.generator == curve.generator && key.params.curve == curve.curve)
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(derSignature) }
    }
    override fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray) = crypto {
        require(iv.size == 16)
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
        }
    }
    override fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray) = gcm(Cipher.ENCRYPT_MODE, key, nonce, plain, aad)
    override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = gcm(Cipher.DECRYPT_MODE, key, nonce, sealed, aad)
    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray) = crypto {
        require(nonce.isNotEmpty())
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(data)
        }
    }
}
