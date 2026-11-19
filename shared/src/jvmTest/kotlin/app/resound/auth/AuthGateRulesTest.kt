package app.resound.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁判定矩阵：游客/凭证有效/过期/串设备/串账号/能力缺口/绑定冲突——全部拒绝路径必须有据。 */
class AuthGateRulesTest {
    private val lic = LicensePayload(v = 1, sub = "u1", name = "a", dev = "dev-1",
        caps = listOf("RECORD_AUDIO", "READ_PERSONAL_DATA"), iat = 100, exp = 800)

    @Test fun guestDenied() {
        val d = AuthGateRules.decide(Capability.RecordAudio, null, null, null, "dev-1", 500)
        assertFalse(d.allowed); assertEquals(AuthStatus.Guest, d.status)
    }

    @Test fun validLicenseAllows() {
        val d = AuthGateRules.decide(Capability.RecordAudio, lic, "u1", "u1", "dev-1", 500)
        assertTrue(d.allowed); assertEquals(AuthStatus.Authorized, d.status)
    }

    @Test fun expiredLicenseDenied() {
        val d = AuthGateRules.decide(Capability.RecordAudio, lic, "u1", "u1", "dev-1", 900)
        assertFalse(d.allowed); assertEquals(AuthStatus.Expired, d.status)
    }

    @Test fun wrongDeviceDenied() {
        val d = AuthGateRules.decide(Capability.RecordAudio, lic, "u1", "u1", "dev-OTHER", 500)
        assertFalse(d.allowed)
    }

    @Test fun licenseAccountMismatchDenied() {
        val d = AuthGateRules.decide(Capability.RecordAudio, lic, null, "u2", "dev-1", 500)
        assertFalse(d.allowed)
    }

    @Test fun missingCapabilityDenied() {
        val d = AuthGateRules.decide(Capability.McpAccess, lic, "u1", "u1", "dev-1", 500)
        assertFalse(d.allowed); assertEquals(AuthStatus.Authenticated, d.status)
    }

    @Test fun crossAccountBindingDenied() {
        // 本机数据已绑 u1，换 u2 登录——绝不能放行（个人数据隔离 P0）。
        val d = AuthGateRules.decide(Capability.ReadPersonalData, lic.copy(sub = "u2"), "u1", "u2", "dev-1", 500)
        assertFalse(d.allowed); assertEquals(AuthStatus.Disabled, d.status)
    }

    @Test fun loggedInWithoutLicenseIsExpired() {
        val d = AuthGateRules.decide(Capability.RecordAudio, null, "u1", "u1", "dev-1", 500)
        assertFalse(d.allowed); assertEquals(AuthStatus.Expired, d.status)
    }

    @Test fun displayStatusCoversAll() {
        assertEquals(AuthStatus.Guest, AuthGateRules.displayStatus(null, null, 500))
        assertEquals(AuthStatus.Authenticated, AuthGateRules.displayStatus(null, "u1", 500))
        assertEquals(AuthStatus.Authorized, AuthGateRules.displayStatus(lic, "u1", 500))
        assertEquals(AuthStatus.Expired, AuthGateRules.displayStatus(lic, "u1", 900))
    }
}
