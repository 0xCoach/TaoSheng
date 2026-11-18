package app.resound.push

import android.app.Application
import app.resound.data.*
import app.resound.platform.ZoneHistory
import app.resound.privacy.RoomPrivacyActions
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayThread204Test {
    @get:Rule val temp = TemporaryFolder()
    private val today = LocalDate.of(2026,11,18)
    private val zones = ZoneHistory(emptyList(),"UTC")
    private val start = today.minusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val channel = PushChannel(1, PushKind.MarkdownFolder, "测试", true, emptySet(), PushFormat.Markdown, "",
        subscription=PushSubscription(records=setOf(RecordKind.Timeline), footprint=false, screenTime=false))

    @Test fun lateTranscriptAppearsAtDawnAndDeletionRewritesPublishedDay() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val db = CaptureDatabase.open(context, "day204.db")
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder()
                .setExecutor(SynchronousExecutor()).build())
            val manager = WorkManager.getInstance(context)
            val observer = launch { AndroidPushChannels(context).observeRecordPush(db) }
            try {
                val state = PushSyncState(temp.newFolder().resolve("state.json"))
                state.json.put("dayThreadInitialized",true)
                suspend fun publish(): String {
                    val files = mutableMapOf<String,String>()
                    val snapshot = db.push().snapshot(Long.MIN_VALUE,true)
                    val days = prepareDayThreads(files,state,channel,snapshot,today,4,zones) { date,raw,s,h,f ->
                        loadDayThread(db,date,raw,s,h,f)
                    }
                    days.forEach { acknowledgeDay(state,it,start) }
                    return files.getValue(MemoryFolder.dailyPath(today.minusDays(2)))
                }
                assertFalse("补录事件" in publish())
                delay(1200) // 启动校对完成后，陆续提交录音和原话。
                for (id in 1L..300L) {
                    val at = start + id * 120000
                    db.capture().segment(SegmentEntity(id,at,at+5000,"phone","synthetic-$id",10,"Done"))
                    db.transcribe().insertLines(listOf(TranscriptLineEntity(id,id,at,at+5000,"补录原话",null)))
                }
                db.memory().insertEvent(MemoryEventEntity(1,start+120000,start+125000,"独白","补录事件","摘要","[]","[]",null,null,null,false,createdAt=start,model="test"))
                delay(1200)
                assertTrue(manager.getWorkInfosByTag(DayThreadPushWork::class.java.name).get().isEmpty())
                val final = publish()
                assertTrue("补录事件" in final)
                assertEquals("79c719316a5105c221db80b877e24933338c2d87158f674cc8053e659435c49e",sha256(final))
                RoomPrivacyActions(db, onErased = { DayThreadPushScheduler.afterDeletion(context) }).forgetEvent(1)
                val work = manager.getWorkInfosForUniqueWork("push-day-thread-privacy").get().single()
                assertEquals(0L,work.initialDelayMillis)
                assertFalse("补录事件" in publish())
            } finally {
                observer.cancelAndJoin()
                manager.cancelAllWork().result.get()
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        } finally { db.close(); context.deleteDatabase("day204.db") }
    }
}
