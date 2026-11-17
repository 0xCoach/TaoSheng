package app.resound.night

import app.resound.platform.ZoneHistory
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Night203Test {
    private val zones=ZoneHistory(emptyList(),"UTC")
    private fun night(day: Int,tag: NightTag?=null)=NightSummary(day.toLong(),LocalDate(2026,11,day),NightState.Ready,NightTimes(0,600_000,28_800_000,true),28_200_000,1,120_000,if(tag==null)3_600_000 else 600_000,2,1,NightConfidence.Good,emptyList(),null,emptyList(),tags=listOfNotNull(tag))
    @Test fun comparisonsRequireBothGroupsAndUseCautiousMedianText() {
        val tag=NightTag(1,"侧睡",true);val nights=(1..6).map {night(it,if(it<=3)tag else null)}
        assertTrue(NightTags.findings(nights.take(5),listOf(tag)).isEmpty())
        val finding=NightTags.findings(nights,listOf(tag)).single()
        assertEquals(3,finding.withNights);assertTrue(finding.text.contains("可能有关"));assertFalse(finding.text.contains("因为"))
    }
    @Test fun suggestionsAreLocalAndDoNotCreateConfirmedTags() {
        val tags=NightTags.presets.mapIndexed {i,s->NightTag(i.toLong(),s,true)}
        assertEquals(setOf("喝了酒","很累","感冒/鼻塞","睡前喝咖啡或茶"),NightTags.suggest("干杯，好累，鼻塞，还喝了咖啡",tags).map {it.name}.toSet())
    }
    @Test fun weekNeedsThreeFinishedNightsAndUsesIsoYear() {
        assertEquals("周报/2020-W53.md",NightWeeks.path(LocalDate(2020,12,28)))
        val start=LocalDate(2026,11,2);val nights=(2..4).map {night(it)}
        assertNull(NightWeeks.build(start,nights.take(2),emptyList(),zones,1))
        assertNull(NightWeeks.build(start,nights+night(5).copy(state=NightState.Processing),emptyList(),zones,1))
        val report=NightWeeks.build(start,nights,emptyList(),zones,1)!!
        assertTrue(report.markdown.contains("一个发现"));assertTrue(report.markdown.contains("夜间人声 3 段"))
        assertEquals(LocalDate(2026,11,2),NightWeeks.due(LocalDate(2026,11,16),8).first())
        assertEquals(4,NightWeeks.due(LocalDate(2026,11,16),9).size)
    }
    @Test fun personalModelLearnsOnlyWhenEvidenceAndValidationPermit() {
        val examples=List(20) {PersonalExample(listOf(2.0+it*.01),true)}+List(10) {PersonalExample(listOf(-2.0-it*.01),false)}
        assertNull(PersonalNightModel.train(examples.drop(1)))
        val model=PersonalNightModel.train(examples)!!
        assertTrue(model.active);assertEquals(1f,model.recall);assertFalse(PersonalNightModel.permits(model,listOf(-3.0)))
        assertTrue(PersonalNightModel.permits(model.copy(active=false),listOf(-3.0)))
        val flat=PersonalNightModel.train(examples.map {it.copy(features=listOf(0.0))})!!;assertFalse(flat.active)
    }
    @Test fun retrainingWaitsForChargingAndTenFeedbackOrAWeek() {
        assertFalse(PersonalNightModel.due(40,30,1,0,false));assertTrue(PersonalNightModel.due(40,30,1,0,true))
        assertFalse(PersonalNightModel.due(39,30,1,0,true));assertTrue(PersonalNightModel.due(30,30,7*86_400_000L,0,true))
    }
    private fun sequence(quiet: Int)=List(20) {20 to true}+List(quiet) {0 to false}+List(2) {20 to false}+List(20) {10 to true}
    private fun points(q: Int)=sequence(q).mapIndexed {i,p->SnoreLevel(i*500L,p.first,p.second)}
    @Test fun breaksRequireSilenceBurstRecoveryAndNoMissingAudio() {
        assertEquals(1,SnoreBreaks.find(points(30)).size);assertTrue(SnoreBreaks.find(points(8)).isEmpty())
        assertTrue(SnoreBreaks.find(points(30).filterIndexed {i,_->i!=49}).isEmpty())
        assertTrue(SnoreBreaks.find(points(30).map {it.copy(snore=false)}).isEmpty())
    }
    @Test fun reminderNeedsThreeValidNightsAndThirtyDayCooldown() {
        val n=(1..3).map {night(it).copy(sleepMillis=3_600_000,snoreBreaks=5)}
        assertTrue(SnoreBreaks.shouldRemind(n,100,null,false));assertFalse(SnoreBreaks.shouldRemind(n,100,0,false))
        assertFalse(SnoreBreaks.shouldRemind(n,100,null,true));assertFalse(SnoreBreaks.shouldRemind(n.map {it.copy(confidence=NightConfidence.Rough)},100,null,false))
    }
    @Test fun exportSelectsContinuousFifteenSecondsAndDoctorIncludesDisclaimer() {
        assertEquals(160_000,NightExports.loudestStart(List(20) {0.0}+List(30) {10.0}+List(10) {1.0},8000))
        val report=NightExports.doctor(LocalDate(2026,11,1),LocalDate(2026,11,2),listOf(night(1)),zones)
        assertTrue(report.startsWith("本资料由手机录音自动统计，仅供参考，不是医疗检查结果"));assertTrue(report.contains("鼾声中断"))
    }
    @Test fun evaluatePublicAudioSimulationWithProductionDetector() {
        val file=File("../docs/任务卡/203-证据/break-windows.csv");assertTrue(file.exists())
        val real=File("../docs/任务卡/203-证据/psg-windows.csv");assertTrue(real.exists())
        val rows=(file.readLines().drop(1)+real.readLines().drop(1)).filter {it.isNotBlank()}.map {it.split(',')}.groupBy {it[0]}
        val results=rows.map { (name,windows)->
            val detector=NightDetector();val levels=mutableListOf<SnoreLevel>()
            windows.forEach {r->val at=r[1].toLong();val score=r[2].toFloat()
                r[6].split(';').forEachIndexed {i,db->levels+=SnoreLevel(at+i*500,db.toInt(),score>=NightTuning.SNORE && detector.currentSnore!=null)}
                detector.accept(at,mapOf("Snoring" to score),NightFeatures(r[3].toDouble(),r[4].toDouble(),r[5].toInt()))
            }
            val count=SnoreBreaks.find(levels).size;val seconds=windows.size*2
            "{\"case\":\"$name\",\"seconds\":$seconds,\"events\":$count,\"perHour\":${count*3600.0/seconds}}"
        }
        File("../docs/任务卡/203-证据/break-results.json").writeText("[\n"+results.joinToString(",\n")+"\n]\n")
        // 数字留作校准证据，不能用目标倒逼改门槛或把模拟当医学真值。
        assertEquals(7,results.size)
    }
}
