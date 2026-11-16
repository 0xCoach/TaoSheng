package app.resound.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException

/** 议题 #46：经 Tailscale 等中继访问的自建服务超时，提示不能只说“本地模型加载慢” */
class WaitTimeout046Test {
    @Test fun timeoutTextCoversSlowNetworkForTailscaleAndPublic() {
        for (endpoint in listOf("http://100.101.102.103:8000/v1", "https://asr.example.com/v1")) {
            val text = modelServiceError(endpoint, SocketTimeoutException("timeout"), "网络连接失败")
            assertEquals(app.resound.net.WAIT_TIMEOUT_TEXT, text)
            assertFalse(text.startsWith("等待超时：本地模型首次加载"))
        }
    }
}
