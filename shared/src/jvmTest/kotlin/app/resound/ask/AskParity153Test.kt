@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.ask

import app.resound.ask.core.*
import app.resound.net.*
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.junit.Assert.*
import org.junit.Test

class AskParity153Test {
    private fun sample(name: String) = javaClass.getResource("/parity153/$name")!!.readText()
    private val zone = TimeZone.of("Asia/Shanghai")
    private val source = AskSource("line", 42, 1000, "我", "方案/一\n第二行")
    private fun tools(rows: List<String> = emptyList()) = AskToolsCore(object : AskToolStore {
        override suspend fun read(query: AskToolsCore.Query) = rows
        override fun terms(words: List<String>) = words.map { "\"$it\"" }
        override suspend fun semantic(names: List<String>, args: String, text: String, stays: List<app.resound.context.PlaceAt>?) = emptyMap<String,List<Pair<Long,Double>>>()
        override fun merge(keyword: List<Long>, semantic: List<Long>) = (keyword + semantic).distinct().take(10)
        override suspend fun findThings(args: String, sources: MutableList<AskSource>) = error("不应调用寻物")
    },sample("spec.json"),zone)

    @Test fun definitionsMatchRecordedBytes() { assertEquals(sample("definitions.json"),AskDefinitions.json()) }
    @Test fun fullSystemPromptMatchesRecordedBytes() {
        assertEquals(sample("prompt.txt"),app.resound.digest.core.DigestPrompt.system(sample("template.md"),LocalDate(2026,11,10),"涛声").replace("{{TODAY}}","2026-11-10"))
    }
    private fun system(mode: AskMode) = runBlocking {
        val engine = AskEngineCore(AskModel { messages,_,_,_->
            assertEquals(sample("system-${mode.name}.json"),messages); reply("[无需记录]你好",emptyList())
        },AskDefinitions.json(),{_,_,_->error("不应查询")},{_,_->},mode={mode},prefetch={_,_->AskContext("固定资料",emptyList())})
        engine.answer("固定系统词",emptyList(),"方案") {}
    }
    @Test fun toolsSystemMatchesRecordedBytes() = system(AskMode.Tools)
    @Test fun fallbackSystemMatchesRecordedBytes() = system(AskMode.Prefetch)
    @Test fun citationEnvelopeMatchesRecordedBytes() { assertEquals(sample("citations.json"),AskCitations.encodeMessage(listOf(source),AskMode.Prefetch)) }
    @Test fun resultMatchesRecordedBytes() = runBlocking {
        val row=Obj(sample("result.json")).getJSONArray("results").getJSONObject(0).apply { remove("ref");remove("time") }
        val sources=mutableListOf<AskSource>()
        assertEquals(sample("result.json"),tools(listOf(row.toString())).execute("search_lines","{\"keywords\":[]}",sources))
        assertEquals(listOf(source.copy(quote="方案/一")),sources)
    }
    @Test fun sqlMatchesRecordedBytesAndRejectsUnknownArguments() {
        val core=tools(); val query=core.query("search_lines","{\"keywords\":[]}")
        assertEquals(sample("query.json"),Obj().put("sql",query.sql).put("kind",query.kind).toString())
        assertTrue(query.args.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { core.query("search_events","{\"sql\":\"DROP TABLE\"}") }
        assertThrows(IllegalArgumentException::class.java) { core.query("search_events","{\"starred_only\":\"true\"}") }
    }
    @Test fun completeMultiRoundPreservesToolOrderAndDenseReferences() = runBlocking {
        val operations=mutableListOf<String>(); var round=0; var final: AskEngineCore.Update?=null
        val engine=engine(TestStream { messages,_,delta,_->
            when(round++) {
                0 -> reply("",listOf(tool("a","search_lines","{\"keywords\":[\"方案\"]}")))
                1 -> { assertEquals("a",messages.getJSONObject(3).getString("tool_call_id")); reply("",listOf(tool("b","search_events","{}"))) }
                else -> {
                    assertEquals("b",messages.getJSONObject(5).getString("tool_call_id"))
                    delta("结论[^2]、原话[^1]"); reply("结论[^2]、原话[^1]",emptyList())
                }
            }
        },Arr(AskDefinitions.json()),{ name,_,sources ->
            operations+=name; sources+=if(name=="search_lines") source else source.copy(kind="event",id=9)
            Obj().put("ref",sources.size)
        },{_,_->})
        engine.answer("固定系统词",emptyList(),"方案") {final=it}
        assertEquals(listOf("search_lines","search_events"),operations)
        assertEquals("结论[^1]、原话[^2]",final!!.text)
        assertEquals(listOf(9L,42L),final!!.sources.map {it.id}); assertNull(final!!.error)
    }
    @Test fun strictDatesAndDstMinuteAdditionPreserveLocalTimeSemantics() {
        assertThrows(IllegalArgumentException::class.java) { AskTime.timestamp("2026-02-30",false,zone) }
        val ny=TimeZone.of("America/New_York")
        // 旧逻辑先加本地分钟，再解析重叠时段，默认使用较早偏移。
        assertEquals(java.time.LocalDateTime.of(2026,11,1,2,0).atZone(java.time.ZoneId.of(ny.id)).toInstant().toEpochMilli(),
            AskTime.timestamp("2026-11-01 01:59",true,ny))
        assertEquals("2026-11-30 23:59",Obj(AskPrefetchCore.timeRange("这个月",LocalDate(2026,11,10))).getString("to"))
    }
    @Test fun suggestionFailureConsumesReservationAndRestartHonorsCooldown() = runBlocking {
        var savedDay: String?=null;var count=0;var savedAt=0L;var time=1_000_000L;var calls=0
        val store=object: AskSuggestionStore {
            override fun day()=savedDay; override fun count()=count;override fun at()=savedAt
            override fun questions()="[]"
            override fun reserve(day: String,newCount: Int,at: Long): Boolean {
                savedDay=day;count=newCount;savedAt=at;return true
            }
            override fun save(questions: String)=true
        }
        fun instance()=AskSuggestionsCore(store,{true},{"事件"},{calls++;error("假失败")},NowMillis {time},ZoneProvider {"UTC"})
        instance().refresh(); instance().refresh()
        assertEquals(1,calls); assertEquals(1,count)
        time+=6*60*60_000L;instance().refresh();time+=6*60*60_000L;instance().refresh()
        assertEquals(2,calls);assertEquals(2,count)
    }
    @Test fun contextRefreshDropsDeletedReferencesAndBoundsQuotes() = runBlocking {
        var deleted=false
        val store=object: AskContextStore {
            override suspend fun refresh(source: AskSource)=if(deleted) null else Obj().put("at",1000).put("title","我").put("quote","新原话").toString()
            override suspend fun record(source: AskSource)=AskContextRecord(9,1000,"会议","摘要",null,null)
            override suspend fun lines(eventId: Long?)=(1L..40L).map {AskContextLine(it,1000,"字".repeat(1000),"我",null)}
        }
        val core=AskContextCore(store)
        val history=listOf(AskMessage(1,AskRole.Assistant,"旧答案",listOf(source),0,false))
        assertEquals("新原话",core.followUp(history)!!.sources.single().quote)
        deleted=true; assertTrue(core.followUp(history)!!.sources.isEmpty())
        val selected=core.read(source.copy(kind="event",id=9))
        assertTrue(selected.sources.size<40); assertTrue(selected.sources.drop(1).all {it.quote.length<=900})
    }
    @Test fun proposalRequiresRetrievedIdAndKeepsSnapshot() = runBlocking {
        val target=AskChangeTarget("方案","Pending",null)
        val core=AskChangesCore(object:AskChangeStore {
            override suspend fun item(id:Long)=target;override suspend fun event(id:Long)=null
        },zone)
        val args="{\"operation\":\"item_status\",\"id\":42,\"status\":\"Done\"}"
        assertThrows(IllegalArgumentException::class.java) {runBlocking { core.propose(args,emptyList()) }}
        val change=core.propose(args,listOf(source.copy(kind="item")))
        assertEquals("{\"content\":\"方案\",\"status\":\"Pending\"}",change.expected)
        assertEquals(AskActionState.Pending,change.action.state)
    }
    @Test fun prefetchBudgetRollsBackUnsentReferences() = runBlocking {
        val calls=mutableListOf<String>()
        val core=AskPrefetchCore({name,_,sources->
            calls+=name;sources+=source
            if(name=="search_events") Obj().put("huge","字".repeat(12001)).toString() else "{}"
        }) { LocalDate(2026,11,10) }
        val context=core.read("方案",null)
        assertEquals(listOf("search_events","search_lines","list_items","semantic_search"),calls)
        assertEquals(3,context.sources.size);assertFalse(context.text.contains("huge"))
    }
}
