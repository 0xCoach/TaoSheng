package app.resound.skills

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import app.resound.AppGraph
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.digest.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SkillGeneration047Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.parse("2026-10-24")
    private val start = day.atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli()
    private val key get() = "evening_note" to day
    private lateinit var db: CaptureDatabase
    private lateinit var files: SkillFiles
    private lateinit var root: File
    private lateinit var work: WorkManager
    private val scopes = mutableListOf<CoroutineScope>()
    private val calls = AtomicInteger()
    private val pushes = AtomicInteger()
    private var response: suspend () -> LlmReply = { eveningReply() }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    private fun eveningReply() = LlmReply("""{"cards":[{"title":"收工","summary":"完成项目推进","values":{"done":["推进项目"]}}]}""", 10, 2)
    private fun repo(scope: CoroutineScope = scope(), generation: SkillGeneration? = null) = LocalSkillRepository(
        files, db.skills(), db.memory(), scope,
        { LlmPreset("test", "测试", "https://example.invalid", "test") to "test-only" },
        { _, _, _, _ -> calls.incrementAndGet(); response() }, { emptyList() },
        object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { pushes.incrementAndGet() }
        }, { _, _ -> }, { start + 22 * 3_600_000L }, generation)

    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        work = WorkManager.getInstance(context)
        root = File(context.cacheDir, "skills047").apply { deleteRecursively(); mkdirs() }
        files = SkillFiles(root) {
            File("src/main/assets/skills").listFiles()!!.map { dir ->
                SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()
            }
        }
        files.skills.value.filter { it.origin != SkillOrigin.Core }.forEach { s -> files.change(s.id) {
            it.put("push", false); it.put("enabled", s.id == "evening_note")
        } }
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        db.capture().segment(SegmentEntity(1, start, start + 1000, "mic", "/unused", 0, "Done"))
        db.openHelper.writableDatabase.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerName,digestedAt,speakerManual) VALUES(1,1,?,?, '推进项目','我',?,0)",
            arrayOf(start + 36_000_000L, start + 36_002_000L, start + 37_000_000L))
    }
    @After fun close() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        work.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close(); root.deleteRecursively(); Unit
    }
    private fun row(entity: String, at: Long = start + 36_000_000L, end: Long? = null, skill: String = "meeting") = SkillCardRow(
        skillId = skill, skillVersion = 1, skillName = "会议速记", icon = "meeting", date = day.toEpochDay(), entity = entity,
        at = at, endAt = end, title = "项目会议", summary = "推进项目", fieldsJson = "[]", valuesJson = "{}", evidenceJson = "[]", generatedAt = start, manual = false)
    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<SkillGenerationWork>(context)
        .setInputData(workDataOf(SkillGeneration.SKILL to key.first, SkillGeneration.DATE to day.toString()))
        .setRunAttemptCount(attempt).build()
    private suspend fun queued(): WorkInfo = withTimeout(5000) {
        work.getWorkInfosForUniqueWorkFlow(SkillGeneration.name(key.first, day)).first { it.isNotEmpty() }.single()
    }
    private fun notifications() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }

    @Test fun deletedDayManualRestoresAndAutomaticUpdatesWhileDeletedAutomaticHasOwnReason() = runBlocking {
        val r = repo()
        val original = (r.runNow(key.first, day) as SkillRunResult.Cards).cards.single()
        db.skills().delete(original.id, start)
        r.runDue()
        assertEquals("这天的卡你删过，自动运行跳过了", db.skills().runsOf(key.first).last().result)
        assertTrue(r.cards(day).first().isEmpty())
        val restored = (r.runNow(key.first, day) as SkillRunResult.Cards).cards.single()
        assertEquals(original.id, restored.id); assertTrue(restored.manual)
        val stored = db.skills().dayRows(key.first, day.toEpochDay()).single()
        assertNull(stored.deletedAt)
        val updated = db.skills().merge(stored.copy(title = "自动更新", manual = false), 1)!!.first
        assertEquals(restored.id, updated.id); assertEquals("自动更新", updated.title)
    }

    @Test fun deletedMeetingOverlapAndAliasesAreRestoredOnlyByManual() = runBlocking {
        val first = db.skills().merge(row("first", end = start + 37_000_000), 8)!!.first
        db.skills().merge(row("alias", start + 36_900_000, start + 38_000_000), 8)
        db.skills().delete(first.id, start)
        val incoming = row("new-name", start + 37_500_000, start + 39_000_000)
        assertEquals(SkillMergeResult.Deleted, db.skills().mergeWithReason(incoming, 8))
        val restored = db.skills().merge(incoming.copy(manual = true), 8, manual = true)!!.first
        assertNull(restored.deletedAt); assertEquals(first.id, restored.id)
        assertTrue(restored.entities().containsAll(listOf("first", "alias", "new-name")))
        assertNotNull(db.skills().merge(row("alias", start + 50_000_000, start + 51_000_000), 8))
        assertEquals(1, db.skills().dayRows("meeting", day.toEpochDay()).size)
    }

    @Test fun fullDayReportsActualCountInsteadOfDeletedReason() = runBlocking {
        repeat(8) { db.skills().merge(row("existing_$it", start + it * 1_000_000L), 8) }
        response = { LlmReply("""{"cards":[{"entity":"third","at":"20:00","title":"会议","values":{"topic":"项目","conclusion":"先做原型"}}]}""", 1, 1) }
        val result = repo().runNow("meeting", day)
        assertEquals(SkillRunResult.Nothing("今天已经有 8 张，达到每天上限"), result)
        assertEquals("今天已经有 8 张，达到每天上限", db.skills().runsOf("meeting").last().result)
    }

    @Test fun generateIsImmediateDeduplicatedClearsOldResultAndPublishesCompletion() = runBlocking {
        val appScope = scope()
        val generation = SkillGeneration(work, db.skills(), appScope)
        val r = repo(appScope, generation); AppGraph.skills = r
        r.generate(key.first, day); r.generate(key.first, day)
        assertTrue(key in r.generating.value); assertFalse(key in r.generated.value)
        val info = queued()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        val request = SkillGeneration.request(key.first, day)
        assertTrue(request.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        withTimeout(5000) { r.generating.first { key !in it } }
        assertTrue(r.generated.value[key] is SkillRunResult.Cards); assertEquals(1, calls.get())
        assertEquals(1, notifications().size); assertEquals(0, pushes.get())
        r.generate(key.first, day)
        assertTrue(key in r.generating.value); assertFalse(key in r.generated.value)
        withTimeout(5000) { work.getWorkInfosForUniqueWorkFlow(SkillGeneration.name(key.first, day)).first { it.isNotEmpty() && it.singleOrNull()?.id != info.id } }
        Unit
    }

    @Test fun restoresPendingWorkAfterRepositoryRestartAndKeepsExistingRequest() = runBlocking {
        val request = SkillGeneration.request(key.first, day)
        work.enqueueUniqueWork(SkillGeneration.name(key.first, day), ExistingWorkPolicy.KEEP, request).await()
        val appScope = scope()
        val generation = SkillGeneration(work, db.skills(), appScope)
        val r = repo(appScope, generation); AppGraph.skills = r
        withTimeout(5000) { r.generating.first { key in it } }
        r.generate(key.first, day)
        assertEquals(request.id, queued().id)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        withTimeout(5000) { r.generating.first { key !in it } }
        assertTrue(r.generated.value[key] is SkillRunResult.Cards); assertEquals(1, calls.get())
    }

    @Test fun noticesHaveThreeOutcomesAndMultipleCardDayLinkEvenWithPushOff() = runBlocking {
        AppGraph.skills = repo()
        val success = worker().doWork() as ListenableWorker.Result.Success
        val cardId = success.outputData.getLongArray("cards")!!.single()
        val single = notifications().single().notification
        assertEquals(LocalNotifier.SKILL_GENERATION_CHANNEL, single.channelId)
        // 单张卡：标题带卡片标题，正文先摘要再列字段
        single.extras.getCharSequence(Notification.EXTRA_TITLE).toString().let { assertTrue(it, it.startsWith("收工打卡") && it.endsWith("已生成")) }
        single.extras.getCharSequence(Notification.EXTRA_TEXT).toString().let { assertTrue(it, it.startsWith("完成项目推进")) }
        val intent = shadowOf(single.contentIntent).savedIntent
        assertEquals("card", intent.getStringExtra("open")); assertEquals(cardId.toString(), intent.data!!.getQueryParameter("id"))
        assertEquals("技能生成", context.getSystemService(NotificationManager::class.java).getNotificationChannel(single.channelId).name)
        response = { LlmReply("""{"cards":[],"nothing_reason":"今天没有会议"}""", 1, 1) }
        worker().doWork()
        val nothing = notifications().first { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("没有出卡") }.notification
        assertEquals("收工打卡没有出卡", nothing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("今天没有会议", nothing.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        response = { throw LlmException(LlmError.Auth) }; worker().doWork()
        val failure = notifications().first { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("生成失败") }.notification
        assertEquals("收工打卡生成失败", failure.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(LlmError.Auth.human, failure.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(day.toString(), shadowOf(failure.contentIntent).savedIntent.getStringExtra("date"))
        val card = db.skills().dayRows(key.first, day.toEpochDay()).single().card()
        val multi = SkillGenerationNotice.from("收工打卡", day, SkillRunResult.Cards(listOf(card, card.copy(id = 99))))
        assertTrue(multi.body.startsWith("出了 2 张")); assertEquals("resound://day?open=day&date=$day", multi.link)
        assertEquals(0, pushes.get())
    }

    @Test fun networkAndServerRetryAtMostTwiceWithoutEarlyNotice() = runBlocking {
        AppGraph.skills = repo()
        response = { throw LlmException(if (calls.get() == 1) LlmError.Network else LlmError.Server) }
        assertTrue(worker(0).doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker(1).doWork() is ListenableWorker.Result.Retry)
        assertTrue(notifications().isEmpty())
        val final = worker(2).doWork() as ListenableWorker.Result.Failure
        assertEquals("failed", final.outputData.getString("result"))
        assertEquals("网络或服务器异常，已重试 2 次，请稍后再试", final.outputData.getString("reason"))
        assertEquals(3, calls.get()); assertEquals(1, notifications().size)
        val last = db.skills().runsOf(key.first).last()
        assertFalse(last.retryable); assertEquals(final.outputData.getString("reason"), last.result)
    }

    @Test fun stoppedWorkerPropagatesCancellationAndRetryCanStillProduceResult() = runBlocking {
        AppGraph.skills = repo()
        val entered = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); awaitCancellation() }
        val stopped = launch { worker().doWork() }
        withTimeout(5000) { entered.await() }; stopped.cancelAndJoin()
        assertEquals("系统暂停，等待后台重试", db.skills().runsOf(key.first).last().result)
        assertTrue(notifications().isEmpty())
        response = { eveningReply() }
        val result = worker(1).doWork() as ListenableWorker.Result.Success
        assertEquals("cards", result.outputData.getString("result"))
        assertEquals(1, db.skills().cards(day.toEpochDay()).first().size)
        assertEquals(1, notifications().size); assertEquals(0, pushes.get())
    }
}
