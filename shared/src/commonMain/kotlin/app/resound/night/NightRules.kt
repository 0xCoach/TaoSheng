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
    // 卡 210：整夜最响的几下。至少比背景高 15 分贝才算，留 3 段；彼此隔 15 秒以上；存峰值前 6 秒到后约 8 秒
    const val LOUD_MIN_DB = 15
    const val LOUD_KEEP = 3
    const val LOUD_GAP = 15_000L
    const val LOUD_BEFORE = 6_000L
    const val LOUD_AFTER = 10_000L
    /** 卡 213：醒着（亮屏、拿起）前后这么久的声音不算“睡着时最响” */
    const val LOUD_AWAKE_MARGIN = 30_000L
    // 卡 210：鼾声中断的回听片段：安静开始前 5 秒到那一声后 8 秒
    const val BREAK_BEFORE = 5_000L
    const val BREAK_AFTER = 8_000L
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
    val calls: Int = 0, val inferenceMillis: Long = 0, val startBattery: Int? = null, val endBattery: Int? = null,
    val nap: Boolean = false, val deadline: Long? = null)
data class NightInput(val at: Long, val minute: Int, val screenOffAt: Long?, val screenOnAt: Long?, val stillSince: Long?,
    val lastVoiceAt: Long?, val pickedUp: Boolean, val charging: Boolean, val battery: Int?, val hot: Boolean, val playback: Boolean,
    /** 卡 225：最近几晚的起床时刻（当地分钟，中位数）；不足 3 晚为 null */
    val usualWakeMinute: Int? = null)
enum class NightTransition { Wait, Enter, Confirm, Cancel, Continue, Finish }
object NightRules {
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = if(start < end) minute in start until end else minute >= start || minute < end
    fun validate(s: NightSettings) { require(s.windowStartMinute in 0..1439 && s.windowEndMinute in 0..1439) }
    fun conflict(s: NightSettings, rules: List<QuietRule>, auto: SleepWindow = SleepWindows.fallback): QuietRule? {
        val windows = SleepWindows.resolved(s.windows, auto).filter { it.enabled }
        return rules.firstOrNull { rule -> rule.enabled && rule.days.isNotEmpty() && windows.any {
            it.contains(rule.startMinute) || inWindow(it.startMinute, rule.startMinute, rule.endMinute)
        } }
    }
    fun mode(s: NightSettings, battery: Int?, charging: Boolean, hot: Boolean): NightMode = when {
        !s.enabled || battery != null && battery < 10 -> NightMode.Off
        battery != null && battery < 15 -> NightMode.TimesOnly
        s.fullNight && (charging || battery != null && battery >= 30) && !hot -> NightMode.Full
        else -> NightMode.Deferred
    }
    fun transition(s: NightSettings, session: NightSession?, input: NightInput): NightTransition =
        transition(s.enabled && inWindow(input.minute,s.windowStartMinute,s.windowEndMinute), false, session, input)
    fun transition(inside: Boolean, nap: Boolean, session: NightSession?, input: NightInput): NightTransition = with(input) {
        if (nap) return NapRules.transition(inside, session, input)
        if (session == null) return if(inside && screenOffAt != null && (charging || stillSince != null && at-stillSince >= 600_000) && (lastVoiceAt == null || at-lastVoiceAt >= 600_000)) NightTransition.Enter else NightTransition.Wait
        if(!inside) return if(session.confirmedAt == null) NightTransition.Cancel else NightTransition.Finish
        if(session.confirmedAt == null) {
            if(pickedUp || screenOnAt != null && at-screenOnAt >= 120_000) return NightTransition.Cancel
            val uninterruptedSince=maxOf(session.provisionalAt,session.signals.filter { it.kind=="screen" && it.end-it.start>60_000 }.maxOfOrNull { it.end } ?: session.provisionalAt)
            if(at-uninterruptedSince >= 1_200_000 && screenOffAt != null) return NightTransition.Confirm
        } else if(at-session.confirmedAt >= 3*3_600_000 && WakeRules.shouldFinish(session,at,minute,usualWakeMinute)) return NightTransition.Finish
        NightTransition.Continue
    }
    /** 这一夜结束后（整理中/已整理），同一个早上不再重新开始记录；中断的那一夜可以接着记 */
    fun canReopen(state: NightState?) = state==null || state!=NightState.Processing && state!=NightState.Ready
    /** #59：中断的一夜在时段结束前先不整理成 Ready，否则重启后当晚就不能再续记（canReopen 不认 Ready）。
     * 没有 deadline 的旧记录照旧立即整理。 */
    fun readyToProcess(state: NightState, deadline: Long?, now: Long) =
        state==NightState.Processing || state==NightState.Interrupted && (deadline ?: Long.MIN_VALUE) <= now
    fun median(values: List<Long>): Long { require(values.isNotEmpty());val v=values.sorted();val i=v.size/2;return if(v.size%2==1)v[i] else v[i-1]+(v[i]-v[i-1])/2 }
    /** 卡 213：“最响的几下”只算睡着时——入睡到起床之间，且不在醒着前后 [NightTuning.LOUD_AWAKE_MARGIN] 内。
     * 显示时筛，不删数据：改了入睡/起床时间会跟着变。还没算出时间（夜里进行中）时不筛。 */
    fun loudWhileAsleep(e: NightEvent, times: NightTimes?, awake: List<NightSpan>): Boolean {
        if (e.kind != NightEventKind.Loud || times == null) return true
        if (e.start < times.onset || e.start >= times.wake) return false
        val m = NightTuning.LOUD_AWAKE_MARGIN
        return awake.none { e.start >= it.start - m && e.start < it.end + m }
    }
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
/**
 * 起床判断（11-18）：醒来的证据是动手机——解锁、拔充电线、关闹钟、拿起、亮屏。
 * 睡满 3 小时后，早上 10 分钟内出现两类证据就马上算起床；
 * 零星一次（看时间、起夜），30 分钟内没重新安静满 15 分钟才算起床。起床时间取这一串动作的第一下。
 */
object WakeRules {
    const val CLUSTER = 600_000L
    const val SETTLE = 1_800_000L
    const val RESLEEP = 900_000L
    const val PICKUPS = 3
    const val SCREEN = 120_000L
    /** 早上 5 点以后才按“两类证据”立刻定；更早的醒来都要等 30 分钟看会不会接着睡 */
    const val MORNING_MINUTE = 5 * 60
    val POINT_KINDS = setOf("unlock", "unplug", "alarm-off", "pickup")
    fun evidence(signals: List<NightSignal>) = signals.filter { it.kind in POINT_KINDS || it.kind=="screen" && it.end-it.start>=30_000 }.sortedBy { it.start }
    /** 还没“重新睡着”的这一串醒来动作的第一下；没有为 null。首页柱子停在这里 */
    fun pending(session: NightSession): Long? {
        val after=session.confirmedAt ?: return null
        val settled=maxOf(after,session.signals.filter { it.kind=="quiet" && it.end-it.start>=RESLEEP }.maxOfOrNull { it.end } ?: after)
        return evidence(session.signals).firstOrNull { it.end>=settled && it.start>=after }?.start
    }
    /** [from] 起 10 分钟内满足几类证据 */
    fun kinds(signals: List<NightSignal>, from: Long): Int {
        val to=from+CLUSTER
        val inside=signals.filter { it.start in from..to }
        val screen=signals.filter { it.kind=="screen" }.sumOf { (minOf(it.end,to)-maxOf(it.start,from)).coerceAtLeast(0) }
        return listOf(inside.any { it.kind=="unlock" }, inside.any { it.kind=="unplug" }, inside.any { it.kind=="alarm-off" },
            inside.count { it.kind=="pickup" }>=PICKUPS, screen>=SCREEN).count { it }
    }
    /**
     * 卡 225：夜里（18 点到早上 5 点）两段睡眠之间看手机，不管多久都只算“醒了”，不结束这一夜；
     * 真起床了就等到 5 点以后按早上的规则收尾，起床时间仍取那一串动作的第一下。
     */
    const val EVENING_MINUTE = 18 * 60
    private fun deepNight(minute: Int) = minute < MORNING_MINUTE || minute >= EVENING_MINUTE
    /** 比平时起床早这么多分钟，或闹钟还要过半小时以上才响：像是醒来看一眼，先不当起床 */
    const val EARLY_MARGIN = 60
    const val ALARM_AHEAD = 1_800_000L
    /** 偏早的醒来，连续醒着（没重新安静 15 分钟）这么久才算起床；更短就当“醒了一次”等回笼觉 */
    const val EARLY_HOLD = 2_700_000L
    /** 按开始醒的那一下 [start] 判断 */
    fun early(session: NightSession, start: Long, at: Long, minute: Int, usualWakeMinute: Int?): Boolean {
        val alarm = session.signals.lastOrNull { it.kind == "alarm" }?.value?.toLong()
        if (alarm != null && alarm - start >= ALARM_AHEAD && alarm - start <= 6 * 3_600_000L) return true
        return usualWakeMinute != null && minuteOf(start, at, minute) < usualWakeMinute - EARLY_MARGIN
    }
    /** 这一刻能不能把这一夜定为起床结束 */
    fun shouldFinish(session: NightSession, at: Long, minute: Int, usualWakeMinute: Int? = null): Boolean {
        if (deepNight(minute)) return false
        val start = pending(session) ?: return false
        if (early(session, start, at, minute, usualWakeMinute)) {
            // 从 5 点或开始醒的那一下（取晚的）算起：半夜失眠到 5 点后又睡着，不会一到 5 点就被收尾
            val morningStart = at - (minute - MORNING_MINUTE) * 60_000L
            if (at - maxOf(start, morningStart) < EARLY_HOLD) return false
        }
        return decide(session, at, minute) != null
    }
    /** 最近几晚起床时刻的中位数（当地分钟）；不足 3 晚不猜 */
    fun usualWake(minutes: List<Int>): Int? = if (minutes.size < 3) null else NightRules.median(minutes.map(Int::toLong)).toInt()
    /** 早上醒来后手机放着去洗漱，会像“又安静了”；不算回笼觉，往前 45 分钟内早上的动作也算这次起床 */
    const val LOOKBACK = 2_700_000L
    private fun morning(minute: Int) = minute>=MORNING_MINUTE && minute<15*60
    /** 算起床就返回起床时间，否则 null。[minute] 是 [at] 的当地分钟 */
    fun decide(session: NightSession, at: Long, minute: Int): Long? {
        val start=pending(session) ?: return null
        val all=evidence(session.signals)
        val strong=morning(minute) && all.filter { it.start>=start }.any { kinds(session.signals,it.start)>=2 }
        if(!strong && at-start<SETTLE) return null
        return lookBack(session,start,at,minute)
    }
    /** 时间段结束被迫收尾时：早上最后一串动作算起床（之后手机一直放着）；早上没动过手机为 null */
    fun atWindowEnd(session: NightSession, at: Long, minute: Int): Long? {
        val after=session.confirmedAt ?: return null
        val last=evidence(session.signals).lastOrNull { it.start>=after && morning(minuteOf(it.start,at,minute)) } ?: return null
        return lookBack(session,last.start,at,minute)
    }
    private fun minuteOf(t: Long, at: Long, minute: Int)=((minute-((at-t)/60_000).toInt())%1440+1440)%1440
    private fun lookBack(session: NightSession, start: Long, at: Long, minute: Int): Long {
        val all=evidence(session.signals)
        var wake=start
        while(true) wake=all.firstOrNull { it.start>=wake-LOOKBACK && it.start<wake && it.start>=(session.confirmedAt ?: 0) && morning(minuteOf(it.start,at,minute)) }?.start ?: break
        return wake
    }
}
data class SleepResult(val times: NightTimes,val awake: List<NightSpan>,val millis: Long,val count: Int)
object SleepEstimate {
    /** 卡 225：这次醒来里亮屏的总时长 */
    fun phoneMillis(span: NightSpan, signals: List<NightSignal>) = NightRules.merge(signals.filter { it.kind == "screen" }
        .mapNotNull { val a = maxOf(it.start, span.start); val b = minOf(it.end, span.end); if (b > a) NightSpan(a, b) else null }).sumOf { it.millis }
    /** 醒来那条的说明：看手机 6 分 / 看了一眼手机 / 拿起手机 / 醒着说话 */
    fun awakeText(span: NightSpan, signals: List<NightSignal>): String {
        val phone = phoneMillis(span, signals)
        return when {
            phone >= 60_000 -> "看手机 ${NightRules.duration(phone)}"
            phone > 0 -> "看了一眼手机"
            signals.any { it.kind == "pickup" && it.start in span.start..span.end } -> "拿起手机"
            else -> "醒着说话"
        }
    }
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
        val estimatedOnset=if (session.nap) session.provisionalAt else listOfNotNull(onset,snore).minOrNull()?.coerceIn(session.screenOff,end) ?: end
        val candidates=awake.filter { it.start>=estimatedOnset }
        val alarm=signals.lastOrNull { it.kind=="alarm" }?.value?.toLong()
        val last=candidates.lastOrNull { a->alarm!=null && abs(a.start-alarm)<=1_800_000 && signals.none { it.kind=="quiet" && it.start>=a.end && it.end-it.start>=900_000 } } ?: candidates.lastOrNull()
        // 退出由状态机确认。若最后一次醒来后重新睡满 15 分钟，不把那次当起床。
        val resumed=last!=null && signals.any { it.kind=="quiet" && it.start>=last.end && it.end-it.start>=900_000 }
        val decided=signals.lastOrNull { it.kind=="wake" }?.value?.toLong()
        val wake=decided ?: if (session.nap) end else if(last!=null && !resumed)last.start else end
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
