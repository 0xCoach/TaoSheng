package app.resound.night

import app.resound.capture.QuietRule
import app.resound.data.CaptureDatabase
import app.resound.data.PrivacyFileCleanup
import app.resound.data.writeTransaction
import app.resound.platform.FileRef
import app.resound.platform.ZoneHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 文件路径仅在适配器解引用；记录删除和文件清理凭据在同一事务中落盘。 */
class NightStore(val db: CaptureDatabase,private val scope: CoroutineScope,initial: NightSettings,
    private val saveSettings: (NightSettings)->Unit,private val download: suspend ()->Unit,
    private val now: ()->Long,private val zones: ()->ZoneHistory,private val available: suspend (String)->Boolean,
    private val fileRef: (String)->FileRef, private val decodeEnvelope: (NightEnvelopeRow)->NightEnvelope, private val cleanup: suspend ()->Unit,private val changed: ()->Unit,quiet: StateFlow<List<QuietRule>>) : NightRepository {
    val dao=db.night()
    val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    val gate=Mutex()
    private val config=MutableStateFlow(initial)
    override val settings=config.asStateFlow()
    private val status=MutableStateFlow(NightLive(NightState.Waiting,null,null))
    override val live=status.asStateFlow()
    override val quietConflict=combine(config,quiet) { s,q->NightRules.conflict(s,q) }.stateIn(scope,SharingStarted.Eagerly,NightRules.conflict(initial,quiet.value))
    init { if(initial.enabled)scope.launch { download() } }
    override fun update(settings: NightSettings) { NightRules.validate(settings);saveSettings(settings);config.value=settings;changed();if(settings.enabled)scope.launch { download() } }
    fun setLive(value: NightLive) {status.value=value}
    fun summary(row: NightRow)=json.decodeFromString<NightSummary>(row.payload).copy(id=row.id)
    fun session(row: NightRow)=json.decodeFromString<NightSession>(row.signals)
    override fun nights(from: LocalDate,to: LocalDate)=dao.nights(from.toString(),to.toString()).map { it.map(::summary) }
    override fun night(date: LocalDate): Flow<NightDetail?> = dao.observe().map { list->list.firstOrNull { it.date==date.toString() }?.let { detail(it.id) } }
    suspend fun detail(id: Long): NightDetail? {
        val row=dao.get(id) ?: return null
        val clips=dao.clips(id).associateBy { it.id }
        val rows=dao.events(id)
        val events=rows.map { r->json.decodeFromString<NightEvent>(r.payload).copy(id=r.id,audioAvailable=clips[json.decodeFromString<NightEvent>(r.payload).clipId]?.path?.let { available(it) }==true) }
        val tagLinks=dao.tagLinks(id);val tagList=dao.tagList();val suggested=tagList.filter {t->tagLinks.any {it.tagId==t.id && it.state=="suggested"}}.map {NightTag(it.id,it.name,it.preset)}
        val dreams=dao.dreams(id).map {json.decodeFromString<Dream>(it.payload).copy(id=it.id)}
        val notice=if(settings.value.breakReminder && dao.notice("break")?.muted!=true && dao.notice("notice-night-$id")!=null)SnoreBreaks.NOTICE else null
        return NightDetail(summary(row),events,dao.envelope(id)?.let(decodeEnvelope),events.filter { it.asked && it.feedback==Feedback.None }.map { it.id },suggestedTags=suggested,dreams=dreams,breakNotice=notice,vetoed=rows.filter { it.vetoed }.map { it.id }.toSet())
    }
    override suspend fun feedback(eventId: Long,value: Feedback)=gate.withLock {
        db.writeTransaction {
            val r=dao.event(eventId) ?: return@writeTransaction
            val e=json.decodeFromString<NightEvent>(r.payload)
            val clip=e.clipId?.let { dao.clip(it) }
            if(clip!=null) {
                if(value==Feedback.No)clip.path?.let { db.privacy().queueCleanup(PrivacyFileCleanup(fileRef(it).id)) }
                dao.updateClip(clip.copy(path=clip.path.takeUnless { value==Feedback.No },confirmed=value==Feedback.Yes))
                if(value!=Feedback.None)dao.feedback(NightFeedbackRow(nightId=r.nightId,eventId=r.id,kind=r.kind,yes=value==Feedback.Yes,scores=clip.scores,features=clip.features,at=now()))
            }
            dao.updateEvent(r.copy(payload=json.encodeToString(e.copy(feedback=value,audioAvailable=value!=Feedback.No && e.audioAvailable)),vetoed=if(value==Feedback.Yes)false else r.vetoed))
            recompute(r.nightId)
        }
        cleanup();changed()
    }
    override suspend fun editTimes(nightId: Long,bedtime: Long?,onset: Long?,wake: Long?)=gate.withLock {
        db.writeTransaction {
            val row=dao.get(nightId) ?: return@writeTransaction;val s=summary(row);val t=s.times ?: return@writeTransaction
            val next=t.copy(bedtime=bedtime ?: t.bedtime,onset=onset ?: t.onset,wake=wake ?: t.wake,
                editedBedtime=t.editedBedtime||bedtime!=null,editedOnset=t.editedOnset||onset!=null,editedWake=t.editedWake||wake!=null,onsetEstimated=if(onset!=null)false else t.onsetEstimated)
            require(next.bedtime<=next.onset && next.onset<=next.wake)
            dao.update(row.copy(payload=json.encodeToString(s.copy(times=next)),updatedAt=now()));recompute(nightId)
        };changed()
    }
    override suspend fun deleteNight(nightId: Long)=gate.withLock { db.writeTransaction { erase(nightId) };cleanup();changed() }
    /** 调用方已有事务；删除整夜避免残留梦话或从信号重新生成隐私内容。 */
    suspend fun erase(id: Long) {
        val learned=dao.feedback().any {it.nightId==id}
        if(dao.get(id)?.state in setOf("Active","Provisional"))dao.notice(NightNoticeRow("privacy-floor",now(),false))
        dao.clips(id).mapNotNull { it.path }.forEach { db.privacy().queueCleanup(PrivacyFileCleanup(fileRef(it).id)) };dao.delete(id);if(learned)dao.clearPersonal();NightWeekPrivacy.refresh(this,zones()) }
    suspend fun eraseRange(from: Long,until: Long) {
        for(row in dao.all()) {
            // 活跃会话在两次落盘之间仍在录制，删除范围也要覆盖这段尾部。
            val end = if (row.state == "Active" || row.state == "Provisional") maxOf(row.end, now()) else row.end
            if(row.start<until && end>from)erase(row.id)
            else for(dream in dao.dreams(row.id))if(dream.at<until && maxOf(dream.until,dream.at+1)>from) {
                dao.deleteDream(dream.id);dao.update(row.copy(updatedAt=now()))
            }
        }
    }
    suspend fun expire(at: Long)=gate.withLock {
        db.writeTransaction { for(c in dao.expired(at)) { c.path?.let { db.privacy().queueCleanup(PrivacyFileCleanup(fileRef(it).id)) };dao.updateClip(c.copy(path=null));dao.get(c.nightId)?.let { dao.update(it.copy(updatedAt=at)) } } };cleanup()
    }
    override suspend fun audio(clipId: Long): FileRef? = dao.clip(clipId)?.path?.takeIf { available(it) }?.let(fileRef)
    suspend fun persistSession(id: Long,session: NightSession,end: Long,state: NightState) {
        dao.get(id)?.let { dao.update(it.copy(end=end,state=state.name,signals=json.encodeToString(session),payload=json.encodeToString(summary(it).copy(state=state,modes=session.modes)),updatedAt=now())) }
    }
    /** 一夜结束：先按手机信号估出入睡/起床，整理（可能要等充电）完成前首页也能画出来 */
    suspend fun finishSession(id: Long,session: NightSession,end: Long) {
        val row=dao.get(id) ?: return;val old=summary(row)
        val times=old.times ?: SleepEstimate.calculate(session,emptyList(),end).times
        dao.update(row.copy(end=end,state=NightState.Processing.name,signals=json.encodeToString(session),payload=json.encodeToString(old.copy(state=NightState.Processing,modes=session.modes,times=times)),updatedAt=now()))
    }
    suspend fun create(session: NightSession,date: LocalDate): Long {
        val s=NightSummary(0,date,NightState.Provisional,null,null,0,0,0,0,0,null,emptyList(),null,emptyList())
        return dao.insert(NightRow(date=date.toString(),start=session.screenOff,end=session.lastAt,state=s.state.name,payload=json.encodeToString(s),signals=json.encodeToString(session),createdAt=now(),updatedAt=now()))
    }
    suspend fun addEvent(id: Long,event: NightEvent,certainty: Double=1.0): Long = dao.insertEvent(NightEventRow(nightId=id,start=event.start,end=event.end,kind=event.kind.name,payload=json.encodeToString(event),certainty=certainty,vetoed=false))
    suspend fun recompute(id: Long) {
        val row=dao.get(id) ?: return;val old=summary(row);val session=session(row)
        val generated = setOf("Awake","Playback","Gap","Turn")
        dao.events(id).filter { it.kind in generated }.forEach { dao.deleteEvent(it.id) }
        session.signals.filter { it.kind in setOf("playback","gap","turn") && (it.kind!="gap" || it.end-it.start>=300_000) }.forEach { signal ->
            addEvent(id,NightEvent(0,when(signal.kind) { "playback"->NightEventKind.Playback;"gap"->NightEventKind.Gap;else->NightEventKind.Turn },signal.start,signal.end,text=signal.text))
        }
        val rows=dao.events(id);val events=rows.filter { !it.vetoed }.map { json.decodeFromString<NightEvent>(it.payload).copy(id=it.id) }.filter { it.feedback!=Feedback.No }
        val history=dao.all().map(::summary)
        val corrections=dao.all().mapNotNull { r->val s=summary(r);val original=session(r).originalTimes;s.times?.takeIf { it.editedOnset && original!=null }?.let { it.onset-original!!.onset } }
        val sleep=SleepEstimate.calculate(session,events,row.end,NightRules.latency(corrections),old.times)
        sleep.awake.forEach { addEvent(id,NightEvent(0,NightEventKind.Awake,it.start,it.end,text="看手机或拿起手机")) }
        val (confidence,reasons)=NightPresentation.confidence(session,session.signals.filter { it.kind=="background" }.map { it.value.toInt() })
        val sound=events.filter { it.kind in setOf(NightEventKind.Snore,NightEventKind.Cough,NightEventKind.SleepTalk) }
        val s=old.copy(state=NightState.Ready,times=sleep.times,sleepMillis=sleep.millis,awakeCount=sleep.count,awakeMillis=sleep.awake.sumOf { it.millis },
            snoreMillis=NightRules.merge(events.filter { it.kind==NightEventKind.Snore }.map { NightSpan(it.start,it.end) }).sumOf { it.millis },
            coughCount=events.count { it.kind==NightEventKind.Cough },talkCount=events.count { it.kind==NightEventKind.SleepTalk },
            snoreBreaks=events.count { it.kind==NightEventKind.SnoreBreak },confidence=confidence,reasons=reasons+if(sound.isEmpty())listOf("没听到睡眠声音，确认手机放在床边") else emptyList())
        val questions=rows.filter { it.kind in setOf("Snore","Cough") && it.certainty<.8 }.sortedBy { it.certainty }.take(3).map { it.id }.toSet()
        rows.forEach { r->val e=json.decodeFromString<NightEvent>(r.payload);if(e.asked!=(r.id in questions))dao.updateEvent(r.copy(payload=json.encodeToString(e.copy(asked=r.id in questions)))) }
        dao.update(row.copy(state=s.state.name,payload=json.encodeToString(s.copy(sentence=NightPresentation.sentence(s,history,settings.value.sharedRoom))),signals=json.encodeToString(session.copy(originalTimes=session.originalTimes ?: sleep.times)),updatedAt=now()))
    }
    suspend fun refreshSpeech(id: Long) {
        val row=dao.get(id) ?: return
        val signals=session(row).signals
        val wake=signals.lastOrNull { it.kind=="wake" }?.value?.toLong() ?: row.end
        val segments=db.capture().segments(row.start,row.end).first().filter { it.importRecordId==null && it.startAt<wake }
        val existing=dao.events(id).filter { it.kind=="SleepTalk" }
        val alive=segments.map { it.id }.toSet()
        existing.filter { json.decodeFromString<NightEvent>(it.payload).segmentId !in alive }.forEach { dao.deleteEvent(it.id) }
        for(segment in segments) {
            if(segment.playbackMillis>0 || !NightRules.sleepTalk(segment.startAt,segment.endAt,signals))continue
            val lines=db.voiceprint().lines(segment.id)
            val event=NightEvent(0,NightEventKind.SleepTalk,segment.startAt,segment.endAt,segmentId=segment.id,
                text=lines.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text },mine=lines.isNotEmpty() && lines.all { it.speakerPersonId==0L })
            val old=existing.firstOrNull { json.decodeFromString<NightEvent>(it.payload).segmentId==segment.id }
            if(old==null)addEvent(id,event) else if(json.decodeFromString<NightEvent>(old.payload)!=event)dao.updateEvent(old.copy(payload=json.encodeToString(event)))
        }
    }
    fun notifyChanged()=changed()
}
