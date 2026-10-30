package app.resound.envsound

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
class EnvSound070Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private val enabled = EnvSoundSettings(enabled=true)
    private var clock = 1_800_000_000_000L
    private var calls = 0
    private var asr = 0
    private var queued = 0
    private var scores = score("Music" to .95f)
    private val state = MutableStateFlow<EnvModelState>(EnvModelState.Ready)
    private lateinit var repo: LocalEnvSoundRepository
    private fun score(vararg pairs: Pair<String,Float>): Map<String,Float> =
        (EnvSoundRules.speech + EnvSoundRules.human + EnvSoundRules.tags.values.flatMap { it.second } + EnvSoundRules.sounds.values.flatMap { it.second }).associateWith { 0f } + pairs
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        dir=File(context.cacheDir,"env070").apply { mkdirs() }
        val prefs=context.getSharedPreferences("env070",0);prefs.edit().clear().commit()
        repo=LocalEnvSoundRepository(db,prefs,scope,state,{}, { calls++;EnvSoundEvidence(scores, scores, 32000, 32000) to false }, { queued++ },now={clock})
    }
    @After fun close() { scope.cancel();db.close();dir.deleteRecursively() }
    private suspend fun segment(id: Long, start: Long=clock, duration: Long=60000): SegmentEntity {
        val f=File(dir,"$id.wav").apply { writeBytes(byteArrayOf(1)) }
        return SegmentEntity(id,start,start+duration,"mic",f.path,1).also { db.capture().segment(it) }
    }
    private fun runner()=TranscribeRunner(db.transcribe(), object: AsrProvider {
        override suspend fun recognize(audio: AsrAudio,key: String,beforeAudio: suspend () -> Unit): AsrResult {
            asr++;beforeAudio();return AsrResult(emptyList(),audio.durationMillis,null)
        }
    },{"key"},{_,_->},now={clock},deferAudioCleanup=true,beforeTranscribe=repo::beforeTranscribe)
    @Test fun pureMusicAndSpeechAndHumanProtection() {
        assertEquals(SegmentVerdict.Skippable,EnvSoundRules.decide(scores,enabled,false).verdict)
        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(score("Speech" to .9f),enabled,false).verdict)
        for(h in listOf("Laughter","Cough")) assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(score("Music" to .8f,h to .05f),enabled,false).verdict)
        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(mapOf("Music" to 1f),enabled,false).verdict)
    }
    @Test fun meProtectsMediaAndSkipAndMediaSuppressesSpecialSounds() {
        val music=score("Music" to .95f,"Speech" to .7f,"Bark" to .5f)
        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(music,enabled,true).verdict)
        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(scores,enabled.copy(skip=SkipMode.On),true).verdict)
        val media=EnvSoundRules.decide(music,enabled,false)
        assertEquals(SegmentVerdict.Media,media.verdict);assertTrue(media.sounds.isEmpty())
        assertTrue(EnvSoundRules.decide(music,enabled.copy(media=MediaMode.MarkMedia),false).markMedia)
        assertTrue(EnvSoundRules.decide(music,enabled.copy(tags=false,specialSounds=false),false).tags.isEmpty())
    }
    @Test fun longAudioUsesThreeTenSecondWindows() {
        assertEquals(listOf(0 until 160000,240000 until 400000,480000 until 640000),EnvSoundRules.windows(640000))
        assertEquals(listOf(0 until 480000),EnvSoundRules.windows(480000))
    }
    @Test fun markOnlyStillTranscribesAndDoesNotInferAgain() = runBlocking {
        repo.update(enabled);segment(1);runner().run()
        assertEquals(1,asr);assertEquals("Skippable",db.envSound().get(1)!!.verdict)
        repo.beforeTranscribe(db.transcribe().segment(1));assertEquals(1,calls)
    }
    @Test fun skipRestoresAndSavedMinutesDisappear() = runBlocking {
        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
        assertEquals(0,asr);assertEquals("SkippedEnv",db.transcribe().segment(1).status)
        assertEquals(1,repo.stats(7).first().savedMinutes)
        assertTrue(repo.restore(1).isSuccess);runner().run()
        assertEquals(1,asr);assertEquals(1,calls);assertEquals(0,repo.stats(7).first().savedMinutes)
    }
    @Test fun mediaSkipAndWrongRequeuesKeepingScores() = runBlocking {
        scores=score("Music" to .95f,"Speech" to .8f)
        repo.update(enabled.copy(media=MediaMode.SkipTranscribe));segment(1);runner().run()
        val stored=db.envSound().get(1)!!.scores
        assertEquals(0,asr);assertEquals("Media",db.envSound().get(1)!!.verdict)
        repo.reportWrong(1)
        withTimeout(3000) { while(!db.envSound().get(1)!!.reportedWrong) delay(10) }
        runner().run();assertEquals(1,asr);assertEquals(stored,db.envSound().get(1)!!.scores)
    }
    @Test fun wrongSkipBecomesKeepAndExpiredRestoreFails() = runBlocking {
        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
        repo.reportWrong(1)
        withTimeout(3000) { while(!db.envSound().get(1)!!.reportedWrong) delay(10) }
        assertEquals("Keep",db.envSound().get(1)!!.verdict);runner().run();assertEquals(1,asr)
        segment(2);runner().run();File(db.transcribe().segment(2).path).delete()
        assertEquals("这段录音已过保留期",repo.restore(2).exceptionOrNull()!!.message)
        repo.update(enabled);segment(3);runner().run()
        db.transcribe().update(db.transcribe().segment(3).copy(status="Quiet"))
        repo.reportWrong(3)
        withTimeout(3000) { while(db.transcribe().segment(3).status != "Pending") delay(10) }
        assertEquals("Keep",db.envSound().get(3)!!.verdict)
    }
    @Test fun disabledUnreadyOldAndFailureAllPassThrough() = runBlocking {
        segment(1);runner().run();assertEquals(0,calls)
        repo.update(enabled);state.value=EnvModelState.NotDownloaded;segment(2);runner().run();assertEquals(0,calls)
        state.value=EnvModelState.Ready;segment(3,clock-1);runner().run();assertEquals(0,calls)
        assertEquals(3,asr)
        val failure=LocalEnvSoundRepository(db,context.getSharedPreferences("env070",0),scope,state,{}, { error("decode") },{})
        segment(4);assertFalse(failure.beforeTranscribe(db.transcribe().segment(4)))
    }
    @Test fun aggregationSplitsHoursAndMergesEvents() = runBlocking {
        val date=LocalDate.of(2026,10,29);val zone=ZoneId.systemDefault();val start=date.atTime(10,59).atZone(zone).toInstant().toEpochMilli()
        clock=start;repo.update(enabled);scores=score("Speech" to .9f,"Rain" to .4f,"Knock" to .4f)
        segment(1,start,120000);runner().run()
        val id=db.memory().insertEvent(MemoryEventEntity(startAt=start,endAt=start+120000,scene="其他",title="事",summary="",pointsJson="[]",peopleJson="[]",place=null,emotion=null,intensity=null,important=false,createdAt=start,model="test"))
        val merged=repo.eventSounds(date).first().getValue(id)
        assertEquals(setOf(EnvTag.Rain),merged.tags);assertEquals(setOf(SpecialSound.Knock),merged.sounds)
        assertEquals(listOf(id),repo.eventsWith(EnvTag.Rain,SpecialSound.Knock).first())
        val hours=repo.hourly(date).first();assertEquals(listOf(10,11),hours.map { it.hour });assertEquals(listOf(1,1),hours.map { it.speechMinutes })
    }
    @Test fun skippedAudioHasOneDayFloorAndClearKeepsDecision() = runBlocking {
        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
        val prefs=context.getSharedPreferences("retention070",0);prefs.edit().putInt("originalRetentionDays",1).commit()
        val audio=LocalOriginalAudio(db.transcribe(),prefs,scope,playback=PlaybackCaptureHold(scope,{}),now={clock})
        clock+=86399999;audio.cleanup();assertTrue(File(db.transcribe().segment(1).path).exists())
        clock+=1;audio.cleanup();assertFalse(File(db.transcribe().segment(1).path).exists());assertNotNull(db.envSound().get(1))
    }
    @Test fun reviewAndWeeklyPresentationObeyToggleWithoutChangingStoredSummary() = runBlocking {
        val date=LocalDate.of(2026,10,29)
        clock=date.atTime(10,0).atZone(app.resound.skills.SKILL_ZONE).toInstant().toEpochMilli()
        repo.update(enabled);scores=score("Speech" to .9f,"Rain" to .4f,"Knock" to .4f)
        segment(1);runner().run()
        val id=db.memory().insertEvent(MemoryEventEntity(startAt=clock,endAt=clock+60000,scene="其他",title="事情",summary="原摘要",pointsJson="[]",peopleJson="[]",place=null,emotion=null,intensity=null,important=false,createdAt=clock,model="test"))
        withTimeout(3000) { repo.presentation.first { id in it };repo.eventMetadata.first { it.any { e -> e.id == id } } }
        app.resound.AppGraph.envSound=repo
        val review=app.resound.review.DailyReview(date,"摘要",listOf(app.resound.review.ReviewSection("发生了什么",listOf("事情"),listOf(id))),clock)
        assertTrue(EnvSoundPresentation.decorate(review).sections[0].lines[0].contains("期间有敲门声"))
        val card=app.resound.skills.SkillCard(1,"weekly","周回顾","",date,clock,null,"标题","摘要",emptyList(),emptyMap(),emptyList(),clock,false)
        assertTrue(EnvSoundPresentation.decorate(card).values.getValue("env_sound_observations").items.single().contains("环境：雨"))
        assertEquals("原摘要",db.memory().event(id)!!.summary)
        repo.update(enabled.copy(showInSummaries=false))
        assertEquals(review,EnvSoundPresentation.decorate(review));assertEquals(card,EnvSoundPresentation.decorate(card))
    }
    @Test fun migration22PreservesSegmentAndCascadesJudgment() = runBlocking {
        val name="migration070.db";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/app.resound.data.CaptureDatabase/22.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,0,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i)
                fun sql(s:String)=s.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices=e.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1,2,'mic','/old',1,'Pending')")
            old.version=22
        }
        val migrated=Room.databaseBuilder(context,CaptureDatabase::class.java,name).allowMainThreadQueries().addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION,migrated.openHelper.writableDatabase.version)
            assertEquals("Pending",migrated.transcribe().segment(1).status)
            migrated.envSound().insert(EnvSoundRow(1,1,2,"/old","Keep",null,"","","{}",false,false))
            migrated.openHelper.writableDatabase.execSQL("DELETE FROM segments WHERE id=1")
            assertNull(migrated.envSound().get(1))
        } finally { migrated.close();context.deleteDatabase(name) }
    }
}
