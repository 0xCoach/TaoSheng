package app.resound.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** 离线凭证验签：与服务端（WebCrypto ECDSA P-256 DER）同口径——SPKI DER 公钥 + SHA256withECDSA。 */
class LicenseVerifierTest {
    private fun keypair(): java.security.KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(priv: java.security.PrivateKey, data: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(priv); update(data); sign()
        })

    @Test fun validSignatureVerifies() {
        val kp = keypair()
        LicenseVerifier.install(kp.public.encoded)
        val payload = """{"v":1,"sub":"u1","name":"a","dev":"d1","caps":["RECORD_AUDIO"],"iat":1,"exp":999999}"""
        assertTrue(LicenseVerifier.verify(payload.toByteArray(), sign(kp.private, payload.toByteArray())))
    }

    @Test fun tamperedPayloadRejected() {
        val kp = keypair()
        LicenseVerifier.install(kp.public.encoded)
        val payload = """{"v":1,"sub":"u1","name":"a","dev":"d1","caps":["RECORD_AUDIO"],"iat":1,"exp":999999}"""
        val sig = sign(kp.private, payload.toByteArray())
        val forged = payload.replace("u1", "u2")
        assertFalse(LicenseVerifier.verify(forged.toByteArray(), sig))
    }

    @Test fun wrongKeyRejected() {
        LicenseVerifier.install(keypair().public.encoded)
        val other = keypair()
        val payload = """{"v":1,"sub":"u1","name":"a","dev":"d1","caps":[],"iat":1,"exp":2}"""
        assertFalse(LicenseVerifier.verify(payload.toByteArray(), sign(other.private, payload.toByteArray())))
    }

    @Test fun garbageSignatureRejected() {
        val kp = keypair()
        LicenseVerifier.install(kp.public.encoded)
        assertFalse(LicenseVerifier.verify("{}".toByteArray(), "not-base64!!!"))
        assertFalse(LicenseVerifier.verify("{}".toByteArray(), ""))
    }
}
