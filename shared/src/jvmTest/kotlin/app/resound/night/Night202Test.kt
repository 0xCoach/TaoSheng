package app.resound.night
import app.resound.capture.QuietRule
import app.resound.context.*
import app.resound.platform.ZoneHistory
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class Night202Test {
    private val config=NightSettings(enabled=true)
    private fun input(at: Long,screenOn: Long?=null,pickup: Boolean=false)=NightInput(at,23*60,if(screenOn==null)0 else null,screenOn,0,0,pickup,true,35,false,false)
    @Test fun enterConfirmCancelAndAllBatteryTiers() {
        assertEquals(NightTransition.Enter,NightRules.transition(config,null,input(600_000)))
        val pending=NightSession(0,600_000,lastAt=600_000)
        assertEquals(NightTransition.Confirm,NightRules.transition(config,pending,input(1_800_000)))
        assertEquals(NightTransition.Cancel,NightRules.transition(config,pending,input(800_000,screenOn=650_000)))
        assertEquals(NightTransition.Cancel,NightRules.transition(config,pending,input(700_000,pickup=true)))
        assertEquals(listOf(NightMode.Full,NightMode.Deferred,NightMode.TimesOnly,NightMode.Off),listOf(35,25,14,9).map { NightRules.mode(config,it,false,false) })
        assertEquals(NightMode.Deferred,NightRules.mode(config,90,true,true))
    }
    @Test fun normalNightAwakeIntervalsAreMergedAndEditedTimesWin() {
        val session=NightSession(0,600_000,1_800_000,8*3_600_000,signals=listOf(NightSignal(3_600_000,3_720_000,"screen"),NightSignal(3_780_000,3_840_000,"screen"),NightSignal(8*3_600_000-600_000,8*3_600_000,"screen")))
        val result=SleepEstimate.calculate(session,emptyList(),8*3_600_000)
        assertEquals(0,result.times.onset);assertEquals(1,result.count);assertEquals(240_000,result.awake.sumOf { it.millis })
        val edit=result.times.copy(onset=600_000,editedOnset=true)
        assertEquals(600_000,SleepEstimate.calculate(session,emptyList(),8*3_600_000,edited=edit).times.onset)
        assertEquals(600_000,NightRules.latency(listOf(600_000,900_000,300_000)))
    }
    @Test fun speechNearUseIsAwakePlaybackNeverDreamTalkAndGapsReduceConfidence() {
        assertTrue(NightRules.sleepTalk(500_000,510_000,emptyList()))
        assertFalse(NightRules.sleepTalk(500_000,510_000,listOf(NightSignal(600_000,610_000,"screen"))))
        assertFalse(NightRules.sleepTalk(500_000,510_000,listOf(NightSignal(0,1_000_000,"playback"))))
        val session=NightSession(0,0,0,4_000_000,signals=listOf(NightSignal(0,2_400_000,"gap")))
        assertEquals(NightConfidence.Rough,NightPresentation.confidence(session,listOf(-60)).first)
        assertEquals("pickup",NightMotion.classify(31.0,0.0,0));assertEquals("turn",NightMotion.classify(10.0,.1,0))
    }
    @Test fun quietConflictCrossesMidnightAndSleepUsesElapsedTimeAcrossDst() {
        assertNotNull(NightRules.conflict(config,listOf(QuietRule("s","睡眠",23*60,7*60))))
        assertNull(NightRules.conflict(config,listOf(QuietRule("s","下午",13*60,15*60))))
        val t=NightTimes(0,0,7*3_600_000,true)
        assertEquals(7*3_600_000,NightRules.asleepMillis(t,emptyList()))
        val zones=ZoneHistory(emptyList(),"America/New_York")
        assertEquals(25*3_600_000,zones.zonesOn(LocalDate(2026,11,1)).sumOf { it.until-it.from })
    }
    @Test fun rhythmRejectsFlatAndIsolatedNoiseAndAcceptsPulses() {
        assertEquals(0.0,NightRhythm.correlation(List(60) {.01}),1e-9)
        val pulse=List(60) { .01+if(it%8<2).1 else 0.0 }
        assertTrue(NightRhythm.accepts(3,NightFeatures(NightRhythm.correlation(pulse),NightRhythm.modulation(pulse),20)))
        assertFalse(NightRhythm.accepts(2,NightFeatures(1.0,1.0,20)))
        val detector=NightDetector()
        for(i in 0..14)detector.accept(i*2_000L,mapOf("Snoring" to .9f),NightFeatures(0.0,0.0,10))
        assertTrue(detector.finish().isEmpty())
    }
    @Test fun boundedSideChannelDropsOldWindowsWithoutWaitingForInference()=runTest {
        var lost=0;val frames=NightFrames {lost++};val pcm=ShortArray(32_000) {1000}
        repeat(20) {frames.accept(pcm,false,it*2_000L)}
        assertEquals(5,lost);assertEquals(10_000,frames.windows.receive().start);frames.close()
    }
    @Test fun deferredCandidatesIncludeBothContextWindowsAndCapAtSixtySeconds() {
        fun w(at: Long,db: Int)=NightWindow(at,ShortArray(32_000),false,.1,-50,NightFeatures(.8,.5,db))
        val c=NightCandidates();assertTrue(c.accept(w(0,0)).isEmpty());assertTrue(c.accept(w(2000,8)).isEmpty())
        assertEquals(listOf(0L,2000L,4000L),c.accept(w(4000,0)).single().map {it.start})
        val long=NightCandidates();val batches=(0..60).flatMap {long.accept(w(it*2000L,8))}
        assertTrue(batches.all {it.size<=30})
    }
    @Test fun legacyDayThreadBytesStayUnchangedAndNightPushContainsNoAudio() {
        val day=LocalDate(2026,11,17);val zones=ZoneHistory(emptyList(),"UTC")
        val thread=DayThreads.build(ThreadInput(day,0,1,0),zones)
        assertFalse(Json.encodeToString(thread).contains("night"))
        val s=NightSummary(1,day,NightState.Ready,NightTimes(0,0,1000,true),1000,0,0,1000,0,0,NightConfidence.Good,emptyList(),null,emptyList())
        val detail=NightDetail(s,listOf(NightEvent(1,NightEventKind.Snore,0,1000,20,clipId=123,audioAvailable=true)),null,emptyList())
        assertNull(NightThreads.build(detail,config.copy(includeInPush=false),zones))
        val result=NightThreads.build(detail,config.copy(sharedRoom=true),zones)!!
        assertTrue(result.markdown.contains("卧室里的疑似鼾声"));assertFalse(Json.encodeToString(result).contains("clipId"));assertTrue(result.onset!!.endsWith("+00:00"))
    }
}
