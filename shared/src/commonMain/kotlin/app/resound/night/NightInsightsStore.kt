@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.night

import app.resound.data.writeTransaction
import app.resound.platform.FileRef
import app.resound.platform.ZoneHistory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlin.time.Instant

class NightInsightsStore(val store: NightStore,private val now: ()->Long,private val zones: ()->ZoneHistory,
    private val loudest: suspend (Long)->Pair<FileRef,Boolean>?) : NightInsights {
    private val dao get()=store.dao
    private val json get()=store.json
    override val tags=dao.tags().map {it.map {NightTag(it.id,it.name,it.preset)}}
    suspend fun initialize() = store.db.writeTransaction { if(dao.tagList().isEmpty())NightTags.presets.forEach {dao.insertTag(NightTagRow(name=it,preset=true))} }
    override suspend fun createTag(name: String): Result<NightTag> = runCatching { store.db.writeTransaction {
        val text=name.trim();require(text.isNotBlank() && text.length<=120)
        require(dao.tagList().none {it.name==text}) {"标签已存在"};require(dao.tagList().count {!it.preset}<20) {"最多自建 20 个标签"}
        val id=dao.insertTag(NightTagRow(name=text,preset=false));NightTag(id,text,false)
    } }
    override suspend fun deleteTag(tagId: Long) {dao.deleteTag(tagId);for(row in dao.all())refreshTags(row.id);refreshWeekPrivacy();store.notifyChanged()}
    override suspend fun setTag(nightId: Long,tagId: Long,on: Boolean) {require(dao.get(nightId)!=null && dao.tagList().any {it.id==tagId});dao.tagLink(NightTagLink(nightId,tagId,if(on)"on" else "off"));refreshTags(nightId);store.notifyChanged()}
    override suspend fun dismissSuggestion(nightId: Long,tagId: Long) {dao.tagLink(NightTagLink(nightId,tagId,"dismissed"));touch(nightId)}
    private suspend fun touch(id: Long) {dao.get(id)?.let {dao.update(it.copy(updatedAt=now()))}}
    private suspend fun refreshTags(id: Long) {
        val row=dao.get(id) ?: return;val chosen=dao.tagLinks(id).filter {it.state=="on"}.map {it.tagId}.toSet()
        val tags=dao.tagList().filter {it.id in chosen}.map {NightTag(it.id,it.name,it.preset)}
        dao.update(row.copy(payload=json.encodeToString(store.summary(row).copy(tags=tags)),updatedAt=now()))
    }
    suspend fun suggest(id: Long,text: String) {
        val existing=dao.tagLinks(id).map {it.tagId}.toSet()
        NightTags.suggest(text,dao.tagList().map {NightTag(it.id,it.name,it.preset)}).filter {it.id !in existing}.forEach {dao.tagLink(NightTagLink(id,it.id,"suggested"))};touch(id)
    }
    override fun findings(): Flow<List<TagFinding>> = combine(dao.observe(),tags) {n,t->NightTags.findings(n.map(store::summary),t)}
    override fun weeklyReports()=dao.weeks().map {rows->rows.map {json.decodeFromString<WeeklyReport>(it.payload)}}
    override val personal = combine(dao.personal(), dao.observeFeedback(), dao.excludedCounts(), store.settings) { rows, feedback, counts, settings ->
        PersonalKind.entries.map { kind ->
            val row = rows.find { it.kind == kind.name }
            val model = row?.let { json.decodeFromString<PersonalWeights>(it.payload) }
            val samples = feedback.filter { it.kind == kind.name }
            val yes = samples.count { it.yes }
            val no = samples.size - yes
            PersonalModelStatus(kind, yes, no, settings.personalModel && model?.active == true && yes >= 20 && no >= 10,
                model?.accuracy, model?.ruleAccuracy, row?.trainedAt, counts.find { it.kind == kind.name }?.count ?: 0)
        }
    }

    override suspend fun resetPersonal() = store.personalGate.withLock {
        store.db.writeTransaction {
            dao.clearPersonal()
            dao.clearFeedback()
            dao.clearPersonalJudgments()
            for (id in dao.vetoedNights()) {
                dao.clearVetoes(id)
                store.recompute(id)
            }
        }
        store.notifyChanged()
    }

    override suspend fun startDream(nightId: Long): Dream {
        require(dao.get(nightId)!=null);val at=now();val d=Dream(0,nightId,at,null,emptyList(),true)
        val id=dao.insertDream(NightDreamRow(nightId=nightId,at=at,until=at+180_000,payload=json.encodeToString(d)));touch(nightId);return d.copy(id=id)
    }
    override suspend fun writeDream(nightId: Long,text: String): Dream {
        require(dao.get(nightId)!=null && text.isNotBlank());val at=now();val d=Dream(0,nightId,at,text.trim(),emptyList(),false)
        val id=dao.insertDream(NightDreamRow(nightId=nightId,at=at,until=at,payload=json.encodeToString(d)));touch(nightId);store.notifyChanged();return d.copy(id=id)
    }
    override suspend fun deleteDream(dreamId: Long) {val d=dao.dream(dreamId) ?: return;dao.deleteDream(dreamId);touch(d.nightId);store.notifyPrivacyChanged()}
    suspend fun refreshDreams(nightId: Long) {
        for(row in dao.dreams(nightId).filter {it.until>it.at}) {
            val segments=store.db.capture().segments(row.at,row.until).first().filter {it.importRecordId==null && it.startAt>=row.at && it.startAt<row.until}
            val lines=segments.flatMap {store.db.voiceprint().lines(it.id)}
            val pending=now()<row.until || segments.any {it.status !in setOf("Done","Empty","SkippedEnv","SkippedPlayback")}
            val d=Dream(row.id,nightId,row.at,lines.takeIf {it.isNotEmpty()}?.joinToString(" ") {it.text},segments.map {it.id},pending)
            val payload=json.encodeToString(d)
            if(payload!=row.payload) {dao.updateDream(row.copy(payload=payload));touch(nightId);store.notifyChanged()}
        }
    }
    override suspend fun loudestClip(nightId: Long)=loudest(nightId)
    override suspend fun muteBreakNotice() {dao.notice(NightNoticeRow("break",now(),true));store.notifyChanged()}
    override fun trend(from: LocalDate,to: LocalDate)=store.nights(from,to).map {n->n.sortedBy {it.date}.map {s->NightPoint(s.date,s.times?.bedtime,s.times?.onset,s.times?.wake,s.sleepMillis,s.snoreMillis,s.coughCount,s.awakeCount,s.snoreBreaks,s.confidence,s.tags.map {it.name})}}
    override suspend fun doctorReport(from: LocalDate,to: LocalDate): DoctorReport {
        require(from<=to);val all=dao.nightHistory().map(store::summary);val nights=all.filter {it.date>=from && it.date<=to}.sortedBy {it.date}
        data class Clip(val key: String,val db: Int,val ref: FileRef,val info: DoctorClip)
        val clips=mutableListOf<Clip>();val drawn=mutableListOf<DoctorNight>()
        for(n in nights) {
            // 只算入睡到起床之间的事件；起床以后的声音不进医生那页
            val t=n.times
            val events=store.detail(n.id)?.events.orEmpty().filter { e-> t==null || e.start>=t.bedtime && e.start<t.wake }
            for(e in events.filter {it.feedback==Feedback.Yes && it.clipId!=null})store.audio(e.clipId!!)?.let {clips+=Clip("${n.date}:${e.kind}",e.aboveBackgroundDb ?: 0,it,DoctorClip(e.start,e.kind))}
            drawn+=DoctorNight(n,events.filter {it.kind==NightEventKind.Snore && it.feedback!=Feedback.No}.map {NightSpan(it.start,it.end)},events.filter {it.kind==NightEventKind.Awake}.map {NightSpan(it.start,it.end)})
        }
        // 先按响度挑最典型的 5 段，再按时间排，医生看着顺
        val picked=(clips.sortedByDescending {it.db}.distinctBy {it.key}+clips.sortedByDescending {it.db}).distinctBy {it.ref}.take(5).sortedBy {it.info.at}
        return DoctorReport(from,to,NightExports.doctor(from,to,nights,zones()),picked.map {it.ref},drawn,
            NightTags.findings(all,dao.tagList().map {NightTag(it.id,it.name,it.preset)}).take(3),picked.map {it.info})
    }
    suspend fun finishWeek(report: WeeklyReport,state: NightWeekState) = store.db.writeTransaction {
        val nights=dao.nightHistory().map(store::summary)
        val next=NightWeeks.build(report.weekStart,nights,NightTags.findings(nights,dao.tagList().map {NightTag(it.id,it.name,it.preset)}),zones(),report.createdAt)
        if(next==null)dao.deleteWeek(report.weekStart.toString())
        else dao.week(NightWeekRow(report.weekStart.toString(),json.encodeToString(if(next.markdown==state.ruleMarkdown)report else next),json.encodeToString(state.copy(phase="ready",ruleMarkdown=next.markdown))))
    }
    suspend fun refreshWeekPrivacy() = store.db.writeTransaction { NightWeekPrivacy.refresh(store,zones()) }
    suspend fun weeks(at: Long): List<WeeklyReport> {
        val history=zones();val local=Instant.fromEpochMilliseconds(at).toLocalDateTime(history.zoneAt(at));val nights=dao.nightHistory().map(store::summary)
        val existing=dao.weeks().first().map {it.weekStart}.toSet();val findings=NightTags.findings(nights,dao.tagList().map {NightTag(it.id,it.name,it.preset)})
        return NightWeeks.due(local.date,local.hour).filter {it.toString() !in existing}.mapNotNull {NightWeeks.build(it,nights,findings,history,at)}
    }
}

@kotlinx.serialization.Serializable
data class NightWeekState(val requestId: String,val phase: String,val ruleMarkdown: String)

/** 删除来源时与删除操作同一事务，防止本机列表或随后推送继续读出旧周报。 */
object NightWeekPrivacy {
    suspend fun refresh(store: NightStore,zones: ZoneHistory) {
        val dao=store.dao;val json=store.json
        val nights=dao.nightHistory().map(store::summary);val tags=dao.tagList().map {NightTag(it.id,it.name,it.preset)}
        for(row in dao.weeks().first()) {
            val old=json.decodeFromString<WeeklyReport>(row.payload)
            val next=NightWeeks.build(old.weekStart,nights,NightTags.findings(nights,tags),zones,old.createdAt)
            if(next==null)dao.deleteWeek(row.weekStart)
            else {
                val state=runCatching {json.decodeFromString<NightWeekState>(row.pushState)}.getOrNull()
                if(state!=null && state.ruleMarkdown!=next.markdown)dao.week(row.copy(payload=json.encodeToString(next),pushState=json.encodeToString(state.copy(phase="ready",ruleMarkdown=next.markdown))))
            }
        }
    }
}
