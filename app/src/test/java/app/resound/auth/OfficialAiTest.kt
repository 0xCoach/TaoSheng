package app.resound.auth

import android.app.Application
import android.content.Context
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import app.resound.digest.officialTaskKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R6.2 官方 AI：任务类别映射、就绪门、错误终态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class OfficialAiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun injectAccount(accountId: String) {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit()
            .putString("account_id", accountId).commit()
    }

    @Test fun taskKindMapping() {
        assertEquals("digest", officialTaskKind("整理"))
        assertEquals("manual_todo", officialTaskKind("手动待办"))
        assertEquals("ask", officialTaskKind("记忆问答"))
        assertEquals("analysis", officialTaskKind("分析"))
        assertEquals("generic", officialTaskKind("寻物"))
    }

    @Test fun readyGate() {
        val store = AuthStore(context)
        val ai = OfficialAi(context, AuthGate(store, AuthApi({ "" })), store)
        // 未刷新状态 → 不就绪
        assertFalse(ai.ready())
    }

    @Test fun readyRequiresLogin() {
        injectAccount("")
        val store = AuthStore(context)
        val ai = OfficialAi(context, AuthGate(store, AuthApi({ "" })), store)
        val f = OfficialAi::class.java.getDeclaredField("status").apply { isAccessible = true }
        f.set(ai, OfficialAi.OfficialStatus(enabled = true, configured = true, consented = true, plan = "free", remaining = 100L))
        assertFalse(ai.ready()) // 未登录不就绪
        injectAccount("u1")
        assertTrue(ai.ready())
    }

    @Test fun terminalDefaultsFalse() {
        assertFalse(LlmException(LlmError.Quota).terminal)
        assertTrue(LlmException(LlmError.Quota, terminal = true).terminal)
    }
}
