package app.resound.ask

import app.resound.ask.core.*
import app.resound.net.*
import kotlinx.coroutines.runBlocking
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import org.junit.Assert.*
import org.junit.Test

class AskEngineTest {
    private val source = AskSource("line", 42, 1000,"我","周三给老王发方案")
    @Test fun referencesAreDenseStableAndRejectOutOfBounds() {
        val other = source.copy(id=43)
        val result = AskCitations.parse("结论[^2]，再说[^2]、[^1]、[^0]、[^999999999999999999]",listOf(source,other))
        assertEquals("结论[^1]，再说[^1]、[^2]、、",result.text)
        assertEquals(listOf(other,source),result.sources); assertTrue(result.invalid)
        assertEquals(result.sources,AskCitations.decode(AskCitations.encode(result.sources)))
    }
    @Test fun fakeServiceCallsToolThenStreamsCitedAnswerAndCountsUsage() = runBlocking {
        var requests=0; var executions=0; var tokens=0L; val updates=mutableListOf<AskEngineCore.Update>()
        val engine=engine(TestStream { messages, _, delta, usage ->
            usage(10,2)
            if (requests++==0) reply("",listOf(tool("a","search_lines","{\"keywords\":[\"老王\"]}")))
            else {
                assertEquals("tool",messages.getJSONObject(messages.length()-1).getString("role"))
                delta("要发方案"); delta("[^1]"); reply("要发方案[^1]",emptyList())
            }
        },JSONArray().put(JSONObject()), { name,args,sources ->
            assertEquals("search_lines",name); assertEquals("老王",args.getJSONArray("keywords").getString(0))
            sources+=source; executions++; JSONObject().put("ref",1)
        }, { p,c -> tokens+=p+c })
        engine.answer("提示词",emptyList(),"答应老王什么",updates::add)
        assertEquals(1,executions); assertEquals(24L,tokens)
        assertTrue(updates.any { it.progress?.contains("老王")==true })
        assertEquals(listOf(source),updates.last().sources); assertNull(updates.last().error)
    }
    @Test fun parallelToolCallsCannotExceedSixAndFinalRequestDisablesTools() = runBlocking {
        var requests=0; var executions=0
        val engine=engine(TestStream { _,tools,_,_ ->
            if(requests++==0) reply("",(0..8).map { tool("$it","search_events","{}") })
            else { assertEquals(0,tools.length()); reply("没找到",emptyList()) }
        },JSONArray().put(JSONObject()),{ _,_,_ -> executions++; JSONObject() },{_,_->})
        engine.answer("",emptyList(),"不存在",{})
        assertEquals(6,executions); assertEquals(2,requests)
    }
    @Test fun malformedToolArgumentsAreRecoverableAndUncitedConclusionGetsError() = runBlocking {
        var round=0; var result: AskEngineCore.Update?=null
        val engine=engine(TestStream { messages,_,_,_->
            if(round++==0) reply("",listOf(tool("a","search_lines","not json")))
            else { assertTrue(messages.toString().contains("参数无效")); reply("你已经答应了",emptyList()) }
        },JSONArray(),{_,_,_->error("不能执行")},{_,_->})
        engine.answer("",emptyList(),"问题",{result=it})
        assertEquals("回答缺少出处，请重新提问；常出现可在设置里检测模型",result!!.error)
    }
    @Test fun answerWithoutAnyLookupPrefetchesButStillRejectsUncitedConclusion() = runBlocking {
        var result: AskEngineCore.Update?=null
        val engine=engine(TestStream { _,_,_,_-> reply("你明天要开会",emptyList()) },JSONArray(),{_,_,_->JSONObject()},{_,_->})
        engine.answer("",emptyList(),"明天干嘛",{result=it})
        assertEquals("没找到可核实的记录，请补充关键词或缩小时间范围",result!!.error)
    }
}
