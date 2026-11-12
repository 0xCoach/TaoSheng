package app.resound.transcribe

import app.resound.transcribe.local.LocalModels
import org.junit.Assert.*
import org.junit.Test

class LocalAsrPolicyTest {
    @Test fun rootAndV1ResolveWithoutDuplicateVersion() {
        assertEquals(listOf("https://example.com/v1/audio/transcriptions","https://example.com/audio/transcriptions"),OpenAiEndpoint.transcriptions(" https://example.com/ "))
        assertEquals(listOf("https://example.com/v1/audio/transcriptions"),OpenAiEndpoint.transcriptions("https://example.com/v1/"))
    }
    @Test fun fullEndpointsAndProxyPrefixesArePreserved() {
        assertEquals(listOf("https://example.com/proxy/v1"),OpenAiEndpoint.bases("https://example.com/proxy/v1/audio/transcriptions"))
        assertEquals(listOf("https://example.com/v1"),OpenAiEndpoint.bases("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/proxy/v1",OpenAiEndpoint.bases("https://example.com/proxy").first())
    }
    @Test fun rejectCredentialsAndUnsafeSchemes() {
        // 0.2.13 起允许 http 地址（局域网模型；公网 http 由界面另行提示），只拒绝带密钥或查询参数的地址
        assertEquals("http://example.com/v1", OpenAiEndpoint.bases("http://example.com").first())
        for(url in listOf("https://key@example.com","https://example.com?key=secret"))assertThrows(IllegalArgumentException::class.java){OpenAiEndpoint.bases(url)}
    }
    @Test fun onlyValidatedSocMayUseNpuPackage() {
        assertTrue(LocalModels.supportedSoc("SM8650"));assertFalse(LocalModels.supportedSoc("SM8550"));assertFalse(LocalModels.supportedSoc("unknown"))
    }
}
