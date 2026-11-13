package app.resound.data

import kotlinx.datetime.toKotlinLocalDate
import android.app.Application
import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.envsound.*
import app.resound.importer.*
import app.resound.topic.*
import app.resound.scheduling.BackgroundRefreshPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TableObservers182Test {
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private val selects = AtomicInteger()
    @Before fun setup() {
        val base = AndroidSQLiteDriver()
        val driver = object : SQLiteDriver {
            override val hasConnectionPool = base.hasConnectionPool
            override fun open(fileName: String): SQLiteConnection {
                val connection = base.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (sql.startsWith("SELECT") && !sql.contains("room_") && !sql.contains("sqlite_")) selects.incrementAndGet()
                        return connection.prepare(sql)
                    }
                }
            }
        }
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).setDriver(driver).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun close() { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close() }
    private suspend fun segment(id: Long, status: String = "Done", start: Long = id * 1000, end: Long = start + 500,
        processed: Long? = null, failed: Long? = null, record: Long? = null): SegmentEntity =
        SegmentEntity(id,start,end,"mic","synthetic-$id",1,status,voiceprintProcessedAt=processed,firstFailedAt=failed,importRecordId=record)
            .also { db.capture().segment(it) }
    private fun env(s: SegmentEntity, verdict: String = "Keep") = EnvSoundRow(s.id,s.startAt,s.endAt,s.path,verdict,null,"Rain","Knock","{}",verdict=="Skipped",false)
    private fun event(start: Long, end: Long) = MemoryEventEntity(startAt=start,endAt=end,scene="其他",title="合成事件",summary="",pointsJson="[]",peopleJson="[]",place=null,emotion=null,intensity=null,important=false,createdAt=0,model="test")
    private fun repository() = LocalEnvSoundRepository(db,RuntimeEnvironment.getApplication().getSharedPreferences("182",0),scope,
        MutableStateFlow(EnvModelState.Ready),{}, { error("不运行识别") },{},now={10_000L},zoneProvider=app.resound.platform.SystemZone, io = kotlinx.coroutines.Dispatchers.IO)
    private inner class Probe<T>(source: Flow<T>) {
        val reads = Channel<Unit>(Channel.UNLIMITED)
        val values = Channel<T>(Channel.UNLIMITED)
        val job = scope.launch { source.onEach { reads.send(Unit) }.distinctUntilChanged().collect { values.send(it) } }
        suspend fun next(): T = withTimeout(3000) { reads.receive(); values.receive() }
        suspend fun unchanged() { withTimeout(3000) { reads.receive() }; assertNull(withTimeoutOrNull(100) { values.receive() }) }
    }

    @Test fun segmentCountsMatchFullTableIncludingEmptyAndAllStatuses() = runBlocking {
        assertEquals(QueueCounts(0,0,false),db.transcribe().observeQueueCounts().first())
        assertEquals(VoiceprintCounts(0,0),db.transcribe().observeVoiceprintCounts().first())
        listOf("Pending","Failed","Running","Done","Done","Quiet","SkippedEnv","Empty").forEachIndexed { i,s -> segment(i+1L,s,processed=if(i==4) 1 else null) }
        val old=db.transcribe().observeSegments().first()
        assertEquals(QueueCounts(old.count { it.status=="Pending" },old.count { it.status=="Failed" },old.any { it.status=="Running" }),db.transcribe().observeQueueCounts().first())
        assertEquals(VoiceprintCounts(old.count { it.status=="Done" && it.voiceprintProcessedAt!=null },old.count { it.status=="Done" && it.voiceprintProcessedAt==null }),db.transcribe().observeVoiceprintCounts().first())
    }
    @Test fun unrelatedSegmentEditsDoNotRecomputeQueueVoiceprintOrFailureConsumers() = runBlocking {
        val done=segment(1,processed=1)
        val queue=Probe(db.transcribe().observeQueueCounts());val voice=Probe(db.transcribe().observeVoiceprintCounts());val failures=Probe(db.transcribe().observeFailedSegments())
        queue.next();voice.next();failures.next()
        db.transcribe().update(done.copy(sentMillis=900,error="仅元数据"))
        queue.unchanged();voice.unchanged();failures.unchanged()
        segment(2,"Pending")
        assertEquals(1,queue.next().pending)
        voice.unchanged();failures.unchanged()
    }
    @Test fun failureRowsPreserveNullDeadlineAndAudioErrorChanges() = runBlocking {
        segment(1,"Done");val failed=segment(2,"Failed");segment(3,"Failed",failed=100)
        val old=db.transcribe().observeSegments().first().filter { it.status=="Failed" }
        assertEquals(old,db.transcribe().observeFailedSegments().first())
        assertEquals(259200101L,BackgroundRefreshPolicy.failureDeadline(old.map { it.firstFailedAt }))
        val probe=Probe(db.transcribe().observeFailedSegments());probe.next()
        db.transcribe().update(failed.copy(error="录音文件已不存在"))
        assertEquals("录音文件已不存在",probe.next().first { it.id==2L }.error)
    }
    @Test fun rangeAndRecentDoneKeepCrossBoundarySegmentsAndOrdering() = runBlocking {
        segment(1,start=50,end=110);segment(2,start=100,end=200);segment(3,"Failed",start=150,end=190);segment(4,start=200,end=250)
        val old=db.transcribe().observeSegments().first()
        assertEquals(old.filter { it.startAt<200 && it.endAt>100 }.sortedWith(compareBy({it.startAt},{it.id})),db.transcribe().observeOverlapping(100,200).first())
        assertEquals(old.filter { it.status=="Done" && it.startAt>=100 },db.transcribe().recentDone(100))
    }
    @Test fun usageTotalsKeepTodayTotalAndEmptyDaySemantics() = runBlocking {
        assertEquals(AsrUsageTotals(0,0),db.transcribe().observeUsageTotals("2026-11-13").first())
        db.transcribe().insertUsage(AsrUsageEntity("2026-11-12",1999));db.transcribe().insertUsage(AsrUsageEntity("2026-11-13",2999))
        val old=db.transcribe().observeUsage().first()
        for(day in listOf("2026-11-13","2026-11-14")) assertEquals(AsrUsageTotals(old.firstOrNull { it.day==day }?.sentMillis ?: 0,old.sumOf { it.sentMillis }),db.transcribe().observeUsageTotals(day).first())
        db.memory().addUsage("2026-11-13",7,11);db.memory().addUsage("2026-11-12",99,1)
        val oldTokens=db.memory().observeUsage().first().first { it.day=="2026-11-13" }
        assertEquals(oldTokens.promptTokens+oldTokens.completionTokens,db.memory().observeTokens("2026-11-13").first())
        val probe=Probe(db.memory().observeTokens("2026-11-13"));probe.next();db.memory().addUsage("2026-11-12",1,1);probe.unchanged()
    }
    @Test fun environmentDayAndHourlyMatchFullHistoryWithMidnightCrossing() = runBlocking {
        val date=LocalDate.of(2026,11,13);val start=date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();val end=date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val clips=listOf(segment(1,start=start-60_000,end=start+60_000),segment(2,start=start+60_000,end=start+180_000),segment(3,start=end,end=end+60_000))
        clips.forEach { db.envSound().insert(env(it,"Skipped")) }
        val all=db.envSound().observe().first();val repo=repository()
        assertEquals(all.filter { it.startAt<end && it.endAt>start }.map { it.view() },repo.flagged(date.toKotlinLocalDate()).first())
        assertEquals(hourlySounds(date,db.transcribe().observeSegments().first(),all,ZoneId.systemDefault()),repo.hourly(date.toKotlinLocalDate()).first())
        val probe=Probe(db.envSound().observeBetween(start,end));probe.next();db.envSound().wrong(3);probe.unchanged()
    }
    @Test fun environmentEventIncludesItsWholeSpanAndSynchronousAudioDoesNotNeedCache() = runBlocking {
        val date=LocalDate.of(2026,11,13);val start=date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val s=segment(1,start=start-120_000,end=start-60_000);db.envSound().insert(env(s))
        val id=db.memory().insertEvent(event(start-180_000,start+60_000));val repo=repository()
        val old=mergeSounds(db.envSound().events().first(),db.envSound().observe().first())
        assertEquals(old,repo.eventSounds(date.toKotlinLocalDate()).first());assertEquals(old[id],repo.eventSound(id))
        val f=File.createTempFile("env182", ".ogg", RuntimeEnvironment.getApplication().cacheDir)
        try {
            db.writeSql("UPDATE env_sounds SET path=? WHERE segmentId=1",arrayOf(f.path))
            assertEquals(app.resound.platform.FileRef(f.path),repo.audio(1));f.delete();assertNull(repo.audio(1))
        } finally { f.delete() }
    }
    @Test fun environmentRepositoryDoesNotReadAnyHistoryWithoutConsumers() = runBlocking {
        segment(1);db.envSound().insert(env(db.transcribe().segment(1)))
        val before=selects.get();repository()
        delay(100)
        assertEquals(before,selects.get())
    }
    @Test fun voiceInputHistoryIsColdAndKeepsNewestAppAndTodayCount() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        segment(1,start=start+1000);segment(2,start=start+2000)
        db.transcribe().insertLines(listOf(
            TranscriptLineEntity(segmentId=1,startAt=start+1000,endAt=start+1500,text="旧名字",speakerTag=null,voiceInputJson="""{"app":"旧名称","package_name":"synthetic.app"}"""),
            TranscriptLineEntity(segmentId=2,startAt=start+2000,endAt=start+2500,text="新名字",speakerTag=null,voiceInputJson="""{"app":"新名称","package_name":"synthetic.app"}""")))
        val before = selects.get()
        val input = app.resound.capture.AndroidVoiceInputCapture(context, db, scope)
        delay(100);assertEquals(before, selects.get())
        assertEquals("新名称",withTimeout(3000) { input.seenApps.first { it.isNotEmpty() } }.single().label)
        assertEquals(2,withTimeout(3000) { input.todayCount.first { it == 2 } })
    }
    @Test fun importReadinessMatchesOldFilterAndIgnoresNonImportWrites() = runBlocking {
        val record=ImportRecordRow(pendingId="r",fileName="合成",kind="Meeting",startAt=0,endAt=10000,byChannel=false,ingestComplete=true)
        val id=db.imports().insert(record);val s=segment(1,"Pending",record=id)
        val probe=Probe(db.imports().observeSummaryReady());assertTrue(probe.next().isEmpty())
        segment(2);probe.unchanged()
        db.transcribe().update(s.copy(status="Done",voiceprintProcessedAt=1))
        val actual=probe.next()
        val old=db.imports().observe().first().filter { it.row.ingestComplete && it.row.summaryRequested && it.row.status!="Summarizing" && it.segments.none { s -> s.status in setOf("Pending","Running") || s.status=="Done" && s.voiceprintProcessedAt==null } }.map { ImportSummaryReady(it.row.id,it.row.revision,it.segments.sortedBy { s -> s.id }.joinToString(",") { s -> s.status }) }
        assertEquals(old,actual)
        // Failed -> Empty 可能没有 Pending 中间态；仍须再次唤醒自动恢复纪要。
        db.transcribe().update(s.copy(status="Failed"));assertEquals("Failed",probe.next().single().statuses)
        db.transcribe().update(s.copy(status="Empty"));assertEquals("Empty",probe.next().single().statuses)
    }
    @Test fun topicProgressStartsOnlyForPendingAndCompletionWakesImmediately() = runBlocking {
        val dao=db.topics();val pending=MutableStateFlow(emptyList<Long>());val subscribed=AtomicInteger();val changes=Channel<List<TopicSegmentProgress>>(Channel.UNLIMITED)
        val job=scope.launch { pending.whileTopicsPending { ids -> subscribed.incrementAndGet();dao.observeProgress(ids) }.collect { changes.send(it) } }
        delay(50);assertEquals(0,subscribed.get())
        val id=dao.insert(TopicRow(kind="Normal",name=null,status="Ended",createdAt=0));dao.span(TopicSpanRow(topicId=id,startAt=1000,endAt=2000))
        val s=segment(1,"Pending",start=1000,end=1500);segment(2,"Pending",start=9000,end=9500)
        pending.value=dao.observePendingIds().first()
        assertEquals(listOf(TopicSegmentProgress(1,"Pending",null)),withTimeout(3000) { changes.receive() })
        db.transcribe().update(s.copy(status="Done",voiceprintProcessedAt=123))
        assertEquals(listOf(TopicSegmentProgress(1,"Done",123)),withTimeout(3000) { changes.receive() })
        db.transcribe().update(db.transcribe().segment(2).copy(status="Done"))
        assertNull(withTimeoutOrNull(150) { changes.receive() })
        pending.value=emptyList();job.cancelAndJoin()
        assertEquals(1,subscribed.get())
    }
    @Test fun doneTopicStillWakesForLateTranscriptButNotAlreadyAssignedLines() = runBlocking {
        val dao=db.topics();val eventId=db.memory().insertEvent(event(1000,2000))
        val id=dao.insert(TopicRow(kind="Normal",name=null,status="Done",createdAt=0,eventId=eventId));dao.span(TopicSpanRow(topicId=id,startAt=1000,endAt=2000))
        segment(1,start=1000,end=1500)
        val probe=Probe(dao.observeLateTranscriptIds());assertTrue(probe.next().isEmpty())
        db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId=1,startAt=1100,endAt=1200,text="迟到原话",speakerTag=null)))
        assertEquals(listOf(id),probe.next())
        db.memory().assignLines(db.transcribe().linesForRedo(1).map { it.id },eventId);db.memory().markDigested(db.transcribe().linesForRedo(1).map { it.id },2000)
        assertTrue(withTimeout(3000) { dao.observeLateTranscriptIds().first { it.isEmpty() } }.isEmpty())
    }
    @Test fun checkpointRefreshReadsOnlyTheObservedTopics() = runBlocking {
        val reads=mutableListOf<Long>()
        val files=TopicCheckpointFiles(RuntimeEnvironment.getApplication()) { id -> reads += id; "source$id" }
        try {
            files.write(1,TopicCheckpoint("source1"));files.write(2,TopicCheckpoint("source2"));reads.clear()
            files.refresh(setOf(1));assertEquals(listOf(1L),reads)
            assertNotNull(files.read(2))
        } finally { files.remove(1);files.remove(2) }
    }
    @Test fun currentTopicQueryMatchesOldSelectionAndPendingPolicy() = runBlocking {
        val dao=db.topics()
        listOf("Done","Failed","Ended","Summarizing","Running","Paused","Scheduled").forEachIndexed { i,status -> dao.insert(TopicRow(kind="Normal",name=null,status=status,createdAt=i.toLong())) }
        val all=dao.observeAll().first()
        assertEquals(all.first { it.row.status in setOf("Running","Paused") },dao.observeCurrent().first())
        assertEquals(all.filter { TopicObservationPolicy.needsProgress(it.row.status) }.map { it.row.id },dao.observePendingIds().first())
    }
}
