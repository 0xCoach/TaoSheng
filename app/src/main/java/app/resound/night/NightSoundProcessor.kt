package app.resound.night

import kotlinx.serialization.encodeToString

/** 同一串行消费者调用，模型和编码不在麦克风读取协程里运行。 */
class NightSoundProcessor(private val owner: AndroidNight,private val id: Long,private val evidence: (NightWindow,Map<String,Float>)->Unit = { _,_-> }) {
    private val detector=NightDetector()
    private val recent=ArrayDeque<Pair<NightWindow,Map<String,Float>>>()
    private var best: List<Pair<NightWindow,Map<String,Float>>> = emptyList()
    private var bestEnergy=-1.0
    private val fine=ArrayDeque<SnoreLevel>()
    private var breaks: MutableSet<Long>? = null
    /** 卡 210：中断要等后文（鼾声回来）够了再存整段录音，供人工回听确认 */
    private val breakClips = mutableListOf<NightSpan>()
    /** 卡 210：整夜最响的几下；挂起的那一下要等后文，所以按起点留着窗口 */
    private var loud: LoudPicker? = null
    private val loudWindows = HashMap<Long,NightWindow>()
    private var zeroScores: Map<String, Float>? = null
    suspend fun accept(w: NightWindow, infer: Boolean = true): Boolean {
        val scores=if (infer) owner.model.nightScores(w.pcm).also {
            if (zeroScores == null) zeroScores = it.mapValues { 0f }
        } else checkNotNull(zeroScores)
        evidence(w,scores)
        repeat(4) {index ->
            val rms=kotlin.math.sqrt((index*8000 until (index+1)*8000).sumOf {val x=w.pcm[it]/32768.0;x*x}/8000)
            fine.addLast(SnoreLevel(w.start+index*500,NightFrames.db(rms)-w.background,(scores["Snoring"] ?: 0f)>=NightTuning.SNORE && detector.currentSnore!=null))
        }
        while(fine.isNotEmpty() && fine.first().at<w.start-120_000)fine.removeFirst()
        for (span in SnoreBreaks.find(fine.toList())) {
            val known = breaks ?: owner.store.dao.breakStarts(id).toMutableSet().also { breaks = it }
            if (span.start in known) continue
            val event = NightEvent(0,NightEventKind.SnoreBreak,span.start,span.end)
            owner.store.dao.insertBreak(id,span.start,span.end,owner.store.json.encodeToString(event))
            known.add(span.start)
            breakClips+=span
        }
        breakClips.filter { w.start>=it.end+NightTuning.BREAK_AFTER }.forEach { saveBreakClip(it);breakClips.remove(it) }
        recent.addLast(w to scores);while(recent.size>60)recent.removeFirst()  // 卡 210：中断整段最长约 76 秒，留 2 分钟
        keepLoudest(w,fine.toList().takeLast(4).maxOf { it.aboveBackground })
        val finished=detector.accept(w.start,scores,w.features)
        for((kind,span) in finished)save(kind,span)
        if(detector.currentSnore!=null) {
            val range=recent.filter { it.first.start>=detector.currentSnore!!.start }.takeLast(15)
            val energy=range.sumOf { it.first.pcm.sumOf { v->v.toDouble()*v } }/range.sumOf { it.first.pcm.size }.coerceAtLeast(1)
            if(energy>bestEnergy) {bestEnergy=energy;best=range}
        }
        return detector.detecting || (scores["Snoring"] ?: 0f) >= NightTuning.SNORE || (scores["Cough"] ?: 0f) >= NightTuning.COUGH
    }
    /** 安静前 [NightTuning.BREAK_BEFORE] 到那一声后 [NightTuning.BREAK_AFTER]：存成片段挂到这次中断上 */
    private suspend fun saveBreakClip(span: NightSpan) {
        val data=recent.filter { it.first.start+2_000>span.start-NightTuning.BREAK_BEFORE && it.first.start<span.end+NightTuning.BREAK_AFTER }
        if(data.isEmpty())return
        val dao=owner.store.dao
        val row=dao.events(id).firstOrNull { it.kind==NightEventKind.SnoreBreak.name && it.start==span.start } ?: return
        val event=owner.store.json.decodeFromString<NightEvent>(row.payload)
        if(event.clipId!=null)return
        val pcm=ShortArray(data.sumOf { it.first.pcm.size });var offset=0;data.forEach { it.first.pcm.copyInto(pcm,offset);offset+=it.first.pcm.size }
        val start=data.first().first.start
        val path=owner.files.encode(id,start,pcm)
        val loudest=data.maxBy { it.first.features.aboveBackground }
        val clip=dao.insertClip(NightClipRow(nightId=id,start=start,end=start+pcm.size/16,path=path,scores=NightFiles.scores(loudest.second),
            features=owner.store.json.encodeToString(loudest.first.features),confirmed=false,expiresAt=span.end+7*86_400_000L,candidate=false))
        dao.updateEvent(row.copy(payload=owner.store.json.encodeToString(event.copy(clipId=clip,audioAvailable=true))))
    }
    suspend fun finish() {
        breakClips.forEach { saveBreakClip(it) };breakClips.clear()
        for((kind,span) in detector.finish())save(kind,span);loud?.flush()?.let { commitLoud(it.first,it.second) }
    }
    /** 比已留的都响就等后文够了再存前后约 15 秒；只留最响几段，挤掉的连录音一起删 */
    private suspend fun keepLoudest(w: NightWindow,db: Int) {
        val picker=loud ?: LoudPicker(owner.store.dao.events(id).filter { it.kind==NightEventKind.Loud.name }.map { row->
            LoudPicker.Pick(row.id,row.start,owner.store.json.decodeFromString<NightEvent>(row.payload).aboveBackgroundDb ?: 0) }).also { loud=it }
        if(db>=NightTuning.LOUD_MIN_DB)loudWindows[w.start]=w
        picker.offer(w.start,db)?.let { (at,d)-> commitLoud(at,d) }
        loudWindows.keys.removeAll { it<w.start-NightTuning.LOUD_AFTER-NightTuning.LOUD_GAP }
    }
    private suspend fun commitLoud(at: Long,db: Int) {
        val picker=loud ?: return
        val data=recent.filter { it.first.start>=at-NightTuning.LOUD_BEFORE && it.first.start<=at+NightTuning.LOUD_AFTER-2_000 }
        val peak=loudWindows[at] ?: data.firstOrNull { it.first.start==at }?.first ?: return
        if(data.isEmpty())return
        val pcm=ShortArray(data.sumOf { it.first.pcm.size });var offset=0;data.forEach { it.first.pcm.copyInto(pcm,offset);offset+=it.first.pcm.size }
        val start=data.first().first.start
        val path=owner.files.encode(id,start,pcm)
        val scores=(data.firstOrNull { it.first.start==at } ?: data.first()).second
        val clip=owner.store.dao.insertClip(NightClipRow(nightId=id,start=start,end=start+pcm.size/16,path=path,scores=NightFiles.scores(scores),features=owner.store.json.encodeToString(peak.features),confirmed=false,expiresAt=at+7*86_400_000L,candidate=false))
        val event=owner.store.addEvent(id,NightEvent(0,NightEventKind.Loud,at,at+2_000,db,clipId=clip,audioAvailable=true))
        val drop=picker.kept(event,at,db)
        for(old in drop) {
            val row=owner.store.dao.event(old) ?: continue
            owner.store.dao.deleteEvent(old)
            owner.store.json.decodeFromString<NightEvent>(row.payload).clipId?.let { owner.store.dao.clip(it) }?.let { c->
                c.path?.let { owner.store.db.privacy().queueCleanup(app.resound.data.PrivacyFileCleanup(owner.files.cleanupPath(it))) };owner.store.dao.updateClip(c.copy(path=null)) }
        }
        if(drop.isNotEmpty())owner.cleanup()
    }
    private suspend fun save(kind: NightEventKind,span: NightSpan) {
        val dao=owner.store.dao
        if(dao.events(id).any { it.kind==kind.name && it.start==span.start && it.end==span.end })return
        val data=if(kind==NightEventKind.Snore)best.ifEmpty { recent.filter { it.first.start>=span.start && it.first.start<span.end }.takeLast(15) }
            else recent.filter { it.first.start>=span.start-2_000 && it.first.start<span.end+2_000 }.take(5)
        if(data.isEmpty())return
        val pcm=ShortArray(data.sumOf { it.first.pcm.size });var offset=0;data.forEach { it.first.pcm.copyInto(pcm,offset);offset+=it.first.pcm.size }
        val path=owner.files.encode(id,data.first().first.start,pcm)
        val representative=data.maxBy { it.first.features.aboveBackground }
        val feature=representative.first.features
        val clip=dao.insertClip(NightClipRow(nightId=id,start=data.first().first.start,end=data.first().first.start+pcm.size/16,path=path,scores=NightFiles.scores(representative.second),features=owner.store.json.encodeToString(feature),confirmed=false,expiresAt=span.end+7*86_400_000L,candidate=false))
        val score=representative.second[if(kind==NightEventKind.Snore)"Snoring" else "Cough"] ?: 0f
        val threshold=if(kind==NightEventKind.Snore)NightTuning.SNORE else NightTuning.COUGH
        owner.store.addEvent(id,NightEvent(0,kind,span.start,span.end,feature.aboveBackground,clipId=clip,audioAvailable=true),((score-threshold)/(1-threshold)*.7+feature.rhythm*.3).coerceIn(0.0,1.0))
        if(kind==NightEventKind.Snore) {best=emptyList();bestEnergy=-1.0}
    }
}
