package app.resound.transcribe

import android.app.Application
import android.os.Looper
import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.ModelSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class LocalStatus229Test {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun upgradeIgnoresOldStatusAndCpuKeepsTranscriptionReady() {
        ShadowBuild.setSystemOnChipModel("SM8850")
        val prefs = context.getSharedPreferences("local-asr", 0)
        prefs.edit().putString("actual", "您的手机暂不支持 NPU 识别，已使用 CPU 识别")
            .putString("download-Npu", "下载 12%").commit()
        installModel(LocalModels.cpu)

        val settings = AndroidTranscribeSettings(context)
        assertTrue(LocalModels.npuSupported())
        assertEquals(LocalBackend.Npu, settings.backend.value)
        assertTrue(settings.ready.value)
        assertTrue(settings.ready(TranscribeSource.Local))
        assertFalse(prefs.contains("actual"))
        assertEquals("NPU 模型还没下载好，先用 CPU 识别", settings.localStatus.value)

        val request = Any()
        settings.setLocalStatus(request, "正在识别 1/2")
        installModel(LocalModels.npu)
        prefs.edit().putString("download-Npu", "已下载").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(settings.localFacts().npuReady)
        assertEquals("正在识别 1/2", settings.localStatus.value)
        assertFalse(prefs.contains("actual"))
        settings.clearLocalStatus(request)

        assertEquals("已选 NPU 识别，下次转写时先试用", settings.localStatus.value)
        settings.recordNpuCheck(LocalModels.npuCheckKey(context), true)
        assertEquals("正在使用：NPU 识别", settings.localStatus.value)
        settings.setBackend(LocalBackend.Cpu)
        assertEquals("正在使用：CPU 识别", settings.localStatus.value)
        assertFalse(prefs.contains("npuCheck"))
    }

    private fun installModel(spec: ModelSpec) {
        val dir = LocalModels.directory(context, spec).apply { mkdirs() }
        RandomAccessFile(File(dir, spec.modelName), "rw").use { it.setLength(spec.modelBytes) }
        File(dir, "tokens.txt").writeText("test")
        File(dir, "verified").writeText(spec.modelHash)
    }
}
