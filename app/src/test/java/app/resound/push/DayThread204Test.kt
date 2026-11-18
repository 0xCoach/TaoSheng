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
import app.resound.AppGraph
import app.resound.platform.HistoricalZones
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

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

    @Test fun weeklyReportCanShareTheDawnFolderBatch() {
        val path = app.resound.night.NightWeeks.path(kotlinx.datetime.LocalDate(2026,11,9))
        assertTrue(FolderSync.owned(path))
        assertFalse(FolderSync.owned("周报/私人笔记.md"))
        assertFalse(FolderSync.owned("周报/../私人笔记.md"))
    }

    @Test fun committedDeletionRunsQueuedWorkerAndReplacesPreviousPayload() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = CaptureDatabase.open(context,"day204-privacy.db")
        val originalZone = TimeZone.getDefault()
        val originalHistory = HistoricalZones.history
        val offset = ZoneOffset.ofHours(12 - ZonedDateTime.now(ZoneOffset.UTC).hour)
        TimeZone.setDefault(TimeZone.getTimeZone(offset))
        HistoricalZones.history = { ZoneHistory(emptyList(),offset.id) }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        try {
            val payloads = java.util.concurrent.ConcurrentHashMap<String,String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                payloads[JSONObject(body).getString("date")] = body
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body("{}".toResponseBody()).build()
            }.build()
            val secrets = object : PushSecrets {
                override fun read(ref: String) = error("测试不使用凭据")
                override fun write(ref: String,value: String) = error("测试不使用凭据")
                override fun remove(ref: String) = Unit
            }
            val prefs = context.getSharedPreferences("privacy204",0)
            val channels = AndroidPushChannels(prefs,prefs,secrets,PushTransport(client),{},stateDirectory=temp.newFolder())
            AppGraph.push = channels
            channels.saveWithSubscription(null,PushKind.Webhook,"测试",mapOf("url" to "https://example.test/hook"),
                channel.subscription,PushFormat.Json,false).getOrThrow()
            channels.subscriptions.source = { _,lines -> db.push().snapshot(Long.MIN_VALUE,lines) }
            channels.subscriptions.dayThreadSource = { d,r,s,h,f -> loadDayThread(db,d,r,s,h,f) }
            val date = LocalDate.now().minusDays(2)
            val at = date.atStartOfDay(offset).toInstant().toEpochMilli()
            db.memory().insertEvent(MemoryEventEntity(1,at,at+1000,"独白","待清除","摘要","[]","[]",null,null,null,false,createdAt=at,model="test"))
            suspend fun deliver() {
                val task = manager.getWorkInfosForUniqueWork("push-day-thread-privacy").get().single { !it.state.isFinished }
                assertEquals(0L,task.initialDelayMillis)
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(task.id)
                withTimeout(10_000) {
                    while (!manager.getWorkInfoById(task.id).get()!!.state.isFinished) {
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
                assertEquals(WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(task.id).get()!!.state)
            }
            DayThreadPushScheduler.afterDeletion(context)
            deliver()
            assertTrue("待清除" in payloads.getValue(date.toString()))
            RoomPrivacyActions(db,onErased={ DayThreadPushScheduler.afterDeletion(context) }).forgetEvent(1)
            deliver()
            assertFalse("待清除" in payloads.getValue(date.toString()))
        } finally {
            manager.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
            TimeZone.setDefault(originalZone)
            HistoricalZones.history = originalHistory
            db.close()
            context.deleteDatabase("day204-privacy.db")
        }
    }

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
                suspend fun publish(dateOfRun: LocalDate = today): String {
                    val files = mutableMapOf<String,String>()
                    val snapshot = db.push().snapshot(Long.MIN_VALUE,true)
                    val days = prepareDayThreads(files,state,channel,snapshot,dateOfRun,4,zones) { date,raw,s,h,f ->
                        loadDayThread(db,date,raw,s,h,f)
                    }
                    days.forEach { acknowledgeDay(state,it,start) }
                    return files.getValue(MemoryFolder.dailyPath(today.minusDays(2)))
                }
                assertFalse("补录事件" in publish(today.minusDays(1)))
                delay(1200) // 启动校对完成后，陆续提交录音和原话。
                for (id in 1L..300L) {
                    val at = start + id * 120000
                    db.capture().segment(SegmentEntity(id,at,at+5000,"phone","synthetic-$id",10,"Done"))
                    db.transcribe().insertLines(listOf(TranscriptLineEntity(id,id,at,at+5000,"补录原话",null)))
                }
                db.memory().insertEvent(MemoryEventEntity(1,start+120000,start+125000,"独白","补录事件","摘要","[]","[]",null,null,null,false,createdAt=start,model="test"))
                db.memory().assignLines(listOf(1),1)
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
