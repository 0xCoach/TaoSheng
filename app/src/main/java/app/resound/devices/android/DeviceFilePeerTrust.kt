package app.resound.devices.android

import app.resound.devices.driver.LocalFileNetworkConfig
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate

/** Only the driver-selected private SoftAP client calls this; ordinary backup/HTTP clients do not. */
internal fun verifyDeviceFilePeer(certificate: X509Certificate, config: LocalFileNetworkConfig,
    readPin: () -> String?, savePin: (String) -> Unit) {
    try { certificate.checkValidity() }
    catch (expired: CertificateExpiredException) {
        if (!config.allowExpiredPeerCertificate) throw expired
    }
    // Not-yet-valid certificates are deliberately not covered by the embedded expiry exception.
    val actual=MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    val pin=readPin()
    if(pin!=null && pin!=actual)throw CertificateException("device certificate changed")
    if(pin==null)savePin(actual)
}
