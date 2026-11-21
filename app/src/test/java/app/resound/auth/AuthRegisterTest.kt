package app.resound.auth

import android.app.Application
import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 自助注册：服务端错误码 → RegisterOutcome 映射（成功端到端在服务端测试覆盖）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AuthRegisterTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun gateWith(httpCode: Int, body: String): AuthGate {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(httpCode).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return AuthGate(AuthStore(context), AuthApi({ "https://t.test" }, client))
    }

    @Test fun usernameTakenMaps() = runBlocking {
        val o = gateWith(409, """{"error":"username taken"}""").register("a_u", "password88", "dev")
        assertTrue(o is AuthGate.RegisterOutcome.UsernameTaken)
    }

    @Test fun rateLimitedMaps() = runBlocking {
        val o = gateWith(429, """{"error":"too many attempts"}""").register("a_u", "password88", "dev")
        assertTrue(o is AuthGate.RegisterOutcome.RateLimited)
    }

    @Test fun invalidMapsServerMessage() = runBlocking {
        val o = gateWith(400, """{"error":"username invalid"}""").register("x", "password88", "dev")
        assertTrue(o is AuthGate.RegisterOutcome.Invalid)
        assertEquals("username invalid", (o as AuthGate.RegisterOutcome.Invalid).msg)
    }
}
