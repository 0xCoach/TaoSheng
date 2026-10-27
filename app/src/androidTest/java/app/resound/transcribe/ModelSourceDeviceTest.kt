package app.resound.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.resound.transcribe.local.*
import app.resound.voiceprint.LocalSpeakerDiarizer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅独立测试包使用；真实匿名国内源下载，随后让正式 Worker 校验、解压、安装。 */
@RunWith(AndroidJUnit4::class)
class ModelSourceDeviceTest {
    @Test fun domesticDownloadsAndLocalRecognition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        // 厂商后台限速会让解压耗时失真；模拟用户打开 App 下载的前台场景。
        context.startActivity(
            android.content.Intent(context, app.resound.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1500)
        val results = JSONArray()
        withTimeout(420_000) {
            for (spec in listOf(LocalModels.cpu, LocalModels.npu, LocalModels.speakers)) {
                assertFalse("Must start without installed models", LocalModels.ready(context, spec))
                val start = android.os.SystemClock.elapsedRealtime()
                val archive = File(context.noBackupFilesDir, "${spec.id}.part")
                // 禁用备用源，证明当前手机确实能从魔搭匿名获取完整文件。
                withContext(Dispatchers.IO) {
                    downloadModelFile(spec.copy(fallbackUrls = emptyList()), archive,
                        status = { android.util.Log.i("ModelSourceTest", "${spec.id}: $it") })
                }
                assertEquals(spec.archiveHash, LocalModels.hash(archive))
                val workName = when (spec) {
                    LocalModels.cpu -> LocalModels.workName(LocalBackend.Cpu).also { LocalModels.enqueue(context, LocalBackend.Cpu, retry = true) }
                    LocalModels.npu -> LocalModels.workName(LocalBackend.Npu).also { LocalModels.enqueue(context, LocalBackend.Npu, retry = true) }
                    else -> LocalModels.SPEAKERS_WORK.also { LocalModels.enqueueSpeakers(context, retry = true) }
                }
                withTimeout(180_000) {
                    while (!LocalModels.ready(context, spec)) {
                        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
                        assertFalse("Model worker failed", infos.any { it.state == androidx.work.WorkInfo.State.FAILED })
                        delay(500)
                    }
                }
                val file = File(LocalModels.directory(context, spec), spec.modelName)
                assertEquals(spec.modelHash, LocalModels.hash(file))
                results.put(JSONObject().put("model", spec.id).put("bytes", spec.archiveBytes)
                    .put("seconds", (android.os.SystemClock.elapsedRealtime() - start) / 1000.0))
                File(context.getExternalFilesDir(null), "modelscope-device.json").writeText(results.toString(2))
            }
            LocalAsrDeviceTest().cpuNpuFallbackAndExistingVoiceprint()
            val audio = File(context.cacheDir, "modelscope-speaker-test.ogg")
            try {
                context.assets.open("asr-test.ogg").use { input -> audio.outputStream().use { input.copyTo(it) } }
                val pcm = AsrAudioReader(context.cacheDir).readPcm(audio)
                val turns = LocalSpeakerDiarizer.turns(context, pcm) { _, _ -> }
                assertTrue(turns.isNotEmpty())
                assertTrue(turns.all { it.start >= 0 && it.end <= pcm.size && it.end > it.start })
            } finally { audio.delete() }
        }
    }
}
