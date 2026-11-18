package app.resound.night

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class NightFeatures(val rhythm: Double, val modulation: Double, val aboveBackground: Int)
data class NightWindow(val start: Long,val pcm: ShortArray,val speech: Boolean,val rms: Double,val background: Int,val features: NightFeatures)
/** 单一录音生产者；只累加、复制、trySend，不等待推理。队列最多 30 秒。 */
class NightFrames(private val dropped: ()->Unit) {
    val windows=Channel<NightWindow>(15,BufferOverflow.DROP_OLDEST,onUndeliveredElement={dropped()})
    private var pcm=ShortArray(NightTuning.WINDOW);private var used=0;private var epoch: Long?=null;private var samples=0L
    private var voice=false;private val envelope=ArrayDeque<Double>();private val history=ArrayDeque<Int>()
    fun accept(frame: ShortArray,speech: Boolean,at: Long) {
        if(epoch!=null && at-(checkNotNull(epoch)+(samples+used)*1000/NightTuning.RATE)>1_000) { epoch=null;samples=0;used=0;voice=false }
        if(epoch==null)epoch=at
        var offset=0
        while(offset<frame.size) {
            val n=minOf(frame.size-offset,pcm.size-used);frame.copyInto(pcm,used,offset,offset+n);used+=n;offset+=n;voice=voice||speech
            if(used==pcm.size) {
                val rms=sqrt(pcm.sumOf { val v=it/32768.0;v*v }/pcm.size)
                repeat(4) { j->envelope.addLast(sqrt((j*8000 until (j+1)*8000).sumOf { val v=pcm[it]/32768.0;v*v }/8000));if(envelope.size>60)envelope.removeFirst() }
                val db=db(rms);val background=if(history.isEmpty())db else NightRules.median(history.map(Int::toLong)).toInt()
                val features=NightFeatures(NightRhythm.correlation(envelope.toList()),NightRhythm.modulation(envelope.toList()),db-background)
                windows.trySend(NightWindow(checkNotNull(epoch)+samples*1000/NightTuning.RATE,pcm,voice,rms,background,features))
                history.addLast(db);if(history.size>300)history.removeFirst()
                samples+=pcm.size;pcm=ShortArray(NightTuning.WINDOW);used=0;voice=false
            }
        }
    }
    fun close() { windows.close() }
    companion object { fun db(rms: Double)= (20*log10(rms.coerceAtLeast(1e-6))).roundToInt().coerceIn(-120,0) }
}
object NightRhythm {
    // 202-证据/continuous-selection.json：只用调参来源和背景压力集；同分取更严格值。待真机校准。
    const val CORRELATION=.7
    const val MODULATION=.3
    fun correlation(envelope: List<Double>): Double {
        if(envelope.size<12)return 0.0
        val values=envelope.takeLast(60);val mean=values.average();val c=values.map { it-mean };var best=0.0
        for(lag in 4..minOf(12,c.size/2)) {
            var dot=0.0;var aa=0.0;var bb=0.0
            for(i in lag until c.size) {val a=c[i-lag];val b=c[i];dot+=a*b;aa+=a*a;bb+=b*b}
            if(aa>1e-15 && bb>1e-15)best=maxOf(best,dot/sqrt(aa*bb))
        }
        return best
    }
    fun modulation(values: List<Double>): Double { if(values.isEmpty())return 0.0;val mean=values.average();return sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size)/(mean+1e-12) }
    fun accepts(positives: Int,features: NightFeatures) = positives>=3 && features.rhythm>=CORRELATION && features.modulation>=MODULATION
}
/** 先存后认、实时识别共用。窗口时间可有缺口，不能把候选片段拼成无缝音频。 */
class NightDetector {
    private val positive=ArrayDeque<Long>()
    private var snore: NightSpan?=null
    val currentSnore get()=snore
    private var cough: NightSpan?=null
    val detecting get() = snore != null || cough != null
    fun accept(at: Long,scores: Map<String,Float>,features: NightFeatures): List<Pair<NightEventKind,NightSpan>> {
        val result=mutableListOf<Pair<NightEventKind,NightSpan>>()
        while(positive.isNotEmpty() && positive.first()<at-28_000)positive.removeFirst()
        val hit=(scores["Snoring"] ?: 0f)>=NightTuning.SNORE
        if(hit)positive.addLast(at)
        val old=snore
        if(old!=null && at-old.end>=30_000) {result+=NightEventKind.Snore to old;snore=null}
        if(hit && (snore!=null || NightRhythm.accepts(positive.size,features)))snore=NightSpan(snore?.start ?: positive.first(),at+2_000)
        val c=cough
        if(c!=null && at-c.end>=3_000) {result+=NightEventKind.Cough to c;cough=null}
        if((scores["Cough"] ?: 0f)>=NightTuning.COUGH)cough=NightSpan(cough?.start ?: at,at+2_000)
        return result
    }
    fun finish(): List<Pair<NightEventKind,NightSpan>> = listOfNotNull(snore?.let { NightEventKind.Snore to it },cough?.let { NightEventKind.Cough to it }).also {snore=null;cough=null;positive.clear()}
}
