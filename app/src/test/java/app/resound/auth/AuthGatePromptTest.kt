package app.resound.auth

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 授权门禁提示回归：登出/会话失效时取消登录面板，不得让受保护入口静默失效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AuthGatePromptTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun newGate(): Pair<AuthStore, AuthGate> {
        val store = AuthStore(context)
        return store to AuthGate(store, AuthApi({ "" }))
    }

    private fun inject(accountId: String = "", boundAccountId: String = "") {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit()
            .putString("account_id", accountId).putString("bound_account_id", boundAccountId).commit()
    }

    /** 回归：已绑定数据 + 会话失效 + 24h 内取消过面板 → 仍必须再弹登录（原 bug：静默拒绝）。 */
    @Test fun loggedOutBoundDeclineStillPrompts() {
        inject(boundAccountId = "owner-1")
        val (store, gate) = newGate()
        store.bindDeclinedAt = System.currentTimeMillis()
        assertFalse(gate.requireOrPrompt(Capability.RecordAudio) {})
        assertNotNull("登出态必须仍然发出登录请求", gate.loginRequested.value)
    }

    /** 回归：本机数据已绑定 + 登出态 → ownershipIssue 必须为 false——
     *  归属弹层曾借此把登录页挡死（bound!=null 恒真、bindLocalData 又要会话，纯死锁）；
     *  登到异号才真正成立，登回原绑定账号即 AlreadyBound 自然解除。 */
    @Test fun loggedOutBoundIsNotOwnershipIssue() {
        inject(boundAccountId = "owner-1")
        val (_, gate) = newGate()
        assertFalse("登出态必须先放行登录面板", gate.ownershipIssue())
        inject(accountId = "other", boundAccountId = "owner-1")
        val (_, gate2) = newGate()
        assertTrue("登录到异号才构成数据归他号", gate2.ownershipIssue())
    }

    /** 登出态取消登录面板不记归属冷却（冷却只属于"已登录的归属确认"）。 */
    @Test fun loggedOutDismissDoesNotArmCooldown() {
        inject(boundAccountId = "owner-1")
        val (store, gate) = newGate()
        gate.dismissLogin()
        assertEquals(0L, store.bindDeclinedAt)
    }

    /** 已登录的归属确认"暂不"仍享有 24h 冷却（不反复打扰），入口走账号页。 */
    @Test fun loggedInOwnershipDeclineMutes() {
        inject(accountId = "u1")
        val (store, gate) = newGate()
        store.hasLocalData = true
        gate.dismissLogin() // ownershipIssue 成立（已登录+未绑定+有数据+未验机主）
        assertTrue(store.bindDeclinedAt > 0)
        assertFalse(gate.requireOrPrompt(Capability.RecordAudio) {})
        assertNull("冷却期内不再自动弹", gate.loginRequested.value)
    }

    @Test fun ownerVerificationWithoutCompletedBindingOnlyAllowsBackup() {
        inject(accountId = "u1")
        val (store, gate) = newGate()
        store.hasLocalData = true
        store.ownerVerified = true
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("license_payload",
            """{"v":1,"sub":"u1","name":"audit","dev":"${store.deviceId}","caps":["READ_PERSONAL_DATA","EXPORT_BACKUP"],"iat":1,"exp":9999999999}""").commit()
        assertTrue(gate.ownershipIssue())
        assertFalse(gate.allowed(Capability.ReadPersonalData))
        assertTrue(gate.allowed(Capability.ExportBackup))
        store.logout()
        assertFalse(store.ownerVerified)
    }

    @Test fun pendingActionWaitsForBindingAndRunsOnlyOnce() {
        inject(accountId = "u1")
        val (store, gate) = newGate()
        store.hasLocalData = true
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("license_payload",
            """{"v":1,"sub":"u1","name":"audit","dev":"${store.deviceId}","caps":["READ_PERSONAL_DATA"],"iat":1,"exp":9999999999}""").commit()
        var calls = 0
        assertFalse(gate.requireOrPrompt(Capability.ReadPersonalData) { calls++ })
        gate.afterAuthSuccess()
        assertEquals(0, calls)
        assertNotNull(gate.loginRequested.value)
        store.bindCurrent()
        gate.afterAuthSuccess()
        gate.afterAuthSuccess()
        assertEquals(1, calls)
        assertNull(gate.loginRequested.value)
    }

    /**
     * 回归：服务端判会话失效 → 立即主动发登录请求 + 触发系统级通知回调，
     * 不再静默清会话等用户点入口撞墙。SecretStore 依赖 AndroidKeyStore，单测环境
     * 无法注入真 token，故直接驱动 expireSession 验证其提示副作用（401 路径在服务端测试覆盖）。
     */
    @Test fun expiredSessionPromptsLoginImmediately() {
        inject(accountId = "u1")
        val (store, gate) = newGate()
        var notified: String? = null
        gate.onSessionExpired = { notified = it }
        AuthGate::class.java.getDeclaredMethod("expireSession", String::class.java)
            .apply { isAccessible = true }.invoke(gate, "登录授权已过期，请重新登录")
        assertNull("会话必须被清", store.sessionAccountId)
        assertNotNull("失效瞬间必须主动发登录请求", gate.loginRequested.value)
        assertTrue(gate.loginRequested.value?.reason?.contains("重新登录") == true)
        assertNotNull("必须触发系统级通知回调", notified)
    }
}
