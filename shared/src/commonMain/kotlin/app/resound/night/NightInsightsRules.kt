@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.night

import app.resound.platform.ZoneHistory
import kotlinx.datetime.*
import kotlin.math.*

object NightTags {
    val presets=listOf("侧睡","没喝酒","喝了酒","换了枕头","止鼾贴","感冒/鼻塞","睡前运动","睡前喝咖啡或茶","很累")
    val phrases=mapOf("喝了酒" to listOf("喝了","喝酒","干杯"),"很累" to listOf("好累","累死了"),"感冒/鼻塞" to listOf("感冒","鼻塞"),"睡前喝咖啡或茶" to listOf("咖啡","喝茶"))
    fun suggest(text: String,tags: List<NightTag>)=tags.filter { tag->phrases[tag.name]?.any { it in text }==true }
    fun findings(nights: List<NightSummary>,tags: List<NightTag>): List<TagFinding> {
        val recent=nights.sortedByDescending { it.date }.take(60).filter { it.confidence!=NightConfidence.Rough && it.state==NightState.Ready }
        return tags.flatMap { tag->
            val yes=recent.filter { s->s.tags.any {it.id==tag.id} };val no=recent.filter { s->s.tags.none {it.id==tag.id} }
            if(yes.size<3 || no.size<3)return@flatMap emptyList()
            CompareMetric.entries.mapNotNull { metric->
                fun value(s: NightSummary): Long?=when(metric) {CompareMetric.Snore->s.snoreMillis;CompareMetric.Sleep->s.sleepMillis;CompareMetric.Latency->s.times?.let { it.onset-it.bedtime };CompareMetric.Awake->s.awakeCount.toLong()}
                val a=yes.mapNotNull(::value);val b=no.mapNotNull(::value)
                if(a.size<3 || b.size<3)return@mapNotNull null
                val am=NightRules.median(a);val bm=NightRules.median(b);val diff=am-bm
                val min=if(metric==CompareMetric.Awake)1L else 600_000L
                if(abs(diff)<min || abs(diff).toDouble()/maxOf(1,bm)<.2)return@mapNotNull null
                val amount=if(metric==CompareMetric.Awake)"${abs(diff)} 次" else "${abs(diff)/60_000} 分钟"
                TagFinding(tag,metric,a.size,b.size,am,bm,"标了‘${tag.name}’的 ${a.size} 晚，${metric.title}中位数比其他 ${b.size} 晚${if(diff<0)"少" else "多"} $amount（可能有关，样本还少）")
            }
        }
    }
}
object NightWeeks {
    fun monday(date: LocalDate)=date.minus(date.dayOfWeek.ordinal,DateTimeUnit.DAY)
    fun due(today: LocalDate,hour: Int): List<LocalDate> {
        val week=monday(today)
        val latest=if(today==week && hour<9)week.minus(14,DateTimeUnit.DAY) else week.minus(7,DateTimeUnit.DAY)
        return (0..3).map {latest.minus(it*7,DateTimeUnit.DAY)}
    }
    fun path(start: LocalDate): String {
        val year=start.plus(3,DateTimeUnit.DAY).year
        val first=monday(LocalDate(year,1,4));val number=(start.toEpochDays()-first.toEpochDays())/7+1
        return "周报/$year-W${number.toString().padStart(2,'0')}.md"
    }
    fun build(start: LocalDate,nights: List<NightSummary>,findings: List<TagFinding>,zones: ZoneHistory,at: Long): WeeklyReport? {
        val end=start.plus(7,DateTimeUnit.DAY)
        val inWeek=nights.filter {it.date>=start && it.date<end}
        if(inWeek.any {it.state in setOf(NightState.Active,NightState.Provisional,NightState.Processing)})return null
        val valid=inWeek.filter {it.state==NightState.Ready && it.confidence!=NightConfidence.Rough && it.sleepMillis!=null && it.times!=null}.sortedBy {it.date}
        if(valid.size<3)return null
        val sleep=valid.mapNotNull {it.sleepMillis}.average().toLong()
        val clocks=valid.map {NightRules.minute(it.times!!.onset,zones).let {m->if(m>=720)m-1440 else m}.toDouble()}
        val mean=clocks.average();val spread=sqrt(clocks.sumOf {(it-mean)*(it-mean)}/clocks.size).roundToInt()
        val snore=valid.sumOf {it.snoreMillis}
        val before=nights.filter {it.date>=start.minus(28,DateTimeUnit.DAY) && it.date<start && it.confidence!=NightConfidence.Rough}.mapNotNull {it.sleepMillis}
        val diff=if(before.size>=3)sleep-before.average().toLong() else 0L
        val comparison=if(abs(diff)>=600_000)"本周平均睡眠比前四周${if(diff>0)"多" else "少"} ${abs(diff)/60_000} 分钟（可能存在变化）" else null
        val choices=findings.map {abs(it.withMedian-it.withoutMedian).toDouble()/maxOf(1,it.withoutMedian) to it.text}+listOfNotNull(comparison?.let {abs(diff)/before.average().coerceAtLeast(1.0) to it})
        val finding=choices.maxByOrNull {it.first}?.second ?: "本周记录了 ${valid.size} 晚，暂未发现明显变化。"
        val suggestion=if(snore/valid.size>=600_000)"可以试试侧睡一周，标记后观察变化。" else "可以试试提前放下手机，观察一周的入睡时间。"
        val text=buildString {
            appendLine("# 夜里周报 · $start\n\n仅供参考，不是医疗检查结果。")
            appendLine("\n| 日期 | 上床 | 约入睡 | 起床 | 约睡眠 |\n|---|---|---|---|---|")
            valid.forEach {s->val t=s.times!!;appendLine("| ${s.date} | ${zones.clock(t.bedtime)} | ${zones.clock(t.onset)} | ${zones.clock(t.wake)} | ${NightRules.duration(s.sleepMillis!!)} |")}
            appendLine("\n平均睡眠 ${NightRules.duration(sleep)}；入睡时间前后差不多 $spread 分钟（标准差）。")
            appendLine("疑似鼾声总计 ${NightRules.duration(snore)}，每晚平均 ${NightRules.duration(snore/valid.size)}。")
            appendLine("疑似咳嗽/清嗓 ${valid.sumOf {it.coughCount}} 次，夜间人声 ${valid.sumOf {it.talkCount}} 段，醒来 ${valid.sumOf {it.awakeCount}} 次。")
            finding?.let {appendLine("\n一个发现：$it")};appendLine("\n试试看：$suggestion")
        }.trimEnd()
        return WeeklyReport(start,valid.size,text,finding,suggestion,false,at)
    }
}
data class SnoreLevel(val at: Long,val aboveBackground: Int,val snore: Boolean)
object SnoreBreaks {
    fun find(points: List<SnoreLevel>,step: Long=500): List<NightSpan> {
        val out=mutableListOf<NightSpan>();var i=0
        while(i<points.size) {
            val first=points[i]
            if(first.aboveBackground>3 || (maxOf(0,i-(30_000/step).toInt()) until i).none {points[it].snore}) {i++;continue}
            var end=i
            while(end<points.size && points[end].aboveBackground<=3 && (end==i || points[end].at-points[end-1].at==step))end++
            val quiet=(end-i)*step
            if(quiet !in 10_000..60_000 || end>=points.size || points[end].aboveBackground<15 || points[end].at-points[end-1].at!=step) {i=maxOf(i+1,end);continue}
            var burst=end
            while(burst<points.size && points[burst].aboveBackground>=15 && (burst==end || points[burst].at-points[burst-1].at==step))burst++
            if((burst-end)*step<=3_000 && points.drop(burst).takeWhile {it.at<=points[end].at+30_000}.any {it.snore} && (i until burst).all {it==i || points[it].at-points[it-1].at==step})out+=NightSpan(first.at,points[burst-1].at+step)
            i=maxOf(i+1,burst)
        }
        return out
    }
    const val NOTICE="最近几晚鼾声中断比较多，如果白天也常犯困，可以找医生做一次睡眠检查。"
    fun shouldRemind(nights: List<NightSummary>,at: Long,lastAt: Long?,muted: Boolean): Boolean = !muted && (lastAt==null || at-lastAt>=30*86_400_000L) && nights.sortedByDescending {it.date}.take(7).count {it.confidence in setOf(NightConfidence.Good,NightConfidence.Fair) && (it.sleepMillis ?: 0)>0 && it.snoreBreaks*3_600_000.0/it.sleepMillis!!>=5}>=3
}
object NightExports {
    fun loudestStart(energy: List<Double>,samplesPerPoint: Int,rate: Int=16_000): Int {
        if(energy.isEmpty())return 0
        val count=(15*rate/samplesPerPoint).coerceAtLeast(1).coerceAtMost(energy.size);var sum=energy.take(count).sum();var best=sum;var start=0
        for(i in count until energy.size) {sum+=energy[i]-energy[i-count];if(sum>best) {best=sum;start=i-count+1}}
        return start*samplesPerPoint
    }
    fun doctor(from: LocalDate,to: LocalDate,nights: List<NightSummary>,zones: ZoneHistory): String=buildString {
        appendLine("本资料由手机录音自动统计，仅供参考，不是医疗检查结果。\n\n# 夜里记录 · $from 至 $to")
        appendLine("\n| 日期 | 约入睡 | 起床 | 约睡眠 | 疑似鼾声 | 咳嗽/清嗓 | 醒来 | 鼾声中断 | 可信度 | 标签 |\n|---|---|---|---|---|---|---|---|---|---|")
        nights.filter {it.date>=from && it.date<=to}.sortedBy {it.date}.forEach {s->appendLine("| ${s.date} | ${s.times?.let {zones.clock(it.onset)} ?: "—"} | ${s.times?.let {zones.clock(it.wake)} ?: "—"} | ${s.sleepMillis?.let(NightRules::duration) ?: "—"} | ${NightRules.duration(s.snoreMillis)} | ${s.coughCount} | ${s.awakeCount} | ${s.snoreBreaks} | ${s.confidence?.title ?: "仅供参考"} | ${s.tags.joinToString("、") {it.name.replace('|',' ')}} |")}
        val valid=nights.filter {it.date>=from && it.date<=to && it.confidence!=NightConfidence.Rough && it.state==NightState.Ready}
        val sleep=valid.mapNotNull {it.sleepMillis}
        if(sleep.isNotEmpty())appendLine("\n有效记录 ${sleep.size} 晚，平均约睡 ${NightRules.duration(sleep.average().toLong())}；鼾声中断共 ${valid.sumOf {it.snoreBreaks}} 次。")
        val clocks=valid.mapNotNull {it.times?.onset}.map {NightRules.minute(it,zones).let {m->if(m>=720)m-1440 else m}.toDouble()}
        if(clocks.size>=3) {val mean=clocks.average();appendLine("入睡时间前后差不多 ${sqrt(clocks.sumOf {(it-mean)*(it-mean)}/clocks.size).roundToInt()} 分钟（标准差）。")}
        appendLine("\n记录可能受手机位置、背景响度、缺口和模型误判影响；不能用于医学诊断。")
    }.trimEnd()
}
