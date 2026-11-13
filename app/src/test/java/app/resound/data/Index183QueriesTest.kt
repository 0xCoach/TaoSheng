package app.resound.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.topic.TopicRow
import app.resound.topic.TopicSpanRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Index183QueriesTest {
    private val old = JSONObject(File("src/test/resources/index183/old-queries.json").readText())
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CaptureDatabase::class.java)
        .setDriver(AndroidSQLiteDriver()).build()
    private suspend fun values(db: CaptureDatabase, key: String, args: Map<String,Any?> = emptyMap()): List<Long?> {
        val sql = old.getString(key)
        val ordered = Regex(":([a-zA-Z][a-zA-Z0-9]*)").findAll(sql).map { it.groupValues[1] }.distinct().map { args.getValue(it) }.toList().toTypedArray()
        return db.readSql(sql,ordered) { c -> buildList { while(c.step()) add(if(c.isNull(0))null else c.getLong(0)) } }
    }
    private suspend fun ids(db: CaptureDatabase, key: String, actual: List<Long>, args: Map<String,Any?> = emptyMap()) {
        val expected=values(db,key,args).map { requireNotNull(it) }
        if(old.getString(key).contains("ORDER BY"))assertEquals(key,expected,actual)
        else assertEquals(key,expected.sorted(),actual.sorted())
    }
    private fun event(deleted: Boolean=false) = MemoryEventEntity(startAt=0,endAt=500,scene="其他",title="合成",summary="",pointsJson="[]",peopleJson="[]",place=null,emotion=null,intensity=null,important=false,createdAt=0,model="synthetic",deleted=deleted)

    @Test fun allRewrittenFiltersAndScansMatchOldSqlOnMixedRows() = runBlocking(Dispatchers.IO) {
        val db=database()
        try {
            val visible=db.memory().insertEvent(event());val hidden=db.memory().insertEvent(event(true))
            val statuses=listOf("Done","Done","Failed","Failed","Running","Done","Done","Empty","Pending","Done")
            val starts=listOf(100L,200L,150L,175L,200L,50L,290L,400L,250L,200L)
            statuses.forEachIndexed { i,status ->
                val id=i+1L
                db.capture().segment(SegmentEntity(id,starts[i],starts[i]+400,if(i==6)"voice-input" else "mic","synthetic-$id",100,status,
                    completedAt=starts[i]+10,voiceprintProcessedAt=if(i%2==0) 100-id else null))
                db.transcribe().insertLines(listOf(TranscriptLineEntity(id=id,segmentId=id,startAt=starts[i],endAt=starts[i]+400,text="合成$id",speakerTag="speaker",
                    eventId=if(i==1) hidden else if(i==6) visible else null,digestedAt=if(i%3==0)1 else null,
                    speakerPersonId=1,voiceInputJson=if(i==6)"{}" else null)))
            }
            val range=mapOf<String,Any?>("start" to 100L,"end" to 350L)
            ids(db,"data/TranscribeDao.kt#recentDone",db.transcribe().recentDone(100).map { it.id },mapOf("since" to 100L))
            ids(db,"data/TranscribeDao.kt#expired",db.transcribe().expired(500).map { it.id },mapOf("before" to 500L))
            // Failed的声纹时间刻意与id顺序相反，新增索引不能改变旧失败列表的扫描顺序。
            assertEquals(listOf(3L,4L),db.transcribe().observeFailedSegments().first().map { it.id })
            ids(db,"data/VoiceprintDao.kt#doneSegments",db.voiceprint().doneSegments().map { it.id })
            ids(db,"data/VoiceInputDao.kt#observed",db.voiceInput().observed().first().map { it.id })
            ids(db,"data/VoiceprintDao.kt#observePersonLines",db.voiceprint().observePersonLines(1).first().map { it.id },mapOf("id" to 1L))
            ids(db,"data/MemoryDao.kt#undigestedLines",db.memory().undigestedLines().map { it.id })
            assertEquals(values(db,"data/MemoryDao.kt#undigestedCount").single(),db.memory().undigestedCount().toLong())
            assertEquals(values(db,"data/MemoryDao.kt#observeUndigestedCount").single(),db.memory().observeUndigestedCount().first().toLong())
            ids(db,"data/MemoryDao.kt#diaryLines",db.memory().diaryLines(100,350).map { it.id },range)
            ids(db,"skills/SkillDao.kt#lines",db.skills().lines(100,350).map { it.id },range)
            ids(db,"push/PushRecords.kt#lines",db.push().lines(100).map { it.id },mapOf("start" to 100L))
            for(include in listOf(false,true))assertEquals(values(db,"data/MemoryDao.kt#latestDiaryAt",mapOf("includeLines" to include)).single(),db.memory().latestDiaryAt(include))
            val before=db.transcribe().allAudio()
            db.writeSql(old.getString("data/VoiceprintDao.kt#invalidate"))
            val expected=db.transcribe().allAudio()
            before.forEach { db.transcribe().update(it) };db.voiceprint().invalidate()
            assertEquals(expected,db.transcribe().allAudio())
        } finally { db.close() }
    }
    @Test fun overlappingTopicSpansNullEventsBoundariesAndLateLinesMatchOldSql() = runBlocking(Dispatchers.IO) {
        val db=database()
        try {
            val event=db.memory().insertEvent(event())
            val first=db.topics().insert(TopicRow(kind="Normal",name=null,status="Done",createdAt=0,eventId=event))
            val second=db.topics().insert(TopicRow(kind="Normal",name=null,status="Done",createdAt=0))
            db.topics().span(TopicSpanRow(topicId=first,startAt=100,endAt=300));db.topics().span(TopicSpanRow(topicId=first,startAt=150,endAt=350))
            db.topics().span(TopicSpanRow(topicId=first,startAt=999,endAt=null));db.topics().span(TopicSpanRow(topicId=second,startAt=350,endAt=400))
            for(id in 1L..3L)db.capture().segment(SegmentEntity(id,0,1000,"mic","t$id",1,if(id==3L)"Failed" else "Done"))
            val lines=listOf(
                TranscriptLineEntity(1,1,100,500,"已归属",null,eventId=event,digestedAt=1),
                TranscriptLineEntity(2,1,150,200,"未整理",null,eventId=event),
                TranscriptLineEntity(3,1,299,400,"语音输入等待",null,voiceInputPendingUntil=400),
                TranscriptLineEntity(4,2,350,400,"空事件已整理",null,digestedAt=1),
                TranscriptLineEntity(5,3,200,400,"失败段",null),
                TranscriptLineEntity(6,1,349,500,"归属不同",null,eventId=999,digestedAt=1),
                TranscriptLineEntity(7,1,400,500,"右边界不含",null))
            db.transcribe().insertLines(lines)
            for(id in listOf(first,second))ids(db,"topic/TopicData.kt#lines",db.topics().lines(id).map { it.id },mapOf("id" to id))
            assertEquals(listOf(1L,2L,6L),db.topics().lines(first).map { it.id })
            assertEquals(values(db,"topic/TopicData.kt#observeLateTranscriptIds"),db.topics().observeLateTranscriptIds().first())
            assertEquals(listOf(first),db.topics().observeLateTranscriptIds().first())
            db.memory().assignLines(listOf(2,6),event);db.memory().markDigested(listOf(2,6),600)
            assertEquals(values(db,"topic/TopicData.kt#observeLateTranscriptIds"),db.topics().observeLateTranscriptIds().first())
            assertTrue(db.topics().observeLateTranscriptIds().first().isEmpty())
            assertEquals(values(db,"topic/TopicData.kt#digestedUntil",mapOf("start" to 200L,"end" to 250L)).single(),db.topics().digestedUntil(200,250))
            assertEquals(500L,db.topics().digestedUntil(200,250))
        } finally { db.close() }
    }
}
