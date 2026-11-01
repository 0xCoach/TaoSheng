package app.resound.things

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.ask.AskSource
import app.resound.search.SearchIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.*

/** 显式启用才调用真实模型；只发送自编样例，密钥和手机数据不进入产物。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ThingsSharedLiveTest {
    private data class Expected(val line: Int,val action: String,val location: String?=null)
    private data class Case(val name: String,val lines: List<String>,val expected: List<Expected>,val previous: String?=null)
    @Test fun liveExtractionAndTokenComparison()=runBlocking {
        assumeTrue(System.getenv("TAOSHENG_LIVE_THINGS")=="1")
        val key=File(System.getProperty("user.home"),".config/resound/deepseek.key").readText().trim()
        val context=RuntimeEnvironment.getApplication()
        val output=File("build/things-live").apply { mkdirs() }
        val baseline = if (System.getenv("TAOSHENG_LIVE_REUSE_BASELINE") == "1") JSONObject(File(output,"initial-results.json").readText()).getJSONArray("cases") else null
        val report=JSONArray()
        val failures=mutableListOf<String>()
        val cases=listOf(
            Case("位置与跨句",listOf("钥匙我搁鞋柜第二个抽屉了。","伞在门后。","我的扳手放哪儿了？","它就在工具箱最下面。","护照塞行李箱夹层了，耳机收在床头柜。","帮我记住，备用门卡在玄关小盒子里。"),
                listOf(Expected(1,"Placed","鞋柜"),Expected(2,"Placed","门后"),Expected(4,"Placed","工具箱"),Expected(5,"Placed","行李箱"),Expected(5,"Placed","床头柜"),Expected(6,"Placed","玄关"))),
            Case("借还与带走",listOf("电钻借给老张了。","老李把相机还回来了。","老婆把蓝色保温杯带出门了。","我那副墨镜搁门口矮凳底下咧。","明天提醒我把充电器借给小王。"),
                listOf(Expected(1,"Lent","老张"),Expected(2,"Returned"),Expected(3,"TakenAway"),Expected(4,"Placed","矮凳"))),
            Case("排除与前批上下文",listOf("收妥了，就按刚说的那个地方。","待会儿我把相机放车里。","你把剪刀放厨房抽屉里。","要是有保险柜，我就把金条放里面。","电视里那个人说：我把钻石塞在床垫下面了。","项目文件在服务器上，钱还在银行卡里。","这个寻物功能应该能识别‘我把手表放书柜了’，提示词怎么写？","今天的方案讨论结束了，我们下周继续。"),
                listOf(Expected(1,"Placed","衣柜")),"红围巾就收衣柜上层吧。")
        )
        val preset=LlmPreset("test-deepseek","DeepSeek","https://api.deepseek.com","deepseek-chat")
        var responseModel=""
        val http=OkHttpClient.Builder().callTimeout(200,java.util.concurrent.TimeUnit.SECONDS).readTimeout(180,java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request()).also { response ->
                responseModel=runCatching { JSONObject(response.peekBody(4_000_000).string()).optString("model") }.getOrDefault("")
            } }.build()
        val client=LlmClient(http)
        val digestTemplate=context.assets.open("prompts/digest.md").bufferedReader().readText()
        val thingTemplate=context.assets.open("prompts/things.md").bufferedReader().readText()
        for ((ci,case) in cases.withIndex()) {
            val db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).allowMainThreadQueries()
                .addCallback(SearchIndex.callback).addCallback(ThingSchema.callback).build()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val calls=JSONArray()
            val day=LocalDate.now();val start=day.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val repo=RoomThingRepository(db,scope,{error("no manual call")},now={start})
            suspend fun call(kind: String,system: String,input: String): LlmReply {
                val begin=System.currentTimeMillis()
                val reply=client.chatJson(preset,key,system,input)
                calls.put(JSONObject().put("kind",kind).put("model",responseModel).put("promptTokens",reply.promptTokens)
                    .put("completionTokens",reply.completionTokens).put("elapsedMs",System.currentTimeMillis()-begin)
                    .put("system",system).put("input",input)
                    .put("response",JSONObject(reply.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))))
                File(output,"progress.json").writeText(report.toString(2))
                return reply
            }
            try {
                db.capture().segment(SegmentEntity(7,start-60000,start+60000,"mic","synthetic",0,"Done"))
                val previous=case.previous?.let { listOf(TranscriptLineEntity(10,7,start-30000,start-29000,it,null,"我",digestedAt=start)) } ?: emptyList()
                val lines=case.lines.mapIndexed { i,text -> TranscriptLineEntity(1001L+i,7,start+i*1000L,start+i*1000L+900,text,null,"我") }
                db.transcribe().insertLines(previous+lines)
                if(ci==1) { repo.add(listOf(ThingDraft("电钻",ThingAction.Placed,"工具箱",null)),ThingOrigin.Typed,"测试已知物品");repo.flush() }
                val known=things(db.things().allThings(),db.things().allRecords())
                val shared=DigestThingExtraction(db,repo,{thingTemplate},now={start})
                var sharedCalls=0
                val runner=DigestRunner(context,db.memory(),client,AndroidDigestSettings(context,client),{digestTemplate},{true},now={start+120000},readKey={key},thingExtraction=shared,
                    request={_,_,system,input -> sharedCalls++;call("shared",system,input)})
                val retry=runner.run(force=true)
                val records=db.things().allRecords().filter { it.sourceLineId!=null }
                val actual=JSONArray(records.map { JSONObject().put("line",it.sourceLineId!!-1000).put("name",it.name).put("action",it.action).put("place",it.place).put("who",it.who).put("knownId",it.thingId).put("explicit",it.explicit) })
                for(e in case.expected) if(records.none { it.sourceLineId==1000L+e.line && it.action==e.action && (e.location==null || (it.place.orEmpty()+it.who.orEmpty()).contains(e.location)) }) failures+="${case.name}: missed $e"
                if(ci==0 && records.none { it.sourceLineId==1006L && it.explicit }) failures+="${case.name}: explicit request not marked"
                if(ci==1 && records.none { it.sourceLineId==1001L && it.thingId==known.single().id }) failures+="${case.name}: known object not linked"
                if(records.size!=case.expected.size) failures+="${case.name}: expected ${case.expected.size}, got ${records.size}"
                if(retry || sharedCalls!=1 || db.memory().undigestedLines().isNotEmpty()) failures+="${case.name}: shared pipeline incomplete"
                if(db.memory().refreshLines(lines.map { it.id }).any { it.eventId==null }) failures+="${case.name}: timeline coverage incomplete"
                for(r in records) {
                    val sources=mutableListOf<AskSource>()
                    val found=FindThingsTool(db,ZoneId.systemDefault()).execute(JSONObject().put("query",r.name),sources)
                    if(found.getJSONArray("results").length()==0 || sources.none { it.id==r.sourceLineId }) failures+="${case.name}: find/source mismatch"
                }
                // 相同原话、同模型：旧版整理 + 独立寻物；基线不写入数据库。
                val (text,_)=DigestPrompt.transcript(lines)
                if (baseline != null) {
                    val saved = baseline.getJSONObject(ci).getJSONArray("calls")
                    for(i in 0 until saved.length()) if(saved.getJSONObject(i).getString("kind") != "shared") calls.put(saved.getJSONObject(i).put("reusedBaseline",true))
                } else {
                call("old-digest",DigestPrompt.system(digestTemplate,day,"涛声"),DigestPrompt.withTodoContext(DigestPrompt.withHotwords(text,emptyList()),emptyList(),emptyList()))
                call("old-things",File(output,"old-things.md").readText().replace("{{CALENDAR}}",DigestPrompt.calendar(day)),ThingPrompt.input(lines,previous,known))
                }
                if(records.any { it.action in listOf("Placed","TakenAway") && it.who != null }) failures += "${case.name}: invalid borrower"
                report.put(JSONObject().put("case",case.name).put("expectedFacts",case.expected.size).put("actual",actual).put("calls",calls))
                File(output,"results.json").writeText(JSONObject().put("cases",report).put("failures",JSONArray(failures)).toString(2))
                println("LIVE case=${case.name} facts=${records.size}/${case.expected.size} sharedCalls=$sharedCalls")
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin();db.close() }
        }
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
