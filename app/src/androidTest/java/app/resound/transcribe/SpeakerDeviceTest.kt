package app.resound.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.transcribe.local.*
import app.resound.voiceprint.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 在独立 validation 包运行，输入为用户授权的真实音频，结果只写私有目录。 */
@RunWith(AndroidJUnit4::class)
class SpeakerDeviceTest {
    @Test fun realAudioDiarizationAndNpuTranscription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val output = JSONArray()
        for (id in listOf(93,132,137,138)) {
            val file = File(context.cacheDir,"segment-$id.ogg")
            val pcm = AsrAudioReader(context.cacheDir).readPcm(file)
            val start = android.os.SystemClock.elapsedRealtime()
            val turns = LocalSpeakerDiarizer.turns(context,pcm) { _,_-> }
            assertTrue(turns.isNotEmpty())
            assertTrue(turns.all { it.start >= 0 && it.end <= pcm.size && it.end > it.start })
            assertTrue(turns.zipWithNext().all { (a,b)->a.end <= b.start })
            output.put(JSONObject().put("id",id).put("durationMs",pcm.size/16L)
                .put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
                .put("turns",JSONArray().apply { turns.forEach { put(JSONObject().put("startMs",it.start/16).put("endMs",it.end/16).put("speaker",it.speaker ?: -1)) } }))
        }
        File(context.filesDir,"speaker-turns.json").writeText(output.toString(2))
        val settings = AppGraph.transcribeSettings as AndroidTranscribeSettings
        settings.onChanged={}; settings.setSource(TranscribeSource.Local); settings.setBackend(LocalBackend.Npu)
        val file = File(context.cacheDir,"segment-138.ogg")
        val result = LocalAsrProvider(context,settings).recognize(AsrAudio(file,"ogg",30392),"local-model") { fail("must not upload") }
        assertTrue(result.sentences.isNotEmpty())
        assertTrue(result.sentences.any { it.speakerTag != null })
        assertTrue(settings.localStatus.value.contains("NPU · 检查通过"))
        File(context.filesDir,"speaker-transcript.json").writeText(JSONArray().apply { result.sentences.forEach {
            put(JSONObject().put("startMs",it.startMillis).put("endMs",it.endMillis).put("text",it.text).put("speaker",it.speakerTag))
        } }.toString(2))
        // 旧 Java ORT 和新 sherpa ORT 共存时，现有 VAD/声纹仍能加载。
        app.resound.audio.SileroVad(context).use { it.accept(ShortArray(512)) { _,_,_-> } }
        val pcm = AsrAudioReader(context.cacheDir).readPcm(file)
        CampPlusEmbedding(context).use { assertEquals(192,it.compute(pcm.copyOfRange(0,minOf(pcm.size,48000))).size) }
    }
}
