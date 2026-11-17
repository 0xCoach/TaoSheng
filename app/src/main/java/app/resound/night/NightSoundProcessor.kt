package app.resound.night

import kotlinx.serialization.encodeToString

/** 同一串行消费者调用，模型和编码不在麦克风读取协程里运行。 */
class NightSoundProcessor(private val owner: AndroidNight,private val id: Long,private val evidence: (NightWindow,Map<String,Float>)->Unit = { _,_-> }) {
    private val detector=NightDetector()
    private val recent=ArrayDeque<Pair<NightWindow,Map<String,Float>>>()
    private var best: List<Pair<NightWindow,Map<String,Float>>> = emptyList()
    private var bestEnergy=-1.0
    private val fine=ArrayDeque<SnoreLevel>()
    private val breaks=mutableSetOf<Long>()
    suspend fun accept(w: NightWindow) {
        val scores=owner.model.nightScores(w.pcm)
        evidence(w,scores)
        repeat(4) {index ->
            val rms=kotlin.math.sqrt((index*8000 until (index+1)*8000).sumOf {val x=w.pcm[it]/32768.0;x*x}/8000)
            fine.addLast(SnoreLevel(w.start+index*500,NightFrames.db(rms)-w.background,(scores["Snoring"] ?: 0f)>=NightTuning.SNORE && detector.currentSnore!=null))
        }
        while(fine.isNotEmpty() && fine.first().at<w.start-120_000)fine.removeFirst()
        for(span in SnoreBreaks.find(fine.toList()))if(breaks.add(span.start)) {
            if(owner.store.dao.events(id).none {it.kind=="SnoreBreak" && it.start==span.start})owner.store.addEvent(id,NightEvent(0,NightEventKind.SnoreBreak,span.start,span.end))
        }
        recent.addLast(w to scores);while(recent.size>45)recent.removeFirst()
        val finished=detector.accept(w.start,scores,w.features)
        for((kind,span) in finished)save(kind,span)
        if(detector.currentSnore!=null) {
            val range=recent.filter { it.first.start>=detector.currentSnore!!.start }.takeLast(15)
            val energy=range.sumOf { it.first.pcm.sumOf { v->v.toDouble()*v } }/range.sumOf { it.first.pcm.size }.coerceAtLeast(1)
            if(energy>bestEnergy) {bestEnergy=energy;best=range}
        }
    }
    suspend fun finish() {for((kind,span) in detector.finish())save(kind,span)}
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
