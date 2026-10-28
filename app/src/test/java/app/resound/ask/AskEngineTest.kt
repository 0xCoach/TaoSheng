package app.resound.ask

import app.resound.digest.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
        var requests=0; var executions=0; var tokens=0L; val updates=mutableListOf<AskEngine.Update>()
        val engine=AskEngine(AskStream { messages, _, delta, usage ->
            usage(10,2)
            if (requests++==0) LlmStreamReply("",listOf(LlmToolCall("a","search_lines","{\"keywords\":[\"老王\"]}")))
            else {
                assertEquals("tool",messages.getJSONObject(messages.length()-1).getString("role"))
                delta("要发方案"); delta("[^1]"); LlmStreamReply("要发方案[^1]",emptyList())
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
        val engine=AskEngine(AskStream { _,tools,_,_ ->
            if(requests++==0) LlmStreamReply("",(0..8).map { LlmToolCall("$it","search_events","{}") })
            else { assertEquals(0,tools.length()); LlmStreamReply("没找到",emptyList()) }
        },JSONArray().put(JSONObject()),{ _,_,_ -> executions++; JSONObject() },{_,_->})
        engine.answer("",emptyList(),"不存在",{})
        assertEquals(6,executions); assertEquals(2,requests)
    }
    @Test fun malformedToolArgumentsAreRecoverableAndUncitedConclusionGetsError() = runBlocking {
        var round=0; var result: AskEngine.Update?=null
        val engine=AskEngine(AskStream { messages,_,_,_->
            if(round++==0) LlmStreamReply("",listOf(LlmToolCall("a","search_lines","not json")))
            else { assertTrue(messages.toString().contains("参数无效")); LlmStreamReply("你已经答应了",emptyList()) }
        },JSONArray(),{_,_,_->error("不能执行")},{_,_->})
        engine.answer("",emptyList(),"问题",{result=it})
        assertEquals("回答缺少出处，请重新提问；常出现可在设置里检测模型",result!!.error)
    }
    @Test fun answerWithoutAnyLookupPointsToToolSupport() = runBlocking {
        var result: AskEngine.Update?=null
        val engine=AskEngine(AskStream { _,_,_,_-> LlmStreamReply("你明天要开会",emptyList()) },JSONArray(),{_,_,_->error("不会调用")},{_,_->})
        engine.answer("",emptyList(),"明天干嘛",{result=it})
        assertEquals("模型没查记录就作答，可能不支持工具调用，可在设置里检测模型",result!!.error)
    }
}
