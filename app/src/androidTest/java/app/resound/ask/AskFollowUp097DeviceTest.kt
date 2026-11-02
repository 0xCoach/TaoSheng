package app.resound.ask

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.data.CaptureDatabase
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.DigestPrompt
import app.resound.digest.LlmClient
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 显式启用才请求当前模型；只发送内存数据库中的合成资料，最多四次网络请求。 */
@RunWith(AndroidJUnit4::class)
class AskFollowUp097DeviceTest {
    @Test fun currentModelAnswersSyntheticFollowUpWithValidSources() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticCloud") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppGraph.digestSettings as AndroidDigestSettings
        val (preset, key) = synchronized(settings.providers.guard) { settings.preset to settings.readKey() }
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .addCallback(app.resound.search.SearchIndex.callback).build()
        var calls = 0; var tokens = 0L
        try {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/synthetic',1,'Done')")
            sql.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(9,1000,2000,'会议','纸飞机项目测试会议','试运行两周后复盘','[]','[]',0,0,2000,'fake')")
            sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(42,1,1000,2000,'大家最终决定：纸飞机项目先试运行两周，之后开会复盘。今天没有批准正式推广。',9,'测试员')")
            fun asset(name: String) = context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
            val definitions = JSONArray(asset("ask-tools.json"))
            val tools = AskTools(db, JSONObject(asset("ask-sql.json")))
            val reader = AskContextReader(db)
            val client = LlmClient()
            val engine = AskEngine(AskStream { messages, requested, delta, usage ->
                check(++calls <= 4) { "Synthetic request limit reached" }
                client.streamChat(preset, key, messages, definitions, delta, usage, allowTools = requested.length() > 0)
            }, definitions, tools::execute, { p, c ->
                tokens += p + c
                (context.applicationContext as ResoundApp).database.memory().addUsage(LocalDate.now().toString(), p, c, UsagePurpose.Ask)
            })
            val system = DigestPrompt.system(AppGraph.prompts.text("ask"), LocalDate.now(), "涛声")
            val history = mutableListOf<AskMessage>()
            for ((index, question) in listOf("纸飞机项目会议决定了什么？", "最后结论是啥").withIndex()) {
                val evidence = if (index == 0) reader.read(AskSource("event", 9, 1000, "", "")) else reader.followUp(history)
                var result: AskEngine.Update? = null
                withTimeout(120_000) { engine.answer(system, history, question, evidence) { result = it } }
                val answer = requireNotNull(result)
                assertNull("Synthetic answer must pass citation checks", answer.error)
                assertTrue(answer.sources.isNotEmpty())
                assertTrue(answer.text.contains("两周") || answer.text.contains("2周") || answer.text.contains("两星期"))
                assertFalse(AskCitations.parse(answer.text, answer.sources).invalid)
                history += AskMessage(index * 2L, AskRole.User, question, emptyList(), 0, false)
                history += AskMessage(index * 2L + 1, AskRole.Assistant, answer.text, answer.sources, 0, false)
                android.util.Log.i("AskFollowUp097", "turn=${index + 1} cited=${answer.sources.size} chars=${answer.text.length} calls=$calls")
            }
        } finally {
            db.close()
            android.util.Log.i("AskFollowUp097", "synthetic_calls=$calls tokens=$tokens")
        }
    }
}
