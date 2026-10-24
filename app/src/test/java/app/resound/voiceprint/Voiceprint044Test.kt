package app.resound.voiceprint

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Voiceprint044Test {
    private fun vector(i: Int) = FloatArray(192).also { it[i] = 1f }
    private fun candidate(i: Int = 0) = SpeakerIdentity.Candidate(i.toLong(), if (i == 0) "我" else "其他", vector(i),
        List(3) { vector(i) }, WindowedVoiceprint.SURE)
    private fun line(a: Long, b: Long) = TranscriptLineEntity(id = 1, segmentId = 1, startAt = a, endAt = b, text = "合成夹具", speakerTag = "0")
    private fun observation(a: Long, b: Long, i: Int?) = WindowedVoiceprint.Observation(WindowedVoiceprint.Window(a,b),
        VoiceprintFeatureCache.Feature(i?.let(::vector), b-a))
    private fun sample(i: Int, source: String = "line", segment: Long? = null) = VoiceprintSampleEntity(
        source = source, sourceLineId = null, segmentId = segment, groupKey = segment?.let { "tag:0" },
        vector = VoiceprintMath.encode(vector(i)), durationMillis = 10_000, createdAt = 1)

    @Test fun windowsKeepRealTimeGapsOverlapAndShortTail() {
        val windows = WindowedVoiceprint.windows(0, 10_000, listOf(line(0,4_000),line(2_000,5_000),line(8_000,10_000)))
        assertEquals(listOf(WindowedVoiceprint.Window(0,3_000),WindowedVoiceprint.Window(3_000,5_000),WindowedVoiceprint.Window(8_000,10_000)), windows)
    }

    @Test fun mixedGroupMapsEachSentenceByOverlapAndRejectsMixedLongSentence() {
        val observations = listOf(observation(0,3_000,0),observation(3_000,6_000,1))
        val candidates = listOf(candidate(),candidate(1))
        assertEquals(0L, WindowedVoiceprint.vote(line(0,3_000),observations,candidates).personId)
        assertEquals(1L, WindowedVoiceprint.vote(line(3_000,6_000),observations,candidates).personId)
        assertNull(WindowedVoiceprint.vote(line(0,6_000),observations,candidates).personId)
    }

    @Test fun missingAndShortWindowsCannotTurnTinyPositiveIntoWholeSentence() {
        val result = WindowedVoiceprint.vote(line(0,6_000),listOf(observation(0,3_000,0),observation(3_000,6_000,null)),listOf(candidate()))
        assertNull(result.personId)
        assertTrue(result.score!! < WindowedVoiceprint.SURE)
    }

    @Test fun topThreeDoesNotAcceptSingleAccidentalMatchAndKeepsMultiPersonMargin() {
        val c = candidate().copy(templates=listOf(vector(0),vector(1),vector(2)))
        assertNull(SpeakerIdentity.match(vector(0),listOf(c)).personId)
        assertEquals(0L, SpeakerIdentity.match(vector(0),listOf(candidate())).personId)
        assertNull(SpeakerIdentity.match(vector(0),listOf(candidate(),candidate().copy(id=1))).personId)
    }

    @Test fun automaticSamplesNeverMoveReferenceAndManualOutlierIsPreserved() {
        val manual = listOf(sample(0),sample(0),sample(1))
        val bank = WindowedVoiceprint.trusted(manual + List(40) { sample(2,"auto") })
        assertEquals(manual,bank)
        assertEquals(3,bank.size)
    }

    @Test fun autoEnrollmentRequiresCoherentWindowsAndTwoSecondsOfSpeech() {
        assertNotNull(WindowedVoiceprint.autoSample(listOf(observation(0,3_000,0)),listOf(candidate())))
        assertNull(WindowedVoiceprint.autoSample(listOf(observation(0,1_800,0)),listOf(candidate())))
        assertNull(WindowedVoiceprint.autoSample(listOf(observation(0,3_000,0),observation(3_000,6_000,1)),listOf(candidate())))
        assertNull(WindowedVoiceprint.autoSample(listOf(observation(0,3_000,0),observation(3_000,4_000,null)),listOf(candidate())))
    }

    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: LocalVoiceprintRepository
    private val files = mutableListOf<File>()
    private val audio = mutableMapOf<String,ShortArray>()
    private var computes = 0
    private var decodes = 0
    private val clock = 100_000_000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repository = LocalVoiceprintRepository(db,scope,{ decodes++;audio.getValue(it.path) },
            { SpeakerEmbedding { computes++; vector(if (it.first()>0) 0 else 1) } },{}, { clock })
    }
    @After fun close() = runBlocking {
        scope.cancel();scope.coroutineContext[Job]!!.join();db.close();files.forEach { it.delete() }
    }
    private suspend fun seed() { repeat(3) { db.voiceprint().saveSample(sample(0,"enroll")) } }
    private suspend fun recording(playback: Long = 0): SegmentEntity {
        val f=File.createTempFile("voice044-", ".ogg", RuntimeEnvironment.getApplication().cacheDir).also { files+=it }
        audio[f.path]=ShortArray(6*16_000) { if(it<3*16_000) 1000 else -1000 }
        db.capture().segment(SegmentEntity(startAt=clock-10_000,endAt=clock-4_000,sourceId="mic",path=f.path,sizeBytes=1,status="Done",playbackMillis=playback))
        val s=db.transcribe().observeSegments().first().first { it.path==f.path }
        db.transcribe().insertLines(listOf(line(s.startAt,s.startAt+3_000).copy(id=0,segmentId=s.id),line(s.startAt+3_000,s.endAt).copy(id=0,segmentId=s.id)))
        return s
    }

    @Test fun recomputeSplitsGroupCachesWindowsAndPreservesExpiredAndManualResults() = runBlocking {
        seed(); val s=recording(); val expired=recording(); val manual=recording()
        db.voiceprint().identity(db.voiceprint().lines(expired.id).map { it.id },0,"我",.9f)
        File(expired.path).delete()
        db.voiceprint().identity(db.voiceprint().lines(manual.id).map { it.id },null,null,null,true)
        assertFalse(repository.recomputeRecent(1))
        val lines=db.voiceprint().lines(s.id)
        assertEquals("我",lines[0].speakerName); assertNull(lines[1].speakerName)
        assertTrue(db.voiceprint().lines(expired.id).all { it.speakerScore==.9f })
        assertTrue(db.voiceprint().lines(manual.id).all { it.speakerManual && it.speakerName==null })
        val before=computes;assertFalse(repository.recomputeRecent(1));assertEquals(before,computes)
        assertEquals(2,decodes)
    }

    @Test fun playbackAndExclusionPreventAutoSamplesAndPruningNeverDeletesManual() = runBlocking {
        seed();val p=recording(playback=1);audio[p.path]=ShortArray(96_000){1000}
        val e=recording();audio[e.path]=ShortArray(96_000){1000}
        db.voiceprint().exclude(VoiceprintExclusionEntity(e.id,"tag:0",1))
        db.voiceprint().saveSample(sample(1,"auto",999))
        assertFalse(repository.recomputeRecent(1))
        assertEquals(3,db.voiceprint().allSamples().size)
        assertTrue(db.voiceprint().lines(e.id).all { it.speakerName==null })
    }

    @Test fun sameSegmentReferenceCannotRecognizeItselfAndUnknownCacheDoesNotUseLegacyFeature() = runBlocking {
        val s=recording();repeat(3) { db.voiceprint().saveSample(sample(0,"enroll",s.id).copy(groupKey="sample:$it")) }
        assertFalse(repository.recomputeRecent(1))
        assertTrue(db.voiceprint().lines(s.id).all { it.speakerName==null })
        assertEquals(2,computes)
    }

    @Test fun resetDuringRecomputeCannotResurrectResultsOrFeatures() = runBlocking {
        seed();val s=recording()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val resetDone=CompletableDeferred<Unit>()
        repository=LocalVoiceprintRepository(db,scope,{ entered.complete(Unit);release.await();audio.getValue(it.path) },
            { SpeakerEmbedding { vector(0) } },{ resetDone.complete(Unit) },{clock})
        val job=async { repository.recomputeRecent(1) }
        try { withTimeout(3000) { entered.await();repository.reset();resetDone.await() } } finally { release.complete(Unit) }
        assertTrue(job.await());assertTrue(db.voiceprint().allSamples().isEmpty())
        assertNull(db.voiceprint().lines(s.id).first().speakerName)
        assertNull(db.transcribe().segment(s.id).voiceprintProcessedAt)
    }

    @Test fun manualClickRejectsMixedWindowsAndQuarantinesOldSampleWithoutDeletingIt() = runBlocking {
        val s=recording()
        db.transcribe().deleteLines(s.id)
        db.transcribe().insertLines(listOf(line(s.startAt,s.endAt).copy(id=0,segmentId=s.id)))
        val clicked=db.voiceprint().lines(s.id).single()
        db.voiceprint().saveSample(sample(0, "line", s.id).copy(sourceLineId=clicked.id))
        repository.nameAndAssign(clicked.id,"我").getOrThrow()
        assertTrue(db.voiceprint().line(clicked.id)!!.speakerManual)
        val saved=db.voiceprint().samples().single()
        assertEquals("line-review",saved.source)
        assertTrue(WindowedVoiceprint.trusted(listOf(saved)).isEmpty())
        assertTrue(repository.sampleHealth().single().outlier)
        audio[s.path]=ShortArray(96_000){1000}
        repository.nameAndAssign(clicked.id,"我").getOrThrow()
        val restored=db.voiceprint().samples().single()
        assertEquals(saved.id,restored.id)
        assertEquals("line",restored.source)
        assertEquals(3000L,restored.durationMillis)
    }

    @Test fun recomputeRejectsInvalidRangeAndIgnoresOlderRecordings() = runBlocking {
        seed(); val s=recording(); db.transcribe().update(s.copy(startAt=clock-2*86_400_000L))
        assertFalse(repository.recomputeRecent(1));assertEquals(0,decodes)
        try { repository.recomputeRecent(0);fail("必须拒绝非法天数") } catch (_: IllegalArgumentException) { }
        val worker=androidx.work.testing.TestListenableWorkerBuilder<VoiceprintRecomputeWork>(RuntimeEnvironment.getApplication())
            .setInputData(androidx.work.workDataOf("days" to 0)).build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(),worker.doWork())
    }
}
