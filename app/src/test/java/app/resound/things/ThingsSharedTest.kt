package app.resound.things

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.ask.AskSource
import app.resound.search.SearchIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ThingsSharedTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: RoomThingRepository
    private lateinit var shared: DigestThingExtraction
    private val now = 1_800_000_000_000L
    private val item = """{"name":"钥匙","aliases":[],"action":"Placed","place":"鞋柜","who":null,"owner":"Mine","lineId":1,"explicit":true,"knownId":null,"confident":true}"""
    private fun response(things: String = "[$item]", scene: String = "其他") = """{"events":[{"from":1,"to":1,"scene":"$scene","title":"收好钥匙","summary":"钥匙在鞋柜"}],"items":[],"things":$things}"""
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).addCallback(ThingSchema.callback).build()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo=RoomThingRepository(db,scope,{ error("no manual request") },now={now})
        shared=DigestThingExtraction(db,repo,{context.assets.open("prompts/things.md").bufferedReader().readText()},now={now})
    }
    @After fun close() { scope.cancel();db.close() }
    private suspend fun seed(source: String="mic") {
        db.capture().segment(SegmentEntity(7,now-1000,now,source,"synthetic",0,"Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(42,7,now-1000,now,"钥匙已放在鞋柜第二层",null)))
    }
    private fun runner(reply: suspend (String,String)->String): DigestRunner {
        val client=LlmClient()
        return DigestRunner(context,db.memory(),client,AndroidDigestSettings(context,client),{"整理"},{true},
            now={now},readKey={"synthetic"},thingExtraction=shared,
            request={_,_,system,input->LlmReply(reply(system,input),100,20)})
    }
    @Test fun oneRequestStoresBothAndAskFindsRealSourceWithoutDoubleUsage()=runBlocking {
        seed(); var calls=0
        val runner=runner { system,input ->
            calls++;assertTrue(system.contains("things"));assertFalse(input.contains("\"lines\""))
            assertEquals(1,Regex("钥匙已放在鞋柜第二层").findAll(input).count())
            response()
        }
        assertFalse(runner.run(force=true));assertFalse(runner.run(force=true))
        assertEquals(1,calls);assertTrue(db.memory().undigestedLines().isEmpty())
        assertEquals(42L,db.things().allRecords().single().sourceLineId)
        val sources=mutableListOf<AskSource>()
        assertTrue(FindThingsTool(db,java.time.ZoneId.systemDefault()).execute(JSONObject().put("query","钥匙"),sources).toString().contains("鞋柜"))
        assertEquals(42L,sources.single().id)
        val usage=db.memory().usageByPurpose("0000","9999").first()
        assertEquals(1,usage.size);assertEquals("Digest",usage.single().purpose)
    }
    @Test fun disabledDuringRequestDiscardsThingButKeepsTimeline()=runBlocking {
        seed()
        runner { _,_->repo.setAutoDetect(false);repo.flush();response() }.run(force=true)
        assertTrue(db.things().allRecords().isEmpty());assertTrue(db.memory().undigestedLines().isEmpty())
    }
    @Test fun importDoesNotAddInstructionsAndMediaDoesNotCreateThings()=runBlocking {
        seed("import")
        runner { system,_->assertFalse(system.contains("附加任务"));response() }.run(force=true)
        assertTrue(db.things().allRecords().isEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE segments SET sourceId='mic' WHERE id=7")
        db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET digestedAt=NULL WHERE id=42")
        runner { _,_->response(scene="媒体") }.run(force=true)
        assertTrue(db.things().allRecords().isEmpty())
    }
    @Test fun badThingDoesNotRetryTimelineAndValidSiblingStillSaves()=runBlocking {
        seed();var calls=0
        runner { _,_->calls++;response("[{\"lineId\":999},$item]") }.run(force=true)
        assertEquals(1,calls);assertEquals(1,db.things().allRecords().size)
        assertTrue(db.memory().undigestedLines().isEmpty())
        assertTrue(db.capture().events(0,Long.MAX_VALUE).first().single { it.type=="ThingsShared" }.detail!!.contains("rejected=1"))
    }
    @Test fun deletedSourceAndFailedTransactionCannotLeaveOrphanThing()=runBlocking {
        seed();val batch=shared.prepare(db.memory().undigestedLines())!!
        assertTrue(runCatching { shared.commit(batch,response(),emptyList()) {
            db.memory().markDigested(listOf(42),now);error("storage failure")
        } }.isFailure)
        assertEquals(1,db.memory().undigestedLines().size);assertTrue(db.things().allRecords().isEmpty())
        db.privacy().deleteLine(42)
        shared.commit(batch,response(),emptyList()) {}
        assertTrue(db.things().allRecords().isEmpty())
    }
    @Test fun reprocessingDoesNotRecreateIgnoredFact()=runBlocking {
        seed();runner{_,_->response()}.run(force=true)
        repo.ignore(db.things().allRecords().single().id);repo.flush()
        db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET digestedAt=NULL WHERE id=42")
        runner{_,_->response()}.run(force=true)
        assertEquals("Ignored",db.things().allRecords().single().status)
    }
}
