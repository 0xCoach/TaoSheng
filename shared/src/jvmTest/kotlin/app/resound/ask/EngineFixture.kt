package app.resound.ask
import app.resound.ask.core.*
import app.resound.net.*
import kotlinx.datetime.LocalDate
internal fun interface TestStream { suspend fun call(messages: Arr, tools: Arr, text: (String)->Unit, usage: (Long,Long)->Unit): AskReply }
internal fun tool(id: String, name: String, arguments: String) = NetToolCall(id, name, arguments, null)
internal fun reply(content: String, calls: List<NetToolCall>) = AskReply(NetStreamReply(content, calls, null))
internal fun engine(stream: TestStream, definitions: Arr, search: suspend (String, Obj, MutableList<AskSource>)->Obj,
    usage: suspend (Long,Long)->Unit, propose: ((Obj,List<AskSource>)->ChangeProposal)? = null,
    mode: ()->AskMode = { AskMode.Tools }, markFallback: (AskFailure,Boolean)->Unit = {_,_->}, recordFailure: (AskFailure)->Unit = {}) : AskEngineCore {
    val lookup: suspend (String,String,MutableList<AskSource>)->String = { name,args,sources -> search(name,Obj(args),sources).toString() }
    return AskEngineCore(AskModel { messages,tools,text,tokens -> stream.call(Arr(messages),Arr(tools),text,tokens) },definitions.toString(),lookup,usage,
        propose?.let { handler -> { args,sources -> handler(Obj(args),sources) } },mode,markFallback,recordFailure,
        AskPrefetchCore(lookup) { LocalDate(2026,11,10) }::read)
}
