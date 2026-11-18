package app.resound.night

import android.app.Application
import app.resound.data.CaptureDatabase
import app.resound.data.open
import app.resound.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
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
class PersonalNight204Test {
    @Test fun resetKeepsAnswersRestoresExcludedEventsAndStartsNewSamples() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val db = CaptureDatabase.open(context,"personal205.db")
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store = NightStore(db,scope,NightSettings(),{}, {},{100L},{ZoneHistory(emptyList(),"UTC")},
            {true},{FileRef(it)},{error("unused")},{},{},MutableStateFlow(emptyList()))
        val insights = NightInsightsStore(store,{100L},{ZoneHistory(emptyList(),"UTC")},{null})
        try {
            val night = store.create(NightSession(0,0,0,3600000), LocalDate(2026,11,18))
            suspend fun snore(at: Long): Long {
                val clip = store.dao.insertClip(NightClipRow(nightId=night,start=at,end=at+1000,path=null,
                    scores=byteArrayOf(),features="{}",confirmed=false,expiresAt=0,candidate=false))
                return store.addEvent(night,NightEvent(0,NightEventKind.Snore,at,at+1000,clipId=clip))
            }
            val yes = snore(0)
            val no = snore(2000)
            val excluded = snore(4000)
            store.feedback(yes,Feedback.Yes)
            store.feedback(no,Feedback.No)
            val model = PersonalWeights(emptyList(),emptyList(),emptyList(),0.0,true,1f,.7f,1f,21,11)
            store.dao.personal(NightPersonalRow("Snore",store.json.encodeToString(model),1,32))
            store.dao.updateEvent(store.dao.event(excluded)!!.copy(vetoed=true))
            store.recompute(night)
            assertEquals(1000L,store.detail(night)!!.summary.snoreMillis)
            assertEquals(1,insights.personal.first().first { it.kind == PersonalKind.Snore }.excludedCount)
            insights.resetPersonal()
            assertTrue(store.dao.personal().first().isEmpty() && store.dao.feedback().isEmpty())
            val detail = store.detail(night)!!
            assertTrue(detail.vetoed.isEmpty())
            assertEquals(2000L,detail.summary.snoreMillis)
            assertEquals(Feedback.Yes,detail.events.first { it.id == yes }.feedback)
            assertEquals(Feedback.No,detail.events.first { it.id == no }.feedback)
            store.feedback(no,Feedback.Yes)
            assertEquals(no,store.dao.feedback().single().eventId)
            assertTrue(store.dao.feedback().single().yes)
        } finally { scope.cancel(); db.close(); context.deleteDatabase("personal205.db") }
    }

    @Test fun onlyChangedNightsAreJudgedAndFlippedAnswerRetrainsWhenCharging() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val db = CaptureDatabase.open(context,"personal204.db")
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store = NightStore(db,scope,NightSettings(),{}, {},{100L},{ZoneHistory(emptyList(),"UTC")},
            {true},{FileRef(it)},{error("unused")},{},{},MutableStateFlow(emptyList()))
        val dao=store.dao
        try {
            val id=store.create(NightSession(0,0,0,3600000),LocalDate(2026,11,17))
            val feature=store.json.encodeToString(NightFeatures(.5,.5,2))
            fun scores(yes: Boolean)=NightFiles.scores(mapOf("a" to if(yes) 1f else 0f))
            repeat(32) { i ->
                val yes=i<21
                dao.feedback(NightFeedbackRow(nightId=id,eventId=100L+i,kind="Snore",yes=yes,scores=scores(yes),features=feature,at=1))
            }
            suspend fun sound(night: Long): Long {
                val clip=dao.insertClip(NightClipRow(nightId=night,start=0,end=2000,path=null,scores=scores(false),features=feature,confirmed=false,expiresAt=0,candidate=false))
                val event=store.addEvent(night,NightEvent(0,NightEventKind.Snore,0,2000,clipId=clip))
                store.recompute(night)
                return event
            }
            val first=sound(id)
            var reads=0
            val training=PersonalNightTraining(store,{ reads++;NightFiles.scores(it) },{ it })
            training.train(10,true)
            assertTrue(dao.event(first)!!.vetoed)
            reads=0
            training.train(11,true)
            assertEquals(0,reads)
            val next=store.create(NightSession(0,0,0,3600000),LocalDate(2026,11,18))
            val second=sound(next)
            training.train(12,false)
            assertEquals(1,reads)
            assertTrue(dao.event(second)!!.vetoed)
            val row=dao.feedback().first()
            dao.feedback(row.copy(id=0,yes=!row.yes,at=13))
            training.train(14,false)
            assertEquals(10L,dao.personal().first().single().trainedAt)
            training.train(15,true)
            assertEquals(15L,dao.personal().first().single().trainedAt)
            store.update(store.settings.value.copy(personalModel=false))
            training.train(16,true)
            assertTrue(dao.vetoedNights().isEmpty())
            // 去重 SQL 即使两个处理器同时从旧缓存发起插入也只保留一条。
            repeat(2) { dao.insertBreak(id,10,20,store.json.encodeToString(NightEvent(0,NightEventKind.SnoreBreak,10,20))) }
            assertEquals(listOf(10L),dao.breakStarts(id))
        } finally { scope.cancel();db.close();context.deleteDatabase("personal204.db") }
    }
}
