@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.night

import app.resound.capture.QuietRule
import app.resound.platform.ZoneHistory
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.math.*
import kotlin.time.Instant

object NightTuning {
    const val MINUTE = 60_000L
    const val RATE = 16_000
    const val WINDOW = 32_000
    // 来源：snore-eval 2026-11-17；不将 Breathing 并入 Snoring。
    const val SNORE = .10f
    const val COUGH = .50f
    const val CANDIDATE_DB = 6
    const val CANDIDATE_LIMIT = 60L * 1024 * 1024
    const val STILL_ANGLE = 5.0
    const val PICKUP_ANGLE = 30.0
    // (m/s²)²；床头传感器经验初值，待真机校准。
    const val STILL_VARIANCE = .02
    const val PICKUP_VARIANCE = .8
    const val NOISY_DB = -40
    const val VERY_NOISY_DB = -25
}
@Serializable data class NightSpan(val start: Long, val end: Long) { val millis get() = (end-start).coerceAtLeast(0) }
@Serializable data class NightSignal(val start: Long, val end: Long, val kind: String, val value: Double = 0.0, val text: String? = null)
@Serializable data class NightSession(val screenOff: Long, val provisionalAt: Long, val confirmedAt: Long? = null,
    val lastAt: Long, val signals: List<NightSignal> = emptyList(), val modes: List<NightModeSpan> = emptyList(),
    val originalTimes: NightTimes? = null, val dropped: Int = 0, val candidateBytes: Long = 0,
    val calls: Int = 0, val inferenceMillis: Long = 0, val startBattery: Int? = null, val endBattery: Int? = null)
data class NightInput(val at: Long, val minute: Int, val screenOffAt: Long?, val screenOnAt: Long?, val stillSince: Long?,
    val lastVoiceAt: Long?, val pickedUp: Boolean, val charging: Boolean, val battery: Int?, val hot: Boolean, val playback: Boolean)
enum class NightTransition { Wait, Enter, Confirm, Cancel, Continue, Finish }
object NightRules {
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = if(start < end) minute in start until end else minute >= start || minute < end
    fun validate(s: NightSettings) { require(s.windowStartMinute in 0..1439 && s.windowEndMinute in 0..1439) }
    fun conflict(s: NightSettings, rules: List<QuietRule>) = rules.firstOrNull { r -> r.enabled && r.days.isNotEmpty() && (0..1439).any { inWindow(it,s.windowStartMinute,s.windowEndMinute) && inWindow(it,r.startMinute,r.endMinute) } }
    fun mode(s: NightSettings, battery: Int?, charging: Boolean, hot: Boolean): NightMode = when {
        !s.enabled || battery != null && battery < 10 -> NightMode.Off
        battery != null && battery < 15 -> NightMode.TimesOnly
        s.fullNight && (charging || battery != null && battery >= 30) && !hot -> NightMode.Full
        else -> NightMode.Deferred
    }
    fun transition(s: NightSettings, session: NightSession?, input: NightInput): NightTransition = with(input) {
        val inside=s.enabled && inWindow(minute,s.windowStartMinute,s.windowEndMinute)
        if (session == null) return if(inside && screenOffAt != null && (charging || stillSince != null && at-stillSince >= 600_000) && (lastVoiceAt == null || at-lastVoiceAt >= 600_000)) NightTransition.Enter else NightTransition.Wait
        if(!inside) return if(session.confirmedAt == null) NightTransition.Cancel else NightTransition.Finish
        if(session.confirmedAt == null) {
            if(pickedUp || screenOnAt != null && at-screenOnAt >= 120_000) return NightTransition.Cancel
            val uninterruptedSince=maxOf(session.provisionalAt,session.signals.filter { it.kind=="screen" && it.end-it.start>60_000 }.maxOfOrNull { it.end } ?: session.provisionalAt)
            if(at-uninterruptedSince >= 1_200_000 && screenOffAt != null) return NightTransition.Confirm
        } else if(at-session.confirmedAt >= 3*3_600_000) {
            val awake=session.signals.lastOrNull { it.kind=="pickup" || it.kind=="screen" && it.end-it.start>=30_000 }
            if(awake != null && at-awake.start>=1_800_000 && (screenOnAt != null || stillSince==null || stillSince<awake.start)) return NightTransition.Finish
        }
        NightTransition.Continue
    }
    fun median(values: List<Long>): Long { require(values.isNotEmpty());val v=values.sorted();val i=v.size/2;return if(v.size%2==1)v[i] else v[i-1]+(v[i]-v[i-1])/2 }
    fun merge(spans: List<NightSpan>, gap: Long=0): List<NightSpan> {
        val out=mutableListOf<NightSpan>()
        spans.sortedBy { it.start }.forEach { n -> val last=out.lastOrNull();if(last!=null && n.start-last.end<=gap)out[out.lastIndex]=NightSpan(last.start,maxOf(last.end,n.end)) else out+=n }
        return out
    }
    fun asleepMillis(t: NightTimes, awake: List<NightSpan>): Long = (t.wake-t.onset-merge(awake.mapNotNull { val a=maxOf(t.onset,it.start);val b=minOf(t.wake,it.end);if(b>a)NightSpan(a,b) else null }).sumOf { it.millis }).coerceAtLeast(0)
    fun sleepTalk(start: Long,end: Long,signals: List<NightSignal>) = signals.none { (it.kind=="screen" || it.kind=="pickup") && it.start <= end+120_000 && it.end >= start-120_000 } && signals.none { it.kind=="playback" && it.start<end && it.end>start }
    fun latency(edits: List<Long>) = if(edits.size<3) 0L else median(edits).coerceIn(0,3_600_000)
    fun date(at: Long,zones: ZoneHistory) = Instant.fromEpochMilliseconds(at).toLocalDateTime(zones.zoneAt(at)).date
    fun minute(at: Long,zones: ZoneHistory): Int { val t=Instant.fromEpochMilliseconds(at).toLocalDateTime(zones.zoneAt(at));return t.hour*60+t.minute }
    fun duration(ms: Long): String { val m=ms.coerceAtLeast(0)/60_000;return if(m>=60)"${m/60} 小时 ${m%60} 分" else "$m 分" }
}
object NightMotion {
    fun angle(a: DoubleArray,b: DoubleArray): Double { val norm=sqrt(a.sumOf { it*it }*b.sumOf { it*it });return if(norm<1e-9)0.0 else acos((a.indices.sumOf { a[it]*b[it] }/norm).coerceIn(-1.0,1.0))*180/PI }
    fun classify(angle: Double,variance: Double,highMillis: Long): String = when {
        angle>NightTuning.PICKUP_ANGLE || variance>NightTuning.PICKUP_VARIANCE && highMillis>=2_000 -> "pickup"
        angle<NightTuning.STILL_ANGLE && variance<NightTuning.STILL_VARIANCE -> "still"
        else -> "turn"
    }
}
data class SleepResult(val times: NightTimes,val awake: List<NightSpan>,val millis: Long,val count: Int)
object SleepEstimate {
    fun calculate(session: NightSession,events: List<NightEvent>,end: Long,latency: Long=0,edited: NightTimes?=null): SleepResult {
        val signals=session.signals
        val waking=signals.filter { it.kind=="pickup" || it.kind=="screen" && it.end-it.start>=30_000 }
        val awakeVoice=signals.filter { it.kind=="voice" && !NightRules.sleepTalk(it.start,it.end,signals) && signals.none { p->p.kind=="playback" && p.start<it.end && p.end>it.start } }
        val awake=NightRules.merge((waking+awakeVoice).map { NightSpan(it.start,it.end) },300_000)
        val disturbed=NightRules.merge((signals.filter { it.kind=="screen" || it.kind=="pickup" || it.kind=="loud" }+awakeVoice).map { NightSpan(it.start,it.end) })
        var quiet=session.screenOff;var onset: Long?=null
        for(d in disturbed+NightSpan(end,end)) {
            if(d.end<quiet)continue
            if(d.start-quiet>=900_000) {onset=quiet+latency;break}
            quiet=maxOf(quiet,d.end)
        }
        val snore=events.filter { it.kind==NightEventKind.Snore && it.feedback!=Feedback.No }.minOfOrNull { it.start }
        val estimatedOnset=listOfNotNull(onset,snore).minOrNull()?.coerceIn(session.screenOff,end) ?: end
        val candidates=awake.filter { it.start>=estimatedOnset }
        val alarm=signals.lastOrNull { it.kind=="alarm" }?.value?.toLong()
        val last=candidates.lastOrNull { a->alarm!=null && abs(a.start-alarm)<=1_800_000 && signals.none { it.kind=="quiet" && it.start>=a.end && it.end-it.start>=900_000 } } ?: candidates.lastOrNull()
        // 退出由状态机确认。若最后一次醒来后重新睡满 15 分钟，不把那次当起床。
        val resumed=last!=null && signals.any { it.kind=="quiet" && it.start>=last.end && it.end-it.start>=900_000 }
        val wake=if(last!=null && !resumed)last.start else end
        val estimated=NightTimes(session.screenOff,estimatedOnset,wake.coerceAtLeast(estimatedOnset),true)
        val t=estimated.copy(bedtime=edited?.takeIf { it.editedBedtime }?.bedtime ?: estimated.bedtime,
            onset=edited?.takeIf { it.editedOnset }?.onset ?: estimated.onset,wake=edited?.takeIf { it.editedWake }?.wake ?: estimated.wake,
            onsetEstimated=edited?.editedOnset!=true,editedBedtime=edited?.editedBedtime==true,editedOnset=edited?.editedOnset==true,editedWake=edited?.editedWake==true)
        val middle=awake.filter { it.start>=t.onset && it.start<t.wake }
        return SleepResult(t,middle,NightRules.asleepMillis(t,middle),middle.count { it.millis>=120_000 })
    }
}
object NightPresentation {
    fun confidence(session: NightSession,background: List<Int>): Pair<NightConfidence,List<String>> {
        val gaps=session.signals.filter { it.kind=="gap" && it.end-it.start>=300_000 }
        val median=if(background.isEmpty())-96 else NightRules.median(background.map(Int::toLong)).toInt()
        val rough=gaps.sumOf { it.end-it.start }>=1_800_000 || session.modes.any { it.mode==NightMode.TimesOnly || it.mode==NightMode.Off } || median>NightTuning.VERY_NOISY_DB || session.signals.count { it.kind=="turn" }>120 || session.candidateBytes>=NightTuning.CANDIDATE_LIMIT
        val reasons=buildList { if(gaps.isNotEmpty())add("记录有中断");if(median>NightTuning.NOISY_DB)add("背景较吵");if(session.modes.any { it.mode==NightMode.Deferred })add("部分时段先存后认");if(session.modes.any { it.mode==NightMode.TimesOnly || it.mode==NightMode.Off })add("电量较低，部分时段只记时间");if(session.dropped>0)add("识别积压丢弃 ${session.dropped} 个窗口");if(session.candidateBytes>=NightTuning.CANDIDATE_LIMIT)add("候选片段达到容量上限") }
        return (if(rough)NightConfidence.Rough else if(reasons.isEmpty())NightConfidence.Good else NightConfidence.Fair) to reasons
    }
    fun sentence(s: NightSummary,history: List<NightSummary>,sharedRoom: Boolean): String {
        val sleep=s.sleepMillis ?: return "夜里记录正在整理"
        val past=history.filter { it.id!=s.id && it.confidence!=NightConfidence.Rough }.take(14).mapNotNull { it.sleepMillis }
        val first="睡了约 ${NightRules.duration(sleep)}"
        val diff=if(past.size>=3)sleep-NightRules.median(past) else 0
        val second=when {
            abs(diff)>=600_000 -> "比平时${if(diff>0)"多" else "少"} ${NightRules.duration(abs(diff))}"
            s.snoreMillis>0 -> "${if(sharedRoom)"卧室里的" else "疑似"}鼾声约 ${NightRules.duration(s.snoreMillis)}"
            s.coughCount>0 -> "疑似咳嗽/清嗓 ${s.coughCount} 次"
            s.awakeCount>0 -> "醒了 ${s.awakeCount} 次"
            s.talkCount>0 -> "夜间人声 ${s.talkCount} 段"
            else -> null
        }
        return listOfNotNull(first,second).joinToString("；")+"。"
    }
}
