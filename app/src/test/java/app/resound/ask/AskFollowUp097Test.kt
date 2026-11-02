package app.resound.ask

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.digest.LlmStreamReply
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AskFollowUp097Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun database(): CaptureDatabase = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build().also { db ->
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/synthetic',1,'Done')")
        sql.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(9,1000,2000,'会议','样例评审','先试运行','[]','[]',0,0,2000,'fake')")
        sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(42,1,1000,2000,'最终决定先试运行两周，再复盘。',9,'我')")
        sql.execSQL("INSERT INTO memory_items(id,eventId,category,content,lineId,status,createdAt) VALUES(7,9,'待办','安排复盘',42,'Pending',2000)")
    }
    private fun source(kind: String, id: Long) = AskSource(kind, id, 0, "旧标题不应发送", "旧原话不应发送")

    @Test fun eventConversationCanResumeAndAnswerTwoFollowUpsWithoutExtraSearch() = runBlocking {
        val db = database()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var calls = 0
        fun repository() = RoomAskRepository(db.ask(), scope, { true }, engine = {
            AskEngine(AskStream { messages, _, _, usage ->
                calls++; usage(10, 3)
                val request = messages.getJSONObject(messages.length() - 1).getString("content")
                assertTrue(request.contains("最终决定先试运行两周"))
                assertFalse(request.contains("旧原话不应发送"))
                if (calls > 1) assertTrue(request.contains("重新核验"))
                LlmStreamReply("先试运行两周，再复盘。[^${if (calls == 1) 2 else 1}]", emptyList())
            }, JSONArray(), { _, _, _ -> error("不应重复搜索") }, { _, _ -> })
        }, system = { "根据原话回答" }, recommended = AskSuggestions(context.getSharedPreferences("follow-up097", 0), { false }, { "" }, { emptyList() }),
            contextReader = AskContextReader(db))
        try {
            var repo = repository()
            repo.askAbout(source("event", 9), "会议决定了什么")
            withTimeout(5000) { repo.messages.first { it.size == 2 && !it.last().streaming } }
            val first = repo.messages.value
            assertNull(first.last().error)
            assertEquals(9L, first.first().sources.single().id)
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo = repository()
            withTimeout(5000) { repo.messages.first { it.size == 2 } }
            repo.ask("最后结论是啥")
            val second = withTimeout(5000) { repo.messages.first { it.size == 4 && !it.last().streaming } }
            assertNull(second.last().error); assertEquals(42L, second.last().sources.single().id)
            repo.ask("再简短一点")
            val third = withTimeout(5000) { repo.messages.first { it.size == 6 && !it.last().streaming } }
            assertNull(third.last().error); assertEquals("先试运行两周，再复盘。[^1]", third.last().text)
            assertEquals(3, calls)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun refreshUsesCurrentRecordsBoundsContextAndDoesNotResendDeletedEvidence() = runBlocking {
        val db = database()
        try {
            val reader = AskContextReader(db)
            val references = listOf(source("event", 9), source("item", 7), source("line", 42))
            val previous = AskMessage(1, AskRole.Assistant, "已删除前的结论[^1]", references, 0, false)
            val fresh = requireNotNull(reader.followUp(listOf(previous)))
            assertEquals(3, fresh.sources.size)
            assertTrue(fresh.sources.all { it.quote.contains("最终决定先试运行") })
            assertFalse(fresh.text.contains("旧原话不应发送")); assertFalse(fresh.text.contains("旧标题不应发送"))
            db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET text='现已取消试运行。' WHERE id=42")
            assertTrue(reader.followUp(listOf(previous))!!.sources.all { it.quote == "现已取消试运行。" })
            val anchor = AskMessage(2, AskRole.User, "就这个问问", listOf(source("event", 9)), 0, false)
            assertTrue(reader.followUp(listOf(anchor))!!.text.contains("现已取消试运行"))
            db.openHelper.writableDatabase.execSQL("UPDATE memory_events SET deleted=1 WHERE id=9")
            val deleted = reader.followUp(listOf(previous))!!
            assertTrue(deleted.sources.isEmpty()); assertFalse(deleted.text.contains("现已取消"))
            var sent = ""
            AskEngine(AskStream { messages, _, _, _ -> sent = messages.toString(); LlmStreamReply("没找到仍可核实的记录", emptyList()) },
                JSONArray(), { _, _, _ -> error("不用工具") }, { _, _ -> })
                .answer("", listOf(previous), "最后结论是啥", deleted) { assertNull(it.error) }
            assertFalse(sent.contains("已删除前的结论"))
            val sql = db.openHelper.writableDatabase
            for (id in 100..120) sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(?,1,1000,2000,?)", arrayOf(id, "长原话".repeat(500)))
            val many = previous.copy(sources = (100L..120L).map { source("line", it) })
            val bounded = reader.followUp(listOf(many))!!
            assertTrue(bounded.sources.size in 1..8); assertTrue(bounded.text.length < 6300)
        } finally { db.close() }
    }
}
