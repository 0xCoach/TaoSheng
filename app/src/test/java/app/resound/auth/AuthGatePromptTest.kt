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
}
