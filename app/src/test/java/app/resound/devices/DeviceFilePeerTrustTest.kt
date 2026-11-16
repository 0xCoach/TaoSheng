package app.resound.devices

import app.resound.devices.android.verifyDeviceFilePeer
import app.resound.devices.driver.LocalFileNetworkConfig
import org.junit.Test
import org.junit.Assert.*
import java.security.cert.*

class DeviceFilePeerTrustTest {
    private fun certificate(name: String) = javaClass.classLoader!!.getResourceAsStream("device-file-peer/$name.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun config(allow: Boolean) = LocalFileNetworkConfig("test-device","test-password","192.168.4.1",443,32003,
        byteArrayOf(),allowExpiredPeerCertificate=allow)
    @Test fun expiredEmbeddedPeerRequiresExplicitProfileAndRemainsPinned() {
        val peer=certificate("expired");var pin: String?=null
        assertThrows(CertificateExpiredException::class.java) { verifyDeviceFilePeer(peer,config(false),{pin},{pin=it}) }
        assertNull(pin)
        verifyDeviceFilePeer(peer,config(true),{pin},{pin=it})
        assertEquals(64,pin!!.length)
        verifyDeviceFilePeer(peer,config(true),{pin},{fail("already pinned")})
        assertThrows(CertificateException::class.java) { verifyDeviceFilePeer(peer,config(true),{"0".repeat(64)},{fail("pin changed")}) }
    }
    @Test fun futureCertificateCannotEnrollEvenWithEmbeddedExpiryCompatibility() {
        assertThrows(CertificateNotYetValidException::class.java) {
            verifyDeviceFilePeer(certificate("future"),config(true),{null},{fail("future certificate enrolled")})
        }
    }
}
