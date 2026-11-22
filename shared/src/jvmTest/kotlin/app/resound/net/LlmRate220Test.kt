package app.resound.net

import org.junit.Assert.*
import org.junit.Test

/** 卡 220：429 带出服务商自己的说明（通用规则，不按服务商特判） */
class LlmRate220Test {
    @Test fun providerMessageIsShown() {
        val e = NetLlmClient.httpError(429, raw = """{"error":{"code":"1302","message":"您当前使用该API的并发数过高，请降低并发"}}""")
        assertEquals(NetLlmError.RateLimit, e.type); assertTrue(e.detail!!.contains("并发数过高"))
    }

    @Test fun arrearsIsQuotaAndPlainBodyKeepsOldBehavior() {
        val e = NetLlmClient.httpError(429, raw = """{"error":{"message":"You exceeded your current quota"}}""")
        assertEquals(NetLlmError.Quota, e.type)
        val plain = NetLlmClient.httpError(429, raw = "{}")
        assertEquals(NetLlmError.RateLimit, plain.type); assertNull(plain.detail)
    }
}
