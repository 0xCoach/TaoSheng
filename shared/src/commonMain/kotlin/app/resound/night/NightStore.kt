@file:OptIn(kotlin.time.ExperimentalTime::class)
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
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 文件路径仅在适配器解引用；记录删除和文件清理凭据在同一事务中落盘。 */
class NightStore(val db: CaptureDatabase,private val scope: CoroutineScope,initial: NightSettings,
    private val saveSettings: (NightSettings)->Unit,private val download: suspend ()->Unit,
    private val now: ()->Long,private val zones: ()->ZoneHistory,private val available: suspend (String)->Boolean,
    private val fileRef: (String)->FileRef, private val decodeEnvelope: (NightEnvelopeRow)->NightEnvelope, private val cleanup: suspend ()->Unit,private val changed: ()->Unit,quiet: StateFlow<List<QuietRule>>,
    private val privacyChanged: suspend () -> Unit = { changed() },
    /** 夜里记录被删、被取消时记一行（只记日期、入口、原因，不记内容），进问题记录 */
    private val log: (String) -> Unit = {}) : NightRepository {
    val dao=db.night()
    val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    val gate=Mutex()
    internal val personalGate = Mutex()
    private val config=MutableStateFlow(initial)
    override val settings=config.asStateFlow()
    private val status=MutableStateFlow(NightLive(NightState.Waiting,null,null))
    override val live=status.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    override val automaticWindow = flow {
        while (true) {
            val at = now()
            val history = zones()
            val today = NightRules.date(at, history)
            emit(today)
            val midnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(history.zoneAt(at)).toEpochMilliseconds()
            delay((midnight - at).coerceIn(1_000L, 3_600_000L))
        }
    }.distinctUntilChanged().flatMapLatest { today ->
        dao.autoSamples(today.minus(13, DateTimeUnit.DAY).toString(), today.toString())
    }.distinctUntilChanged().map { payloads ->
        val history = zones()
        SleepWindows.automatic(payloads.mapNotNull { payload ->
            val summary = json.decodeFromString<NightSummary>(payload)
            summary.times?.takeIf { summary.confidence in setOf(NightConfidence.Good, NightConfidence.Fair) }?.let {
                NightRules.minute(it.onset, history) to NightRules.minute(it.wake, history)
            }
        })
    }.stateIn(scope, SharingStarted.Eagerly, SleepWindows.fallback)
    override val quietConflict = combine(config, quiet, automaticWindow) { s, q, auto ->
        NightRules.conflict(s, q, auto)
    }.stateIn(scope, SharingStarted.Eagerly, NightRules.conflict(initial, quiet.value))
    init { if(initial.enabled)scope.launch { download() } }
    override fun update(settings: NightSettings) {
        NightRules.validate(settings)
        val old = config.value
        val legacyEdit = settings.windows == old.windows &&
            (settings.windowStartMinute != old.windowStartMinute || settings.windowEndMinute != old.windowEndMinute)
        val windows = if (legacyEdit) settings.windows.map {
            if (it.kind == SleepWindowKind.Night) it.copy(auto = false, startMinute = settings.windowStartMinute,
                endMinute = settings.windowEndMinute) else it
        } else settings.windows.toList()
        if (windows != old.windows) SleepWindows.error(windows, automaticWindow.value)?.let { throw IllegalArgumentException(it) }
        val night = windows.first { it.kind == SleepWindowKind.Night }
        val next = settings.copy(windows = windows, windowStartMinute = night.startMinute, windowEndMinute = night.endMinute)
        saveSettings(next);config.value=next;changed();if(next.enabled)scope.launch { download() }
    }
    fun setLive(value: NightLive) {status.value=value}
    fun summary(row: NightRow)=json.decodeFromString<NightSummary>(row.payload).copy(id=row.id)
    fun session(row: NightRow)=json.decodeFromString<NightSession>(row.signals)
    /** 卡 225：最近 14 晚（已整理）的起床时刻中位数，当地分钟 */
    suspend fun usualWakeMinute(kind: String, zones: app.resound.platform.ZoneHistory): Int? =
        WakeRules.usualWake(dao.all().filter { it.kind == kind && it.state == NightState.Ready.name }.take(14)
            .mapNotNull { summary(it).times?.wake }.map { NightRules.minute(it, zones) })
    override fun nights(from: LocalDate,to: LocalDate)=dao.nights(from.toString(),to.toString()).map { it.map(::summary) }
    override fun night(date: LocalDate): Flow<NightDetail?> = dao.observe().map { list->list.firstOrNull { it.date==date.toString() }?.let { detail(it.id) } }
    override fun naps(date: LocalDate) = dao.naps(date.toString()).map { rows -> rows.map(::summary) }
    override fun nightById(id: Long) = dao.observeId(id).map { it?.let { detail(id) } }
    suspend fun detail(id: Long): NightDetail? {
        val row=dao.get(id) ?: return null
        val clips=dao.clips(id).associateBy { it.id }
        val rows=dao.events(id)
        val all=rows.map { r->json.decodeFromString<NightEvent>(r.payload).copy(id=r.id,audioAvailable=clips[json.decodeFromString<NightEvent>(r.payload).clipId]?.path?.let { available(it) }==true) }
        // 卡 213：起床后、入睡前、醒着时的“很响的一下”不显示
        val times=summary(row).times;val awake=all.filter { it.kind==NightEventKind.Awake }.map { NightSpan(it.start,it.end) }
        val events=all.filter { NightRules.loudWhileAsleep(it,times,awake) }
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
                val at = now()
                val expires = if (value == Feedback.No) at + 7L * 86_400_000 else clip.expiresAt
                dao.updateClip(clip.copy(confirmed = value == Feedback.Yes, expiresAt = expires))
                if (value == Feedback.None) dao.removeFeedback(r.id)
                else dao.feedback(NightFeedbackRow(nightId=r.nightId,eventId=r.id,kind=r.kind,yes=value==Feedback.Yes,
                    scores=clip.scores,features=clip.features,at=at))
            }
            dao.updateEvent(r.copy(payload=json.encodeToString(e.copy(feedback=value)),
                vetoed=if(value==Feedback.Yes) false else r.vetoed))
            recompute(r.nightId)
        }
        if (value == Feedback.No) privacyChanged() else changed()
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
    override suspend fun deleteNight(nightId: Long)=deleteNight(nightId,"删除这一夜")
    suspend fun deleteNight(nightId: Long,why: String)=gate.withLock {
        dao.get(nightId)?.let { log("删除 ${it.date} 状态=${it.state} 原因=$why") }
        db.writeTransaction { erase(nightId) };try { privacyChanged() } finally { cleanup() } }
    /**
     * 时间线上删录音/事件时：夜里记录不删（睡眠时间、鼾声、咳嗽没有隐私问题），
     * 只把指向已删录音的梦话去掉；已整理的夜晚重算一次数字。
     */
    suspend fun forgetSpeech(ranges: List<Pair<Long,Long>>) {
        for(row in dao.all()) {
            if(ranges.none { (from,until)-> row.start<until && row.end>from })continue
            refreshSpeech(row.id)
            if(row.state==NightState.Ready.name)recompute(row.id)
            log("时间线删除与 ${row.date} 重叠：保留夜里记录，只刷新梦话")
        }
        changed()
    }
    /** 调用方已有事务；删除整夜避免残留梦话或从信号重新生成隐私内容。 */
    suspend fun erase(id: Long) {
        dao.invalidateJudgment(id)
        val learned=dao.feedback().any {it.nightId==id}
        if(dao.get(id)?.state in setOf("Active","Provisional"))dao.notice(NightNoticeRow("privacy-floor",now(),false))
        dao.clips(id).mapNotNull { it.path }.forEach { db.privacy().queueCleanup(PrivacyFileCleanup(fileRef(it).id)) };dao.delete(id);if(learned)dao.clearPersonal();NightWeekPrivacy.refresh(this,zones()) }
    suspend fun eraseRange(from: Long,until: Long) {
        for(row in dao.all()) {
            // 活跃会话在两次落盘之间仍在录制，删除范围也要覆盖这段尾部。
            val end = if (row.state == "Active" || row.state == "Provisional") maxOf(row.end, now()) else row.end
            if(row.start<until && end>from) { log("全部清除 ${row.date}");erase(row.id) }
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
        dao.invalidateJudgment(id)
        val times=old.times ?: SleepEstimate.calculate(session,emptyList(),end).times
        dao.update(row.copy(end=end,state=NightState.Processing.name,signals=json.encodeToString(session),payload=json.encodeToString(old.copy(state=NightState.Processing,modes=session.modes,times=times)),updatedAt=now()))
    }
    suspend fun create(session: NightSession,date: LocalDate, window: SleepWindow = SleepWindows.fallback): Long {
        val s=NightSummary(0,date,NightState.Provisional,null,null,0,0,0,0,0,null,emptyList(),null,emptyList())
        return dao.insert(NightRow(date=date.toString(),kind=window.recordKind,windowId=window.id,start=session.screenOff,end=session.lastAt,state=s.state.name,payload=json.encodeToString(s),signals=json.encodeToString(session),createdAt=now(),updatedAt=now()))
    }
    suspend fun addEvent(id: Long,event: NightEvent,certainty: Double=1.0): Long = dao.insertEvent(NightEventRow(nightId=id,start=event.start,end=event.end,kind=event.kind.name,payload=json.encodeToString(event),certainty=certainty,vetoed=false))
    suspend fun recompute(id: Long) {
        val row=dao.get(id) ?: return;val old=summary(row);val session=session(row)
        val generated = setOf("Awake","Playback","Gap","Turn")
        dao.events(id).filter { it.kind in generated }.forEach { dao.deleteEvent(it.id) }
        // 卡 210：旧版本把同一次鼾声中断记成多条，重算时合并
        SnoreBreaks.duplicates(dao.events(id).filter { it.kind=="SnoreBreak" }.map { it.id to NightSpan(it.start,it.end) }).forEach { dao.deleteEvent(it) }
        session.signals.filter { it.kind in setOf("playback","gap","turn") && (it.kind!="gap" || it.end-it.start>=300_000) }.forEach { signal ->
            addEvent(id,NightEvent(0,when(signal.kind) { "playback"->NightEventKind.Playback;"gap"->NightEventKind.Gap;else->NightEventKind.Turn },signal.start,signal.end,text=signal.text))
        }
        val rows=dao.events(id);val events=rows.filter { !it.vetoed }.map { json.decodeFromString<NightEvent>(it.payload).copy(id=it.id) }.filter { it.feedback!=Feedback.No }
        val comparable = dao.all().filter { it.kind == row.kind }
        val history=comparable.map(::summary)
        val corrections=comparable.mapNotNull { r->val s=summary(r);val original=session(r).originalTimes;s.times?.takeIf { it.editedOnset && original!=null }?.let { it.onset-original!!.onset } }
        val sleep=SleepEstimate.calculate(session,events,row.end,NightRules.latency(corrections),old.times)
        sleep.awake.forEach { addEvent(id,NightEvent(0,NightEventKind.Awake,it.start,it.end,text=SleepEstimate.awakeText(it,session.signals))) }
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
        // #59：实时外放判定可能失灵（魅族上路由给 TYPE_UNKNOWN），再用“设备播放记录”（媒体会话）兜底：手机在放节目时录到的人声不算梦话
        val played=if(wake>row.start) runCatching { app.resound.screentime.PlaybackQueries(db.screenTime()).overlapping(row.start,wake) }.getOrDefault(emptyList()) else emptyList()
        val talks=segments.filter { s-> s.playbackMillis<=0 && NightRules.sleepTalk(s.startAt,s.endAt,signals) && played.none { it.from<s.endAt+5_000 && it.until>s.startAt-5_000 } }
        val alive=talks.map { it.id }.toSet()
        existing.filter { json.decodeFromString<NightEvent>(it.payload).segmentId !in alive }.forEach { dao.deleteEvent(it.id) }
        for(segment in talks) {
            val lines=db.voiceprint().lines(segment.id)
            val event=NightEvent(0,NightEventKind.SleepTalk,segment.startAt,segment.endAt,segmentId=segment.id,
                text=lines.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text },mine=lines.isNotEmpty() && lines.all { it.speakerPersonId==0L })
            val old=existing.firstOrNull { json.decodeFromString<NightEvent>(it.payload).segmentId==segment.id }
            if(old==null)addEvent(id,event) else if(json.decodeFromString<NightEvent>(old.payload)!=event)dao.updateEvent(old.copy(payload=json.encodeToString(event)))
        }
    }
    suspend fun notifyPrivacyChanged() = privacyChanged()
    fun notifyChanged()=changed()
}
