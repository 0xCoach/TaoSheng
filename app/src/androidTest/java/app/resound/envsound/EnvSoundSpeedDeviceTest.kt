package app.resound.envsound

import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.ResoundApp
import app.resound.MainActivity
import app.resound.transcribe.AsrAudio
import app.resound.transcribe.AsrAudioReader
import app.resound.transcribe.local.LocalAsrConnection
import app.resound.transcribe.local.LocalBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 显式指定已有段号，仅读录音/判定；不改记录、不上传、不重复入库。 */
@RunWith(AndroidJUnit4::class)
class EnvSoundSpeedDeviceTest {
    @Test fun existingRecordingsKeepDecisionsAndMeasurePipeline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ResoundApp
        // 与实际使用一致：保持前台 Activity，使绑定的本地识别进程不被系统当作缓存进程回收。
        instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val ids = requireNotNull(InstrumentationRegistry.getArguments().getString("segmentIds")).split(',').map(String::toLong)
        val useSnapshots = InstrumentationRegistry.getArguments().getString("useSnapshots") == "true"
        val model = EnvSoundModel(app)
        assertTrue(model.checkReady())
        val reader = AsrAudioReader(app.cacheDir)
        val results = JSONArray()
        app.transcribeRunner.withIdleAudio {
            for (id in ids) {
                val segment = requireNotNull(app.database.transcribe().findSegment(id))
                val old = app.database.envSound().get(id)
                val audio = if (useSnapshots) File(app.cacheDir, "env-speed-fixtures/$id.ogg") else File(segment.path)
                assertTrue(audio.isFile)
                val timings = mutableListOf<String>()
                var identityCalls = 0
                val analysis = EnvSoundAnalysis(reader::readPcm, { model.infer(it, timings::add) }, {
                    identityCalls++
                    app.voiceprintRepository.recognizesMeBeforeTranscribe(it)
                }, log = timings::add)
                val started = SystemClock.elapsedRealtime()
                val (scores, me) = analysis.analyze(audio)
                val envMs = SystemClock.elapsedRealtime() - started
                val decision = EnvSoundRules.decide(scores, EnvSoundSettings(enabled = true, skip = SkipMode.MarkOnly, media = MediaMode.MarkMedia), me)
                if (old != null) assertEquals("segment $id", old.verdict, decision.verdict.name)
                assertFalse(decision.skip)
                val row = JSONObject().put("segmentId", id).put("audioMs", segment.endAt - segment.startAt)
                    .put("oldEnvironmentMs", old?.elapsedMs ?: JSONObject.NULL).put("environmentMs", envMs)
                    .put("identityCalls", identityCalls).put("verdict", decision.verdict.name).put("timings", JSONArray(timings))
                results.put(row)
                File(app.cacheDir, "env-speed-device.json").writeText(results.toString(2))
                val asrAt = SystemClock.elapsedRealtime()
                val recognized = LocalAsrConnection.get(app).recognize(
                    AsrAudio(audio, audio.extension, segment.endAt - segment.startAt), LocalBackend.Npu) { }
                row.put("asrMs", SystemClock.elapsedRealtime() - asrAt).put("sentences", recognized.sentences.size)
                File(app.cacheDir, "env-speed-device.json").writeText(results.toString(2))
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ENV_SPEED $row\n") })
            }
        }
    }
}
