package app.resound.transcribe

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalResumeQueue192Test {
    @Test fun interruptedProgressDoesNotSpendAttemptsOtherSegmentsRunAndScreenResumes() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val directory=File(context.noBackupFilesDir,"asr-progress").apply { deleteRecursively() }
        val db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val audio=File.createTempFile("resume192", ".ogg",context.cacheDir).apply { writeText("test") }
        val otherAudio=File.createTempFile("resume192-other", ".ogg",context.cacheDir).apply { writeText("other") }
        try {
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(false)
            db.capture().segment(SegmentEntity(startAt=1,endAt=1000,sourceId="mic",path=audio.path,sizeBytes=4))
            val dao=db.transcribe();val id=dao.observeSegments().first().single().id
            val files=LocalResumeFiles(directory,{})
            val key=LocalResumeKey("segment:$id",16000,"pcm","cpu","auto",true,-1,"","",true)
            val saved=LocalResumeProgress(key,listOf(ResumeTurn(0,16000,0)),"完整",listOf(ResumeChunk(0,16000,0,0,0)))
            var calls=0;var success=false
            val queue=LocalResumeQueue(context)
            val runner=TranscribeRunner(dao,object:AsrProvider {
                override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult {
                    calls++
                    if(!success) { files.save(saved);throw AsrException(AsrError.Interrupted,detail="local-inference-timeout") }
                    return AsrResult(emptyList(),1000,null)
                }
            },{""},{_,_->},defaultSource={TranscribeSource.Local}).apply { localResume=queue }
            repeat(2) { runner.run() }
            assertEquals(2,calls);assertEquals(0,dao.segment(id).attempts);assertEquals("Pending",dao.segment(id).status)
            assertTrue(queue.deferred(id));assertFalse(queue.readyToResume())
            // 推迟的段不能挡住后面的本地段。
            db.capture().segment(SegmentEntity(startAt=2000,endAt=3000,sourceId="mic",path=otherAudio.path,sizeBytes=4))
            success=true;runner.run();assertEquals(3,calls);assertEquals("Pending",dao.segment(id).status)
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            assertTrue(queue.readyToResume());runner.run();assertEquals(4,calls)
            assertEquals("Empty",dao.segment(id).status);assertEquals(0,files.state("segment:$id").count)
            assertNull(files.load("segment:$id"))
        } finally { db.close();audio.delete();otherAudio.delete();directory.deleteRecursively() }
    }
    @Test fun processDeathIsCountedOnceAndChargingAllowsDeferredSegment() {
        val context=RuntimeEnvironment.getApplication()
        val directory=File(context.noBackupFilesDir,"asr-progress").apply { deleteRecursively() }
        val audio=File.createTempFile("resume192", ".ogg",context.cacheDir)
        try {
            val row=SegmentEntity(id=7,startAt=0,endAt=1,sourceId="mic",path=audio.path,sizeBytes=0)
            val files=LocalResumeFiles(directory,{})
            files.state("segment:7",LocalInterruptionState(1,true))
            val queue=LocalResumeQueue(context)
            repeat(2) { queue.prepare(listOf(row),TranscribeSource.Local) }
            assertEquals(2,files.state("segment:7").count)
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(true)
            assertFalse(queue.deferred(7));assertTrue(queue.readyToResume())
            queue.prepare(listOf(row.copy(asrSource=TranscribeSource.Doubao.name)),TranscribeSource.Local)
            assertEquals(0,files.state("segment:7").count)
        } finally { audio.delete();directory.deleteRecursively() }
    }
}
