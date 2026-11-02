package app.resound.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.transcribe.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅对内置合成测试音频运行，不写转写数据库、不改用户设置、不上传音频。 */
@RunWith(AndroidJUnit4::class)
class LocalLanguage093DeviceTest {
    @Test fun cpuAndNpuDecodeWithAutomaticAndChineseLanguage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "language-093-synthetic.ogg")
        context.assets.open("asr-test.ogg").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val connection = LocalAsrConnection.get(context)
            for (backend in LocalBackend.entries) {
                assertTrue("Model must already be installed: $backend", LocalModels.ready(context, LocalModels.spec(backend)))
                for (language in listOf(LocalAsrLanguage.Auto, LocalAsrLanguage.Chinese)) {
                    val result = connection.recognize(AsrAudio(file, "ogg", 3007, separateSpeakers = false), backend, language) {}
                    assertTrue(result.sentences.any { it.text.isNotBlank() })
                    val metrics = requireNotNull(connection.lastMetrics)
                    assertEquals(language.code, metrics.language)
                    assertTrue(metrics.detectedLanguages.any { it.contains("zh") })
                    android.util.Log.i("LocalLanguage093", "backend=$backend requested=${metrics.language} detected=${metrics.detectedLanguages} loads=${metrics.modelLoads} chars=${result.sentences.sumOf { it.text.length }}")
                }
            }
        } finally { file.delete() }
    }
}
