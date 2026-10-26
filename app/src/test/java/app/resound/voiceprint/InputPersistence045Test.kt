package app.resound.voiceprint

import android.app.Application
import androidx.room.Room
import app.resound.capture.*
import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class InputPersistence045Test {
    @Test fun sourceMetadataSurvivesInSpoolAndNewLogDoesNotBreakStatistics() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val files = SegmentFiles(context,db.capture(),scope)
            files.awaitReady()
            val sink = files.sink(System.currentTimeMillis(),"Usb:DJI")
            sink.begin(0); sink.write(ShortArray(16000)); sink.confirmed()
            val recording = File(context.filesDir,"segment-spool").listFiles()!!.single { it.extension=="recording" }
            assertEquals("Usb:DJI",files.readSourceId(recording))
            assertEquals("Usb:DJI",File(recording.parentFile,recording.nameWithoutExtension+".source").readText())
            assertEquals("mic",files.readSourceId(File(recording.parentFile,"legacy.pcm")))
            sink.end(16000,false)
            assertFalse(File(recording.parentFile,recording.nameWithoutExtension+".source").exists())
            CaptureStore(context,db.capture()).inputSwitched("手机麦克风 → DJI；用户选择")
            val stats = RoomCaptureStatsRepository(context,db.capture())
            val event = stats.watchdogEvents(LocalDate.now()).first().single()
            assertEquals(WatchdogEventType.InputSwitched, event.type)
            assertEquals("手机麦克风 → DJI；用户选择", event.detail)
            assertTrue(stats.exportDiagnostics().readText().contains("InputSwitched"))
            stats.dayStats(LocalDate.now()).first() // 新类型不能触发枚举解析异常。
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun matchingUsesEnoughSameInputManualSamplesOtherwiseAllAndEnrollmentIsPhone() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val segment = SegmentEntity(id=10,startAt=1,endAt=40000,sourceId="Usb:DJI",path="/unused",sizeBytes=1)
            db.capture().segment(segment)
            fun sample(id: Long, source: String, segmentId: Long?, duration: Long=10000) = VoiceprintSampleEntity(
                id=id,source=source,sourceLineId=null,segmentId=segmentId,groupKey="g$id",
                vector=VoiceprintMath.encode(FloatArray(192) { if (it==0) 1f else 0f }),durationMillis=duration,createdAt=1)
            val usb = (1L..3L).map { sample(it,"line",10) }
            val phone = (4L..6L).map { sample(it,"enroll",null) }
            val auto = sample(7,"auto",10)
            (usb+phone+auto).forEach { db.voiceprint().saveSample(it) }
            val sources = db.voiceprint().sampleInputs().associate { it.sampleId to it.sourceId }
            assertEquals("mic",sources[4L])
            assertEquals("Usb:DJI",sources[1L])
            assertEquals(usb,WindowedVoiceprint.forInput(usb+phone+auto,"Usb:DJI",sources))
            assertEquals(phone,WindowedVoiceprint.forInput(usb+phone+auto,"mic",sources))
            assertEquals(usb.take(2)+phone,WindowedVoiceprint.forInput(usb.take(2)+phone+auto,"Usb:DJI",sources))
            val short = usb.map { it.copy(durationMillis=9999) }
            assertEquals(short+phone,WindowedVoiceprint.forInput(short+phone+auto,"Usb:DJI",sources))
        } finally { db.close() }
    }
}
