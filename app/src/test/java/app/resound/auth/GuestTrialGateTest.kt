package app.resound.auth

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 游客试用门：未登录+未绑定 = 免费试用（录音 GUEST_TRIAL_MS 内放行），耗尽必须注册授权。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class GuestTrialGateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun newGate(): Pair<AuthStore, AuthGate> {
        val store = AuthStore(context)
        return store to AuthGate(store, AuthApi({ "" }))
    }

    /** 直接写 prefs 注入会话/绑定（saveLogin 走 AndroidKeyStore，Robolectric 下不可用）。 */
    private fun injectAuthPrefs(accountId: String = "", boundAccountId: String = "", licenseJson: String = "") {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit()
            .putString("account_id", accountId).putString("bound_account_id", boundAccountId)
            .putString("license_payload", licenseJson).commit()
    }
    private fun licenseJson(sub: String, dev: String) =
        """{"v":1,"sub":"$sub","name":"a","dev":"$dev","caps":["RECORD_AUDIO"],"iat":1,"exp":9999999999}"""

    @Test fun guestWithinTrialAllowed() {
        val (store, gate) = newGate()
        store.guestTrialUsedMs = AuthGate.GUEST_TRIAL_MS - 1
        val d = gate.require(Capability.RecordAudio)
        assertTrue(d.allowed); assertEquals(AuthStatus.Authorized, d.status)
    }

    @Test fun guestTrialExhaustedDenied() {
        val (store, gate) = newGate()
        store.guestTrialUsedMs = AuthGate.GUEST_TRIAL_MS
        val d = gate.require(Capability.RecordAudio)
        assertFalse(d.allowed); assertEquals(AuthStatus.Guest, d.status)
        assertTrue(d.reason.contains("试用"))
    }

    @Test fun guestWithLocalDataNoLongerOwnerPass() {
        // 旧语义"本机所有者永久放行"已废弃：有数据未绑定的游客同样只享试用额度
        val (store, gate) = newGate()
        store.hasLocalData = true; store.guestTrialUsedMs = AuthGate.GUEST_TRIAL_MS + 1
        assertFalse(gate.require(Capability.RecordAudio).allowed)
    }

    @Test fun boundToOtherGuestStillDenied() {
        val (store, gate) = newGate()
        injectAuthPrefs(boundAccountId = "acct-a") // 已归他号 + 游客 → decide 层拒绝
        val d = gate.require(Capability.RecordAudio)
        assertFalse(d.allowed); assertEquals(AuthStatus.Guest, d.status)
    }

    @Test fun trialCounterNotResetByLogout() {
        val (store, gate) = newGate()
        injectAuthPrefs(accountId = "acct-a", licenseJson = licenseJson("acct-a", store.deviceId))
        store.guestTrialUsedMs = 123_000
        store.logout()
        assertEquals(123_000L, store.guestTrialUsedMs)
    }

    @Test fun loggedInWithValidLicenseAllowed() {
        val (store, gate) = newGate()
        injectAuthPrefs(accountId = "acct-a", licenseJson = licenseJson("acct-a", store.deviceId))
        assertTrue(gate.require(Capability.RecordAudio).allowed)
    }
}
