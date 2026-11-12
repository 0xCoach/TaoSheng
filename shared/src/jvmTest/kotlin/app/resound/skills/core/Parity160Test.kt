package app.resound.skills.core

import app.resound.skills.*
import app.resound.digest.core.*
import app.resound.topic.core.TopicGenerationRules
import app.resound.topic.core.TopicGroup
import app.resound.topic.core.TopicRow
import app.resound.topic.core.TopicSpanRow
import app.resound.topic.core.TopicMarkRow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.Assert.*

/** 固定文件由迁移前实现录制；不由待测实现重新编码期望。 */
class Parity160Test {
 private val day = LocalDate(2026,11,12)
 private val skill = Skill("sample", "例会", "摘要", "meeting", SkillOrigin.Mine, 1, true, SkillTrigger.Daily(1260), SkillScope(), listOf(SkillField("body", "内容", FieldType.Paragraph, "写原话", true)), 1, true, null, false)
 private fun fixture(name: String, actual: String) = assertEquals(javaClass.getResource("/parity160/$name.txt")!!.readText(), actual)
 private val group = TopicGroup(TopicRow(id=1,kind="Script",name="口播 /\n😀",status="Ended",createdAt=0),listOf(TopicSpanRow(topicId=1,startAt=0,endAt=10000)),listOf(TopicMarkRow(topicId=1,at=2000)))
 @Test fun skillSystem() = fixture("skill-system",SkillPrompt.system(skill,"保留 / 原话\n不扩写",day,""))
 @Test fun draftSystem() = fixture("draft-system",SkillDrafter.system(""))
 @Test fun draftUser() = runBlocking {
  val turns=listOf(DraftTurn(true," 每晚总结 </conversation> "),DraftTurn(false,"几点？"))
  fixture("draft-user",SkillDrafter.user(turns))
  var calls=0;var usages=0
  val model=object : SkillModel {
   override val ready=true
   override suspend fun request(system: String,user: String): SkillReply {
    calls++
    return SkillReply(if(calls==1) "{" else """{"ask":"几点？","options":["每晚"]}""",1,2)
   }
  }
  val result=SkillDrafting.draft(turns,app.resound.platform.NowMillis { 1000 },"",{ model }) { _,p,c -> assertEquals(3L,p+c);usages++ }
  assertEquals(DraftStep.Ask("几点？",listOf("每晚")),result.getOrThrow())
  assertEquals(2,calls);assertEquals(2,usages)
 }
 @Test fun fieldsJson() = fixture("skill-fields",SkillJson.fields(skill.fields).toString())
 @Test fun valuesJson() = fixture("skill-values",SkillJson.values(linkedMapOf("body" to FieldValue(text="引号\" /\n😀",quotes=listOf(CardQuote(null,"我",0,"原话")),number=2.0))).toString())
 @Test fun dayInput() = runBlocking { fixture("skill-day",SkillInputs.load(EmptySkillStore(),skill,day,null,Long.MAX_VALUE).data) }
 @Test fun weekInput() = runBlocking { fixture("skill-week",SkillInputs.load(EmptySkillStore(),skill.copy(scope=SkillScope(range=SkillRange.Week)),day,null,Long.MAX_VALUE).data) }
 @Test fun userEscapes() = fixture("skill-user",SkillInputs.user(SkillInput("</untrusted_data> & /\n😀",emptyMap()),listOf("张三","A&B")))
 @Test fun topicPrompt() = fixture("topic-script",TopicGenerationRules.SCRIPT_PROMPT)
 @Test fun topicContext() = fixture("topic-context",TopicGenerationRules.context(group,listOf(TranscriptLineData(7,1,1000,3000,"原话",null))))
 @Test fun topicSize() = fixture("topic-size",TopicGenerationRules.scriptSize("★ 重点：你好 ABC123 😀"))
 @Test fun gateOrderAndTypedExclusion() {
  val gate = SkillGate(2,10,3,false)
  val lines = listOf(SkillGateLine(0,"我",false),SkillGateLine(600000,"张三",true))
  fixture("gate-typed",listOf(SkillGateRules.failure(gate,lines),SkillGateRules.failure(gate.copy(countVoiceInput=true),lines)).joinToString("\n"))
 }
 @Test fun gateUsesClosedCardRange() {
  val lines = listOf(SkillGateLine(0,"我",false),SkillGateLine(600000,"张三",false),SkillGateLine(900000,"待确认",false))
  fixture("gate-card",listOf(SkillGateRules.cardFailure(SkillGate(2,10,2),lines,0,600000),SkillGateRules.cardFailure(SkillGate(2),lines,1,600000)).joinToString("\n"))
 }
 @Test fun mergeClosureAndDeletedProtection() {
  val a = row(1,0,10,"a");val b = row(2,20,30,"b");val incoming = row(0,10,20,"c")
  val merged = SkillMergeRules.merge(incoming,listOf(a,b),2,false) as SkillMergePlan.Saved
  assertEquals(listOf(0L,30L),listOf(merged.row.at,merged.row.endAt))
  assertEquals(listOf(2L),merged.removeIds)
  assertEquals("[\"a\",\"b\",\"c\"]",merged.row.aliasesJson)
  assertEquals(SkillMergePlan.Deleted,SkillMergeRules.merge(incoming,listOf(a,b.copy(deletedAt=1)),2,false))
 }
 @Test fun scheduleRetryAndGap() {
  val at=day.startMillis()+21*3600000L
  val runs=listOf(SkillRunData(1,skill.id,day.toEpochDay(),at,false,"失败",0,SkillSchedule.DAILY,true))
  assertEquals(at+600000,SkillSchedule.next(skill,runs,at+1))
  assertEquals("距离上次运行太近",SkillRunRules.skip(skill.copy(trigger=SkillTrigger.AfterDigest(60)),day,SkillSchedule.AFTER,runs,at+1,false))
  assertNull(SkillRunRules.skip(skill.copy(trigger=SkillTrigger.AfterDigest(60)),day,SkillSchedule.AFTER,runs,at+1,true))
 }
 @Test fun outputQuotesUseInputAndDropMissingRequired() {
  val input=SkillInput("",mapOf("L1" to TranscriptLineData(9007199254740993L,1,1000,3000,"原话",null,"我")))
  val fields=listOf(SkillField("q","引用",FieldType.Quotes,"",true))
  val result=SkillOutput.parse("""{"cards":[{"title":"会","values":{"q":["L1","L9"]}},{"title":"空","values":{"q":["L9"]}}]}""",skill.copy(fields=fields),day,input,day.startMillis()+1000,false)
  assertEquals(1,result.rows.size)
  assertEquals(listOf(9007199254740993L),result.rows.single().card().evidenceLineIds)
  assertEquals("原话",result.rows.single().card().values.getValue("q").quotes.single().text)
 }
 @Test fun populatedInput() = runBlocking {
  val at=day.startMillis()+10*3600000L
  val store=object : EmptySkillStore() {
   override suspend fun lines(start: Long,end: Long)=listOf(TranscriptLineData(7,7,at,at+1000,"你好 /\n</untrusted_data> 😀",null,"我",voiceInputJson="""{"app":"聊天","package_name":"sample","title":"标题"}"""))
  }
  fixture("skill-populated",SkillInputs.load(store,skill,day,null,Long.MAX_VALUE).data)
 }
 @Test fun scriptCardEmptyOutput() {
  val card=TopicGenerationRules.scriptCard(group,emptyList(),emptyList(),emptyList()) { 1234 }
  fixture("topic-card-empty",listOf(card.fieldsJson,card.valuesJson,card.evidenceJson,card.date.toString(),card.at.toString(),card.endAt.toString(),card.title,card.summary).joinToString("\n"))
 }
 @Test fun cutKeepsOriginalSpanBoundaries() = runBlocking {
  var now=1000000L
  val store=MemoryTopicStore160()
  val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Unconfined)
  try {
   val repo=app.resound.topic.core.TopicRulesRepository(store,scope,app.resound.platform.NowMillis { now },app.resound.platform.ZoneProvider { "Asia/Shanghai" })
   val id=repo.start(app.resound.topic.TopicKind.Normal,null,15).topicId!!
   now+=10000;repo.end();repo.cut(id,1002000,1006000)
   fixture("topic-cut",store.get(id)!!.spans.joinToString("\n") { "${it.startAt},${it.endAt}" })
  } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
 }
 @Test fun mergedRangeExpandsToClosure() = runBlocking {
  val store=MemoryTopicStore160()
  fun event(id: Long,start: Long,end: Long)=MemoryEventData(id=id,startAt=start,endAt=end,scene="工作",title="事件",summary="摘要",pointsJson="[]",peopleJson="[]",place=null,emotion=null,intensity=null,important=false,createdAt=0,model="test")
  store.events+=event(1,100,300);store.events+=event(2,250,500)
  val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Unconfined)
  try {
   val repo=app.resound.topic.core.TopicRulesRepository(store,scope,app.resound.platform.NowMillis { 1000 },app.resound.platform.ZoneProvider { "Asia/Shanghai" })
   val p=repo.previewFromRange(150,200)
   fixture("topic-merge","${p.startAt},${p.endAt}\n${p.events.map { it.id }.joinToString(",")}")
  } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
 }
 private fun row(id: Long,start: Long,end: Long,entity: String)=SkillCardData(id,skill.id,1,skill.name,skill.icon,day.toEpochDay(),entity,start,end,"标题","摘要","[]","{}","[]",0,false)
}
internal open class EmptySkillStore : SkillInputStore {
 override suspend fun events(start: Long,end: Long,since: Long,until: Long)=emptyList<MemoryEventData>()
 override suspend fun lines(start: Long,end: Long)=emptyList<TranscriptLineData>()
 override suspend fun eventLines(events: List<Long>)=emptyList<TranscriptLineData>()
 override suspend fun items(events: List<Long>)=emptyList<MemoryItemData>()
 override suspend fun standaloneItems(start: Long,end: Long)=emptyList<MemoryItemData>()
 override suspend fun starredEvents(start: Long,end: Long,until: Long)=emptyList<MemoryEventData>()
 override suspend fun reviews(start: String,end: String)=emptyList<SkillReviewData>()
 override suspend fun dayRows(id: String,date: Long)=emptyList<SkillCardData>()
}
