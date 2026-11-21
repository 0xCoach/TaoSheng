package app.resound.night

import android.app.Application
import app.resound.data.*
import app.resound.platform.*
import app.resound.push.nightWeekPayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Night203AndroidTest {
    @Test fun dreamUsesExistingSegmentsAndDeletionRewritesThread()=runBlocking(Dispatchers.IO) {
        val context=RuntimeEnvironment.getApplication();val db=CaptureDatabase.open(context,"night203-dream.db")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var now=1_000_000L;var refreshes=0
        val zones=ZoneHistory(emptyList(),"UTC")
        val store=NightStore(db,scope,NightSettings(),{}, {},{now},{zones},{true},{FileRef("/private/$it")},{error("unused")},{},{refreshes++},MutableStateFlow(emptyList()))
        val insights=NightInsightsStore(store,{now},{zones},{null})
        try {
            val id=store.create(NightSession(0,0,0,now),LocalDate(2026,11,17));store.recompute(id)
            val dream=insights.startDream(id)
            db.capture().segment(SegmentEntity(101,now+1000,now+2000,"phone","dream.pcm",10,"Done"))
            db.capture().segment(SegmentEntity(102,now+181_000,now+182_000,"phone","later.pcm",10,"Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId=101,startAt=now+1000,endAt=now+2000,text="梦见海边",speakerTag=null)))
            now+=200_000;insights.refreshDreams(id)
            val actual=store.detail(id)!!.dreams.single();assertEquals(listOf(101L),actual.segmentIds);assertEquals("梦见海边",actual.text);assertFalse(actual.transcribing)
            insights.deleteDream(dream.id);assertTrue(store.detail(id)!!.dreams.isEmpty());assertTrue(refreshes>0)
            insights.initialize();repeat(20) {assertTrue(insights.createTag("自建$it").isSuccess)};assertTrue(insights.createTag("超额").isFailure)
        } finally {scope.cancel();db.close();context.deleteDatabase("night203-dream.db")}
    }
    @Test fun deletingSourceRemovesDerivedWeekAndPersonalWeightsAtomically()=runBlocking(Dispatchers.IO) {
        val context=RuntimeEnvironment.getApplication();val db=CaptureDatabase.open(context,"night203-privacy.db")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val zones=ZoneHistory(emptyList(),"UTC")
        val store=NightStore(db,scope,NightSettings(),{}, {},{100L},{zones},{true},{FileRef(it)},{error("unused")},{},{},MutableStateFlow(emptyList()))
        try {
            val ids=(2..4).map { day ->
                val id=store.create(NightSession(0,0,0,28_800_000),LocalDate(2026,11,day))
                val row=db.night().get(id)!!
                val summary=store.summary(row).copy(state=NightState.Ready,times=NightTimes(0,600_000,28_800_000,true),sleepMillis=28_200_000,confidence=NightConfidence.Good)
                db.night().update(row.copy(state="Ready",payload=store.json.encodeToString(summary)));id
            }
            val report=NightWeeks.build(LocalDate(2026,11,2),db.night().all().map(store::summary),emptyList(),zones,100)!!
            db.night().week(NightWeekRow(report.weekStart.toString(),store.json.encodeToString(report),store.json.encodeToString(NightWeekState("unique","ready",report.markdown))))
            db.night().personal(NightPersonalRow("Snore","{}",1,30))
            db.night().feedback(NightFeedbackRow(nightId=ids.first(),eventId=999,kind="Snore",yes=true,scores=byteArrayOf(),features="{}",at=1))
            store.deleteNight(ids.first())
            assertTrue(db.night().weeks().first().isEmpty());assertTrue(db.night().personal().first().isEmpty())
        } finally {scope.cancel();db.close();context.deleteDatabase("night203-privacy.db")}
    }
    @Test fun weekFolderPathAndWebhookContainOnlyText() {
        val week=WeeklyReport(LocalDate(2026,12,28),3,"规则文字",null,"可以试试侧睡",false,0)
        assertEquals("周报/2026-W53.md",NightWeeks.path(week.weekStart))
        val payload=nightWeekPayload(week);assertEquals("night_week",payload.getString("type"));assertFalse(payload.has("audio"));assertEquals("规则文字",payload.getString("markdown"))
    }
}
