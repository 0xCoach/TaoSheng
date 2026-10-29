package app.resound.push

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PushErrors060Test {
    private val message = PushMessage("涛声", "测试", null, null, 1)
    private fun send(kind: PushKind, values: Map<String, String>, code: Int = 200, body: String = "", error: IOException? = null): String = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (error != null) throw error
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody()).build()
        }.build()
        PushTransport(client).send(kind, PushFormat.Text, values, message).exceptionOrNull()?.message ?: error("应失败")
    }
    private val webhook = mapOf("url" to "http://receiver.ts.net:8080/private?key=secret-key")

    @Test fun refusedConnectionShowsOnlyHostAndPort() {
        val text = send(PushKind.Webhook, webhook, error = IOException("outer", ConnectException("secret-key")))
        assertEquals("连不上 receiver.ts.net:8080：确认电脑上的接收服务已启动、端口正确；局域网/Tailscale 地址需手机和电脑在同一网络", text)
    }
    @Test fun unknownHostShowsHostWithoutPathOrKey() {
        assertEquals("找不到 receiver.ts.net：检查地址拼写；用 Tailscale 等内网地址时确认手机已连上",
            send(PushKind.Webhook, webhook, error = UnknownHostException("secret-key")))
    }
    @Test fun missingPathExplains404() {
        assertEquals("地址不存在（404）：检查接口路径", send(PushKind.Webhook, webhook, code = 404))
    }
    @Test fun pushPlusReasonIsPreservedAndRedactedBeforeTruncation() {
        val values = mapOf("token" to "test-secret-123")
        assertEquals("PushPlus：token 不存在", send(PushKind.PushPlus, values, body = """{"code":900,"msg":"token 不存在"}"""))
        assertEquals("PushPlus：*** 不存在 请重填", send(PushKind.PushPlus, values,
            body = """{"code":900,"msg":"test-secret-123 不存在\n请重填"}"""))
        val long = send(PushKind.PushPlus, values, body = org.json.JSONObject().put("code", 900)
            .put("msg", "错".repeat(78) + "test-secret-123").toString())
        assertEquals(80, long.substringAfter('：').length)
        assertFalse(long.contains("test"))
        assertEquals("*** receiver.ts.net:8080", PushErrors.clean("secret-key http://receiver.ts.net:8080/private?key=secret-key", webhook))
    }
    @Test fun htmlResponseExplainsWrongEndpoint() {
        assertEquals(PushErrors.INVALID_RESPONSE,
            send(PushKind.PushPlus, mapOf("token" to "test-token"), body = "<html>gateway</html>"))
    }
    @Test fun folder503ExplainsResumption() {
        assertEquals("坚果云暂时限流，稍后会自动续传", send(PushKind.MarkdownFolder,
            mapOf("account" to "test@example.com", "password" to "test-password", "folder" to "涛声"), code = 503))
    }
}
