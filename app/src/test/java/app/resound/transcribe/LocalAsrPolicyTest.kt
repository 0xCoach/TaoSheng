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
    @Test fun eachSnapdragonGetsItsOwnNpuModel() {
        for (soc in listOf("SM8450","SM8475","SM8550","SM8650","SM8750","SM8850")) assertTrue(soc, LocalModels.supportedSoc(soc))
        // 系统报的型号带后缀也要认出来（#64 荣耀 Magic V5 是 SM8750）
        assertEquals("sense-sm8750-v1", app.resound.transcribe.local.NpuModels.forSoc("sm8750-ac")!!.spec().id)
        // 已下载的 SM8650 模型目录名不能变，否则老用户要重下
        assertEquals("sense-sm8650-v1", app.resound.transcribe.local.NpuModels.forSoc("SM8650")!!.spec().id)
        for (soc in listOf("SM8350","SM8635","MT6989","unknown","")) assertFalse(soc, LocalModels.supportedSoc(soc))
    }
    @Test fun npuModelsAreDistinctAndBundleTheirHtpLibs() {
        val all = app.resound.transcribe.local.NpuModels.all
        assertEquals(all.size, all.map { it.modelHash }.toSet().size)
        val libs = java.io.File("src/main/jniLibs/arm64-v8a")
        for (m in all) for (kind in listOf("Stub","Skel"))
            assertTrue("${m.soc} 缺 libQnnHtp${m.htp}$kind.so", java.io.File(libs, "libQnnHtp${m.htp}$kind.so").isFile)
    }
}
