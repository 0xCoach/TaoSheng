package app.resound.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.ask.*
import app.resound.backup.*
import app.resound.data.*
import app.resound.digest.*
import app.resound.search.SearchIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Todo046Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 10, 24)
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private val dao get() = db.memory()
    private fun event(id: Long = 1) = MemoryEventEntity(id, at, at + 1000, "独白", "合成事件", "合成摘要", "[]", "[]", null, null, null, false, createdAt = at, model = "test", starred = true, starredAt = at)
    private fun item(id: Long = 1, category: String = "待办", status: String = "Accepted") = MemoryItemEntity(id, 1, category, null, "合成条目$id", null, null, null, null, status, at)
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().addCallback(SearchIndex.callback).build()
        root = File(context.cacheDir, "todo046-${UUID.randomUUID()}").apply { mkdirs() }
        context.getSharedPreferences("digest", 0).edit().clear().commit()
        dao.insertEvent(event()); Unit
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }
    private suspend fun line(id: Long = 1, eventId: Long? = 1): TranscriptLineEntity {
        db.capture().segment(SegmentEntity(id, at, at + 10000, "mic", "synthetic.ogg", 100, "Done"))
        val line = TranscriptLineEntity(id, id, at + 5000, at + 6000, "合成证据：已经办好了", null, "我", eventId)
        db.transcribe().insertLines(listOf(line)); return line
    }
    private suspend fun snapshot() = db.push().snapshot(Long.MIN_VALUE, false)

    @Test fun migration15AndOldVolumeAndSettingsRestore() = runBlocking {
        val name = File(root, "v15.db").path
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/15.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(name, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                e.optJSONArray("indices")?.let { a -> repeat(a.length()) { old.execSQL(sql(a.getJSONObject(it).getString("createSql"))) } }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES(1,0,1,'独白','旧事件','摘要','[]','[]',0,0,'test')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(1,1,'待办','旧条目','Accepted',0)")
            old.version = 15
        }
        val migrated = CaptureDatabase.open(context, name)
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertFalse(migrated.memory().itemIncludingDeleted(1)!!.deleted)
            assertEquals("[]", migrated.memory().itemIncludingDeleted(1)!!.dismissedDoneLinesJson)
        } finally { migrated.close() }
        val rows = File(root, "old.jsonl").apply { writeText("""{"table":"memory_events","values":{"id":2,"startAt":0,"endAt":1,"scene":"独白","title":"旧事件","summary":"摘要","pointsJson":"[]","peopleJson":"[]","important":0,"createdAt":0,"model":"test"}}
{"table":"memory_items","values":{"id":2,"eventId":2,"category":"待办","content":"旧条目","status":"Accepted","createdAt":0}}
""") }
        DayVolumeRows.insert(db.openHelper.writableDatabase, rows, false)
        assertNull(dao.itemIncludingDeleted(2)!!.editedAt)
        assertEquals(0L, dao.itemIncludingDeleted(2)!!.reminderVersion)
        val settings = AndroidAppointmentSettings(context)
        assertEquals(AppointmentReminders(), settings.state.value)
        settings.set(AppointmentReminders(false, 1200, null))
        BackupSettings.capture(context, root) { _, _ -> "" }
        context.getSharedPreferences("digest", 0).edit().clear().commit()
        BackupSettings.apply(context, root) { _, _, _ -> }
        assertEquals(AppointmentReminders(false, 1200, null), AndroidAppointmentSettings(context).state.value)
    }

    @Test fun deletedIsAbsentFromAllConsumers() = runBlocking {
        dao.insertItems(listOf(item().copy(dueAt = at + 10000), item(2, status = "Pending")))
        dao.setImportant(1, true)
        dao.deleteItem(1); dao.deleteItem(2)
        assertTrue(dao.ongoing().first().isEmpty()); assertTrue(dao.pendingItems().first().isEmpty())
        assertTrue(dao.itemsBetween(at, at + 10000).first().isEmpty()); assertTrue(dao.dayItems(at, at + 10000).first().isEmpty())
        assertTrue(dao.importantItems().first().isEmpty()); assertTrue(dao.starredItems().first().isEmpty()); assertTrue(dao.activeItemTimes(at, at + 10000).first().isEmpty())
        assertTrue(db.review().input(at - 1, at + 10000).items.isEmpty())
        assertTrue(db.review().staleCandidates(at + 4 * 86400000L, at + 4 * 86400000L).isEmpty())
        assertTrue(db.skills().items(listOf(1)).isEmpty()); assertTrue(snapshot().items.isEmpty())
        assertTrue(SubscriptionReminders.candidates(snapshot(), PushSubscription.PhoneNotice, JSONObject()).isEmpty())
        val spec = JSONObject(File("src/main/assets/prompts/ask-sql.json").readText())
        assertEquals(0, AskTools(db, spec).execute("list_items", JSONObject(), mutableListOf()).getJSONArray("results").length())
        assertTrue(runCatching { AskContextReader(db).read(AskSource("item", 1, at, "", "")) }.isFailure)
    }

    @Test fun redigestAndMergeNeverReviveDeletedOrOverwriteEdits() = runBlocking {
        val source = line()
        dao.insertItems(listOf(item().copy(lineId = source.id), item(2, "约定").copy(lineId = source.id), item(3, "灵感"), item(4, "灵感").copy(mergedInto = 3)))
        dao.deleteItem(1); dao.updateItem(2, "用户修改的约定", at + 100000, true, "小林", at + 1)
        dao.saveDigest(listOf(event(0) to listOf(source.id)), { ids -> listOf(item(0).copy(eventId = ids[0], lineId = source.id), item(0, "约定").copy(eventId = ids[0], lineId = source.id)) }, listOf(source.id), at + 10)
        assertEquals(listOf(2L, 3L), dao.itemsByCategory(listOf("待办", "约定", "灵感")).first().map { it.id }.sorted())
        assertEquals("用户修改的约定", dao.itemIncludingDeleted(2)!!.content)
        dao.deleteItem(3); dao.unmerge(4); dao.repairMergedItems(); dao.mergeInto(3, 4)
        assertEquals(3L, dao.itemIncludingDeleted(4)!!.mergedInto)
        assertTrue(dao.mergedItems(3).first().isEmpty())
        assertFalse(dao.itemIncludingDeleted(4)!!.deleted) // 删除只改目标条目。
    }

    @Test fun editNormalizesDateAndRevisesReminderIdentity() = runBlocking {
        dao.insertItems(listOf(item()))
        val noon = at + 12 * 3600000
        dao.updateItem(1, "改内容", noon, false, " ", at + 1)
        val day = dao.itemIncludingDeleted(1)!!
        assertEquals("2026-10-24", day.dueText); assertEquals(at, day.dueAt); assertNull(day.who)
        dao.updateItem(1, "改内容", noon, true, " 小陈 ", at + 2)
        val timed = dao.itemIncludingDeleted(1)!!
        assertEquals("2026-10-24 12:00", timed.dueText); assertEquals("小陈", timed.who); assertEquals(at + 2, timed.editedAt)
        assertEquals(day.reminderVersion + 1, timed.reminderVersion)
        dao.updateItem(1, "只改文字", noon, true, "小陈", at + 3)
        assertEquals(timed.reminderVersion, dao.itemIncludingDeleted(1)!!.reminderVersion)
        dao.updateItem(1, "取消时间", null, false, null)
        assertNull(dao.itemIncludingDeleted(1)!!.dueText)
        // “问问”确认改期也走同一编辑入口；重复设置同一时间不能再触发一轮提醒。
        val conversation = db.ask().insertConversation(AskConversationEntity(createdAt = at, title = "合成改期"))
        val changes = AskChanges(db, { at + 4 })
        suspend fun applyDue() {
            val proposal = changes.propose(JSONObject("""{"operation":"item_due","id":1,"due":"2026-10-25 10:00"}"""), listOf(AskSource("item", 1, at, "", "")))
            val message = db.ask().insertMessage(AskMessageEntity(conversationId = conversation, role = "Assistant", text = "", sourcesJson = "[]", at = at, error = null, actionJson = proposal.encode()))
            changes.resolve(message, true)
        }
        applyDue(); val changed = dao.itemIncludingDeleted(1)!!
        assertEquals("2026-10-25 10:00", changed.dueText); assertEquals(at + 4, changed.editedAt)
        applyDue(); assertEquals(changed.reminderVersion, dao.itemIncludingDeleted(1)!!.reminderVersion)
    }

    @Test fun ongoingAndUnlimitedCompletedRange() = runBlocking {
        dao.insertItems(listOf(item(1), item(2, "约定").copy(createdAt = at + 2), item(3, "备忘", "Pending")))
        assertEquals(listOf(2L, 1L), dao.ongoing().first().map { it.id })
        dao.setItemStatus(2, "Accepted", at); dao.setItemStatus(3, "Accepted", at)
        assertEquals(listOf(3L, 2L, 1L), dao.ongoing().first().map { it.id })
        dao.insertItems((10L..610L).map { item(it, status = "Done").copy(doneAt = at + it) } + item(611, status = "Done").copy(doneAt = at - 1))
        assertEquals(601, dao.doneItems(at).first().size)
        assertEquals(610L, dao.doneItems(at).first().first().id)
    }

    @Test fun batchIsAtomicAndDoneHintsValidateIdsAndDismissal() = runBlocking {
        val source = line()
        dao.insertItems(listOf(item(1, status = "Pending"), item(2, status = "Pending"), item(3, status = "Done")))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_batch BEFORE UPDATE OF status ON memory_items WHEN new.id=2 BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { dao.setItemsStatus(listOf(1, 2), "Accepted", at + 1) }.isFailure)
        assertEquals("Pending", dao.itemIncludingDeleted(1)!!.status)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_batch")
        dao.setItemsStatus(listOf(1, 2), "Accepted", at + 1)
        val reply = """{"events":[],"done_hints":[{"item_id":1,"line_id":1},{"item_id":3,"line_id":1},{"item_id":999,"line_id":1},{"item_id":2,"line_id":9}]}"""
        val parsed = DigestPrompt.doneHints(reply, listOf(source), setOf(1, 2, 3))
        assertEquals(listOf(1L to source.id, 3L to source.id), parsed)
        suspend fun save() = dao.saveDigest(emptyList(), { emptyList() }, listOf(source.id), at + 20, parsed, setOf(1, 2, 3))
        save(); assertEquals(source.id, dao.itemIncludingDeleted(1)!!.doneHintLineId); assertNull(dao.itemIncludingDeleted(3)!!.doneHintLineId)
        assertEquals("Accepted", dao.itemIncludingDeleted(1)!!.status)
        dao.dismissDoneHint(1); save(); assertNull(dao.itemIncludingDeleted(1)!!.doneHintLineId)
        assertEquals(source.id, JSONArray(dao.itemIncludingDeleted(1)!!.dismissedDoneLinesJson).getLong(0))
    }

    @Test fun digestContextLimitsAndIgnoredWindow() = runBlocking {
        dao.insertItems((1L..55L).map { item(it, status = "Pending") } + (100L..135L).map { item(it, status = "Ignored").copy(statusChangedAt = at) } + item(200, status = "Ignored").copy(statusChangedAt = at - 61L * 86400000))
        dao.deleteItem(135)
        assertEquals(50, dao.unfinishedForDigest().size)
        val ignored = dao.ignoredForDigest(at - 60L * 86400000)
        assertEquals(30, ignored.size); assertFalse(ignored.any { it.id in setOf(135L, 200L) })
        val input = DigestPrompt.withTodoContext("[1] 原话", dao.unfinishedForDigest(), ignored)
        assertTrue(input.contains("unfinished_items")); assertFalse(input.contains("合成条目135"))
    }

    @Test fun eveningGroupsDateOnlyAndLeadIsSingleAndSentOnce() {
        val next = at + 86400000L
        val a = item().copy(dueAt = next + 10 * 3600000, dueText = "2026-10-25 10:00")
        val b = item(2, "约定").copy(dueAt = next, dueText = "2026-10-25")
        val data = PushSnapshot(listOf(event()), listOf(a, b, item(3, status = "Pending").copy(dueAt = next)))
        fun candidates(sent: JSONObject = JSONObject(), rows: PushSnapshot = data) = SubscriptionReminders.candidates(rows, PushSubscription.PhoneNotice, sent, AppointmentReminders(), zone, at)
        val list = candidates(); val evening = list.single { it.key.startsWith("evening:") }
        assertEquals(at + 21 * 3600000, evening.at); assertEquals(2, evening.keys.size)
        assertTrue(evening.text.contains("10:00 合成条目1")); assertTrue(evening.text.contains("合成条目2"))
        assertEquals(next + 9 * 3600000, list.single { it.key.startsWith("lead:") }.at)
        val sent = JSONObject(); list.flatMap { it.keys }.forEach { sent.put(it, at) }
        assertTrue(candidates(sent).isEmpty())
        val changed = data.copy(items = listOf(a.copy(dueAt = next + 11 * 3600000, reminderVersion = 1), b))
        assertEquals(1, candidates(sent, changed).single { it.key.startsWith("evening:") }.keys.size)
        assertTrue(candidates(rows = data.copy(items = listOf(a.copy(deleted = true), b.copy(status = "Done")))).isEmpty())
    }

    @Test fun localPhoneSendsOnceAndReschedulesEditedTime() = runBlocking {
        var clock = at + 9 * 3600000
        dao.insertItems(listOf(item().copy(dueAt = at + 10 * 3600000, dueText = "2026-10-24 10:00")))
        val sent = mutableListOf<String>(); val plans = mutableListOf<Long?>(); val settings = AppointmentReminders(false, leadMinutes = 60)
        val runtime = PhoneReminders(prefs(), { snapshot() }, { settings }, object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { sent += body }
        }, { next, _ -> plans += next }, { clock })
        assertFalse(runtime.refresh(true)); assertEquals(1, sent.size)
        runtime.refresh(true); assertEquals(1, sent.size)
        dao.updateItem(1, "合成条目1", at + 12 * 3600000, true, null, clock)
        runtime.refresh(); assertEquals(at + 11 * 3600000, plans.last())
        clock = at + 11 * 3600000; runtime.refresh(true); assertEquals(2, sent.size)
        dao.deleteItem(1); runtime.refresh(); assertNull(plans.last())
        clock = at + 13 * 3600000; runtime.refresh(true); assertEquals(2, sent.size)
    }

    @Test fun strongReminderGoesToStrongChannelOthersStayNormal() = runBlocking {
        val clock = at + 10 * 3600000
        dao.insertItems(listOf(item(1).copy(dueAt = clock, dueText = "2026-10-24 10:00", strongReminder = true),
            item(2).copy(dueAt = clock, dueText = "2026-10-24 10:00")))
        val normal = mutableListOf<String>(); val strong = mutableListOf<String>()
        fun notifier(into: MutableList<String>) = object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { into += body }
        }
        PhoneReminders(prefs(), { snapshot() }, { AppointmentReminders(false, leadMinutes = null) }, notifier(normal), { _, _ -> }, { clock },
            strongNotifier = notifier(strong)).refresh(true)
        // 手机上看的文字不带 I1 这样的编号
        assertEquals(listOf("待办到期 · ${app.resound.OutputTime().moment(clock)} 合成条目1"), strong); assertEquals(listOf("待办到期 · ${app.resound.OutputTime().moment(clock)} 合成条目2"), normal)
    }

    @Test fun strongReminderPostsWithButtonsAfterEdit() = runBlocking {
        var clock = at + 9 * 3600000
        dao.insertItems(listOf(item(1).copy(eventId = null, dueAt = at + 10 * 3600000, dueText = "2026-10-24 10:00", strongReminder = true)))
        val runtime = PhoneReminders(prefs(), { snapshot() }, { AppointmentReminders(false, leadMinutes = null) }, LocalNotifier(context), { _, _ -> }, { clock },
            strongNotifier = StrongReminderNotifier(context), onRefresh = { StrongReminders.sweep(context, it) })
        clock = at + 10 * 3600000; assertFalse(runtime.refresh(true))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val posted = org.robolectric.Shadows.shadowOf(manager).allNotifications
        assertEquals(1, posted.size); assertEquals(3, posted.single().actions.size)
        // 改到 11 点后再到点，要再弹一次
        dao.updateItem(1, "合成条目1", at + 11 * 3600000, true, null, clock)
        clock = at + 11 * 3600000; assertFalse(runtime.refresh(true))
        assertEquals(2, org.robolectric.Shadows.shadowOf(manager).allNotifications.size + 1)
    }

    @Test fun deleteRegeneratesFolderAndWebhookDelta() = runBlocking {
        dao.insertItems(listOf(item()))
        val sub = PushSubscription.ForAgents.copy(backfillDays = null)
        val before = snapshot().select(sub, null, date, zone)
        val old = JSONObject(); RecordDelta.records(before, sub).forEach { (key, value) -> old.put(key, sha256(canonical(value))) }
        val generator = MemoryFolder(date, zone)
        assertTrue(generator.generate(before, sub).values.any { it.contains("合成条目1") })
        dao.deleteItem(1)
        val after = snapshot().select(sub, null, date, zone)
        assertFalse(generator.generate(after, sub).values.any { it.contains("合成条目1") })
        val changes = RecordDelta.changes(RecordDelta.records(after, sub), old).getJSONArray("changes")
        assertEquals(1, changes.length()); assertEquals("delete", changes.getJSONObject(0).getString("op"))
    }

    @Test fun subscriptionSendsIndividualLeadsAndRetrySkipsSuccessfulOne() = runBlocking {
        val clock = at + 9 * 3600000
        dao.insertItems(listOf(item(1), item(2)).map { it.copy(dueAt = at + 10 * 3600000, dueText = "2026-10-24 10:00") })
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            bodies += JSONObject(buffer.readUtf8()).getString("body")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (bodies.size == 2) 503 else 200).message("synthetic").body("{}".toResponseBody()).build()
        }.build()
        val sub = PushSubscription.PhoneNotice.copy(summaries = false)
        val channel = PushChannel(1, PushKind.Webhook, "模拟订阅", true, Subscriptions.topics(sub), PushFormat.Json, "", subscription = sub)
        val values = mapOf("url" to "https://example.test/hook", "signSecret" to "synthetic")
        val runtime = SubscriptionRuntime(prefs(), File(root, "push"), { channel }, { channel to values },
            PushTransport(client), { _, _, _ -> }, { _, _, _ -> }, { clock }, { zone })
        runtime.source = { _, _ -> snapshot() }
        runtime.reminderSettings = { AppointmentReminders(eveningBefore = false) }
        runtime.configure(channel, true)
        assertTrue(runtime.scheduled(1)) // 第一条成功、第二条失败，交给 Worker 重试。
        assertEquals(2, bodies.size)
        assertFalse(runtime.scheduled(1)); assertEquals(3, bodies.size)
        assertEquals(bodies[1], bodies[2]); assertNotEquals(bodies[0], bodies[2])
        runtime.scheduled(1); assertEquals(3, bodies.size)
        dao.deleteItem(1); dao.setItemStatus(2, "Done", clock)
        runtime.reminderSchedule(channel, snapshot())
        runtime.scheduled(1); assertEquals(3, bodies.size)
    }

    @Test fun runnerSuppliesContextAndPersistsHintWithoutCompleting() = runBlocking {
        val source = line(eventId = null)
        dao.insertItems(listOf(item(), item(2, status = "Ignored").copy(statusChangedAt = at)))
        val client = LlmClient(); val settings = AndroidDigestSettings(context, client)
        var input = ""
        val runner = DigestRunner(context, dao, client, settings, { "template" }, { true }, now = { at + 10000 }, readKey = { "synthetic-key" }, request = { _, _, _, user ->
            input = user
            LlmReply("""{"events":[],"items":[],"done_hints":[{"item_id":1,"line_id":1}]}""", 1, 1)
        })
        runner.forceOnce = true; assertFalse(runner.run())
        assertTrue(input.contains("unfinished_items")); assertTrue(input.contains("ignored_items"))
        assertEquals(source.id, dao.itemIncludingDeleted(1)!!.doneHintLineId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomMemoryRepository(dao, runner, {}, scope, AndroidAppointmentSettings(context))
            assertEquals(source.text, repository.ongoing().first().single().doneHint!!.text)
            assertEquals(ItemStatus.Accepted, repository.ongoing().first().single().status)
        } finally { scope.cancel() }
    }
}
