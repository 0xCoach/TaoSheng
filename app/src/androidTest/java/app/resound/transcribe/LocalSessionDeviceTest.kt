package app.resound.transcribe

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.transcribe.local.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 只使用独立验证包，真实录音与结果保留在私有目录，不调用云端。 */
@RunWith(AndroidJUnit4::class)
class LocalSessionDeviceTest {
    @Test fun speakerSegmentationWorksAcrossReusedSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val prefs = context.getSharedPreferences("local-asr",0)
        val before = prefs.getBoolean("speakers-enabled",true)
        val connection = LocalAsrConnection.get(context)
        val started = SystemClock.elapsedRealtime()
        try {
            prefs.edit().putBoolean("speakers-enabled",true).commit()
            var pid = 0
            repeat(2) {
                val result = connection.recognize(AsrAudio(File(context.cacheDir,"segment-138.ogg"),"ogg",30397),LocalBackend.Npu) {}
                assertTrue(result.sentences.isNotEmpty())
                assertTrue(result.sentences.any { it.speakerTag != null })
                assertTrue(result.sentences.all { it.startMillis >= 0 && it.endMillis > it.startMillis && it.endMillis <= result.durationMillis })
                val metrics = requireNotNull(connection.lastMetrics)
                assertEquals(1,metrics.modelLoads)
                assertTrue(metrics.timedChunks>0)
                if(it==0)pid=metrics.pid else assertEquals(pid,metrics.pid)
            }
            assertTrue(ModelVerification.matches(LocalModels.directory(context,LocalModels.speakers),LocalModels.speakers))
            File(context.filesDir,"session-speakers-test.json").writeText(JSONObject().put("pid",pid)
                .put("requests",2).put("asrModelLoads",1).put("elapsedMs",SystemClock.elapsedRealtime()-started).toString(2))
        } finally { prefs.edit().putBoolean("speakers-enabled",before).commit() }
        Unit
    }
    @Test fun queuedReuseFallbackAndIdleExit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val settings = AppGraph.transcribeSettings as AndroidTranscribeSettings
        val source = settings.source.value; val backend = settings.backend.value
        val changed = settings.onChanged
        val prefs = context.getSharedPreferences("local-asr",0)
        val speakers = prefs.getBoolean("speakers-enabled",true)
        val connection = LocalAsrConnection.get(context)
        val report = JSONObject()
        val started = SystemClock.elapsedRealtime()
        try {
            settings.onChanged = {}
            settings.setSource(TranscribeSource.Local)
            settings.setBackend(LocalBackend.Npu)
            prefs.edit().putBoolean("speakers-enabled",false).commit()
            val progress = java.util.concurrent.CopyOnWriteArrayList<String>()
            val inputs = listOf(138 to 30397L, 93 to 65157L, 138 to 30397L)
            val results = coroutineScope {
                inputs.map { (id,duration) -> async {
                    connection.recognize(AsrAudio(File(context.cacheDir,"segment-$id.ogg"),"ogg",duration),LocalBackend.Npu) { progress.add(it) }
                } }.awaitAll()
            }
            assertEquals(3,results.size)
            for (result in results) {
                assertTrue(result.sentences.isNotEmpty())
                assertTrue(result.sentences.all { it.startMillis >= 0 && it.endMillis > it.startMillis && it.endMillis <= result.durationMillis })
                assertTrue(result.sentences.zipWithNext().all { (a,b) -> a.startMillis <= b.startMillis })
            }
            assertTrue("busy requests should report queueing",progress.any { it.contains("队列") })
            val batch = requireNotNull(connection.lastMetrics)
            assertEquals("one native model for the whole NPU batch",1,batch.modelLoads)
            assertTrue("real token timestamps must exist",batch.timedChunks>0)
            assertTrue("30 second input should be cut",batch.chunks>=2)
            report.put("npuBatch",JSONObject().put("pid",batch.pid).put("modelLoads",batch.modelLoads)
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("timedChunks",batch.timedChunks).put("chunks",batch.chunks))
            File(context.filesDir,"session-transcripts.json").writeText(JSONArray().apply {
                results.forEachIndexed { index,result -> put(JSONObject().put("segment",inputs[index].first).put("sentences",JSONArray().apply {
                    result.sentences.forEach { put(JSONObject().put("start",it.startMillis).put("end",it.endMillis).put("text",it.text)) }
                })) }
            }.toString(2))
            val short = File(context.cacheDir,"session-short.ogg")
            context.assets.open("asr-test.ogg").use { input -> short.outputStream().use { input.copyTo(it) } }
            // 检查已判定 NPU 不可用时，实际成功运行 CPU 且复用同一服务进程。
            settings.recordNpuCheck(LocalModels.npuCheckKey(context), false)
            val fallback = LocalAsrProvider(context,settings).recognize(AsrAudio(short,"ogg",3007),"local") { fail("must not upload") }
            assertTrue(fallback.sentences.isNotEmpty())
            assertTrue(settings.localStatus.value.contains("回退 CPU"))
            val cpu = requireNotNull(connection.lastMetrics)
            assertEquals(batch.pid,cpu.pid); assertEquals(2,cpu.modelLoads)
            for(spec in listOf(LocalModels.cpu,LocalModels.npu))assertTrue(ModelVerification.matches(LocalModels.directory(context,spec),spec))
            report.put("cpuFallback",JSONObject().put("pid",cpu.pid).put("modelLoads",cpu.modelLoads))
            File(context.filesDir,"session-test.json").writeText(report.toString(2))
            val idleStart = SystemClock.elapsedRealtime()
            delay(64_000)
            val am = context.getSystemService(ActivityManager::class.java)
            assertFalse("idle model process must exit",am.runningAppProcesses.orEmpty().any { it.pid==cpu.pid })
            report.put("idleExitedAfterMs",SystemClock.elapsedRealtime()-idleStart)
            val resumed = connection.recognize(AsrAudio(short,"ogg",3007),LocalBackend.Npu) {}
            assertTrue(resumed.sentences.isNotEmpty())
            val newSession = requireNotNull(connection.lastMetrics)
            assertNotEquals(cpu.pid,newSession.pid);assertEquals(1,newSession.modelLoads)
            report.put("restartedPid",newSession.pid).put("totalMs",SystemClock.elapsedRealtime()-started)
            File(context.filesDir,"session-test.json").writeText(report.toString(2))
            short.delete()
            Unit
        } finally {
            prefs.edit().putBoolean("speakers-enabled",speakers).commit()
            settings.setBackend(backend);settings.setSource(source);settings.onChanged=changed
        }
    }
}
