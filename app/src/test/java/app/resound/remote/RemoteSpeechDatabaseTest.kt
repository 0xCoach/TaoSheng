package app.resound.remote

import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.voiceprint.LocalVoiceprintRepository
import app.resound.voiceprint.SpeakerEmbedding
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class RemoteSpeechDatabaseTest {
    @Test fun migrates29PreservingPeopleAndTranscript() = runBlocking {
        val context:Context=RuntimeEnvironment.getApplication();val name="remote-migration.db";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/app.resound.data.CaptureDatabase/29.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);fun sql(v:String)=v.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,4000,'mic','/saved.ogg',100,'Done')")
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerTag,speakerName) VALUES(1,1,1000,3000,'保留原话','S1','我')")
            old.execSQL("INSERT INTO voiceprint_people(id,name,createdAt) VALUES(5,'朋友',1000)")
            old.version=29
        }
        val db=Room.databaseBuilder(context,CaptureDatabase::class.java,name).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION,db.openHelper.writableDatabase.version)
            assertEquals("保留原话",db.voiceprint().line(1)!!.text);assertEquals("朋友",db.voiceprint().people().single().name)
            assertNull(db.transcribe().segment(1).remoteSpeakerScope)
            db.remoteSpeech().task(RemoteSpeechTask("task","scope","request","a".repeat(32),1,1000))
            assertEquals("a".repeat(32),db.remoteSpeech().task("task")!!.jobId)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun migrates30KeepingActionsAndAddsSampleIndex() = runBlocking {
        val context:Context=RuntimeEnvironment.getApplication();val name="remote-migration-31.db";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/app.resound.data.CaptureDatabase/30.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);fun sql(v:String)=v.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO remote_speech_actions(id,scope,localId,kind,value,audioPath,createdAt,error) VALUES('a','scope',0,'enroll',NULL,'/x.wav',1,NULL)")
            old.version=30
        }
        val db=Room.databaseBuilder(context,CaptureDatabase::class.java,name).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION,db.openHelper.writableDatabase.version)
            assertNull(db.remoteSpeech().actions("scope").single().lineId)
            db.remoteSpeech().sample(RemoteSpeechSample("b".repeat(32),"scope",0,null,6000,1))
            assertEquals(6000L,db.remoteSpeech().samples("scope",0).single().durationMillis)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun shortLineIsExtendedWithSameSpeakerLinesClickedFirst() {
        val pcm=ShortArray(60*16000){ i -> (1000 + i / 16000).toShort() }  // 每秒不同幅度，便于辨认来源
        fun line(id:Long,from:Long,to:Long)=TranscriptLineEntity(id=id,segmentId=1,startAt=from,endAt=to,text="",speakerTag="S1")
        val clicked=line(2,10_000,12_000)
        val audio=remoteSampleAudio(pcm,0,clicked,listOf(line(1,0,4000),clicked,line(3,40_000,60_000)))
        assertTrue(audio.size>=5*16000)
        assertEquals(1010.toShort(),audio.first())  // 先放所点那句
        val capped=remoteSampleAudio(pcm,0,line(9,0,60_000),listOf(line(9,0,60_000)))
        assertEquals(30*16000,capped.size)
    }
    @Test fun remoteIdentityIsNeverOverwrittenByLocalAutoMatching() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CaptureDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            db.capture().segment(SegmentEntity(1,0,3000,"mic","/unavailable.wav",100,status="Done",voiceprintProcessedAt=10,remoteSpeakerScope="scope"))
            db.transcribe().complete(db.transcribe().segment(1),listOf(TranscriptLineEntity(segmentId=1,startAt=0,endAt=3000,text="远端结果",speakerTag="S1",speakerName="朋友",speakerPersonId=7)))
            val id=db.voiceprint().lines(1).single().id
            val local=LocalVoiceprintRepository(db,scope,{error("must not decode")},{SpeakerEmbedding { error("must not infer") }},{})
            assertFalse(local.runSegment(1))
            db.voiceprint().invalidate();assertNotNull(db.transcribe().segment(1).voiceprintProcessedAt)
            db.voiceprint().identity(listOf(id),0,"我",0.9f)
            assertEquals("朋友",db.voiceprint().line(id)!!.speakerName)
            db.voiceprint().identity(listOf(id),0,"我",null,true)
            assertEquals("我",db.voiceprint().line(id)!!.speakerName)
            db.remoteSpeech().clearExplicitResults("scope",0)
            assertNull(db.voiceprint().line(id)!!.speakerName)
        } finally { scope.cancel();db.close() }
    }
    @Test fun remoteWaitingDoesNotConsumeFailureAttempts() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CaptureDatabase::class.java).build()
        val file=File.createTempFile("remote-pending", ".wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            db.capture().segment(SegmentEntity(1,0,3000,"mic",file.path,1))
            val provider=object : app.resound.transcribe.AsrProvider {
                override suspend fun recognize(audio:app.resound.transcribe.AsrAudio,key:String,beforeAudio:suspend ()->Unit):app.resound.transcribe.AsrResult {
                    throw app.resound.transcribe.AsrException(app.resound.transcribe.AsrError.RemotePending)
                }
            }
            val runner=app.resound.transcribe.TranscribeRunner(db.transcribe(),provider,{"key"},{_,_->},now={1000L})
            runner.run()
            val row=db.transcribe().segment(1)
            assertEquals(0,row.attempts);assertEquals("Failed",row.status);assertTrue(row.nextRetryAt!!>1000)
        } finally { file.delete();db.close() }
    }

}
