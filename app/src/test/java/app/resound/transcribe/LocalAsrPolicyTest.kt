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
        for(url in listOf("http://example.com","https://key@example.com","https://example.com?key=secret"))assertThrows(IllegalArgumentException::class.java){OpenAiEndpoint.bases(url)}
    }
    @Test fun onlyValidatedSocMayUseNpuPackage() {
        assertTrue(LocalModels.supportedSoc("SM8650"));assertFalse(LocalModels.supportedSoc("SM8550"));assertFalse(LocalModels.supportedSoc("unknown"))
    }
}
