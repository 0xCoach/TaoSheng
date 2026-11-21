package app.resound.auth

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 离线授权凭证验签：服务端 ECDSA P-256 私钥签名（DER），客户端用内置 SPKI DER 公钥验。
 * 与 RemoteDeviceCards 同一算法口径（SHA256withECDSA + DER 签名）。
 */
object LicenseVerifier {
    private var publicKey: ByteArray? = null

    /** AppGraph 装配时注入 assets 里的公钥（hex 或 base64 都行）。 */
    fun install(spkiDer: ByteArray) { publicKey = spkiDer }

    /** 测试或缺公钥时不可用——按"验签不通过"处理，绝不默认放行。 */
    val ready: Boolean get() = publicKey != null

    fun verify(payloadUtf8: ByteArray, signatureB64Der: String): Boolean {
        val key = publicKey ?: return false
        return runCatching {
            val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
            val sig = Base64.getDecoder().decode(signatureB64Der)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(k); update(payloadUtf8); verify(sig)
            }
        }.getOrDefault(false)
    }
}
