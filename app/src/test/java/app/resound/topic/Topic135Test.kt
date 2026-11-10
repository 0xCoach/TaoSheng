package app.resound.topic

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.backup.BackupArchive
import app.resound.backup.DayVolumeRows
import app.resound.data.*
import app.resound.digest.*
import app.resound.importer.ImportKind
import app.resound.skills.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Topic135Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: RoomTopicRepository
    private var time = 10_000_000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = RoomTopicRepository(db, scope, { time }, { ZoneId.of("Asia/Shanghai") }, quiet = { _, _ -> true }, recordingEnabled = { false })
    }
    @After fun close() { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close() }
    private suspend fun line(id: Long, at: Long, text: String = "我会提交报告", status: String = "Done", digested: Long? = null, event: Long? = null) {
        db.capture().segment(SegmentEntity(id = id, startAt = at, endAt = at + 1000, sourceId = "mic", path = "/topic-$id.ogg",
            sizeBytes = 1, status = status, voiceprintProcessedAt = if (status == "Done") time else null, separateSpeakers = true))
        if (status == "Done") db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, at, at + 1000, text, "0", "我", eventId = event, digestedAt = digested)))
    }
    private fun event(start: Long, end: Long, title: String = "平常事件", star: Boolean = false) = MemoryEventEntity(startAt = start, endAt = end,
        scene = "工作", title = title, summary = "摘要", pointsJson = "[]", peopleJson = "[]", place = null, emotion = null,
        intensity = null, important = false, createdAt = time, model = "test", starred = star)
    private fun todo(event: Long, content: String, line: Long, status: String = "Pending", edited: Long? = null) = MemoryItemEntity(
        eventId = event, category = "待办", kind = "我要做", content = content, dueText = null, dueAt = null, who = "我", lineId = line,
        status = status, createdAt = time, editedAt = edited)
    private fun template(kind: ImportKind): Pair<Skill, String> {
        val dir = File("src/main/assets/skills/import_${kind.name.lowercase()}")
        return SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin).skill to File(dir, "prompt.md").readText()
    }
    private fun digestReply(user: String): LlmReply {
        val n = Regex("(?m)^\\[([0-9]+)\\]").findAll(user).map { it.groupValues[1].toInt() }.maxOrNull() ?: 1
        return LlmReply(JSONObject().put("events", JSONArray().put(JSONObject().put("from", 1).put("to", n)
            .put("title", "模型专题标题").put("summary", "已确定下一步").put("scene", "工作").put("points", JSONArray().put("明确行动"))))
            .put("items", JSONArray().put(JSONObject().put("category", "待办").put("content", "提交报告").put("line", 1))).toString(), 10, 10)
    }
    private fun cardReply() = LlmReply(JSONObject().put("cards", JSONArray().put(JSONObject().put("title", "纪要")
        .put("summary", "摘要").put("values", JSONObject().put("topic", "主题").put("points", JSONArray().put("★ 重点：提交报告"))
            .put("conclusion", "约定提交").put("actions", JSONArray().put("提交报告")))))
        .put("todos", JSONArray()).toString(), 10, 10)
    private fun generator(request: suspend (String, String) -> LlmReply = { system, user ->
        if (system.contains("必须恰好输出一张")) cardReply() else digestReply(user)
    }) = TopicGenerator({ "输出 events、items JSON，事件含 from、to、title、summary。" }, ::template, request,
        hotwords = { listOf("涛声") }, now = { time })
    private fun engine(generator: TopicGenerator = generator(), ready: (Long) -> Unit = {}) = TopicEngine(db, repo, generator, now = { time }, ready = ready)
    private suspend fun ended(kind: TopicKind = TopicKind.Normal, name: String? = null): Long {
        val id = repo.start(kind, name, 15).topicId!!
        time += 1000; repo.end(); return id
    }

    @Test fun migration32AndBothBackupFormsKeepTopicsAndOldBackupCompatibility() = runBlocking {
        val name = "topic135-v32.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/32.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(text: String) = text.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { a -> repeat(a.length()) { j -> old.execSQL(sql(a.getJSONObject(j).getString("createSql"))) } }
            }
            schema.getJSONArray("setupQueries").let { a -> repeat(a.length()) { i -> old.execSQL(a.getString(i)) } }
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','old.ogg',10,'Done')")
            old.version = 32
        }
        val work = File(context.cacheDir, "topic135-backup").apply { deleteRecursively(); mkdirs() }
        val legacy = File(work, "legacy.db"); context.getDatabasePath(name).copyTo(legacy)
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals("old.ogg", migrated.transcribe().segment(1).path)
            val id = migrated.topics().insert(TopicRow(kind = "Training", name = "培训", status = "Ended", createdAt = time, repeatRule = 5, quietOverride = true, revision = 3))
            migrated.topics().span(TopicSpanRow(topicId = id, startAt = 1000, endAt = 2000))
            migrated.topics().mark(TopicMarkRow(topicId = id, at = 1500))
            val snapshot = File(work, "snapshot.db"); BackupArchive.snapshot(migrated, snapshot)
            val whole = CaptureDatabase.open(context, snapshot.path)
            try { assertEquals(migrated.topics().get(id), whole.topics().get(id)) } finally { whole.close() }
            val (version, rows) = DayVolumeRows.write(snapshot, File(work, "rows"))
            val target = File(work, "restore.db")
            DayVolumeRows.rebuild(context, target, version, rows.getValue("state"), rows.filterKeys { it != "state" }.values.toList())
            val restored = CaptureDatabase.open(context, target.path)
            try { assertEquals(migrated.topics().get(id), restored.topics().get(id)) } finally { restored.close() }
            val oldBackup = CaptureDatabase.open(context, legacy.path)
            try { assertEquals(CaptureDatabase.VERSION, oldBackup.openHelper.writableDatabase.version); assertTrue(oldBackup.topics().all().isEmpty()) }
            finally { oldBackup.close() }
            // 老分卷不含三张新表，恢复到最新 schema 仍合法。
            val oldState = File(work, "old-state.jsonl").apply { writeText("") }
            val oldRows = File(work, "old-day.jsonl").apply { writeText("{\"table\":\"segments\",\"values\":{\"id\":1,\"startAt\":1000,\"endAt\":2000,\"sourceId\":\"mic\",\"path\":\"old.ogg\",\"sizeBytes\":10,\"status\":\"Done\"}}\n") }
            val oldTarget = File(work, "old-restored.db"); DayVolumeRows.rebuild(context, oldTarget, 32, oldState, listOf(oldRows))
            val oldRestored = CaptureDatabase.open(context, oldTarget.path)
            try { assertTrue(oldRestored.topics().all().isEmpty()); assertEquals("old.ogg", oldRestored.transcribe().segment(1).path) } finally { oldRestored.close() }
        } finally { migrated.close(); context.deleteDatabase(name); work.deleteRecursively() }
    }

    @Test fun startBackfillsTwoMinutesAndClampsToPreviousTopicAndDigestedSpeech() = runBlocking {
        val first = repo.start(TopicKind.Meeting, "涛声 项目，甲", 15)
        assertTrue(first.quietOverride); assertTrue(first.recordingDisabled)
        assertTrue(TopicPolicy.overridesQuiet(db.topics().get(first.topicId!!)!!.topic(), time))
        assertEquals(time - 120_000, db.topics().get(first.topicId!!)!!.spans.single().startAt)
        time += 30_000; repo.end(); val ended = time
        time += 20_000
        val next = repo.start(TopicKind.Normal, null).topicId!!
        assertEquals(ended, db.topics().get(next)!!.spans.single().startAt)
        repo.cancel(next)
        line(1, time - 10_000, digested = time)
        val last = repo.start(TopicKind.Normal, null).topicId!!
        assertEquals(time - 9000, db.topics().get(last)!!.spans.single().startAt)
        assertEquals(listOf("涛声", "项目"), TopicPolicy.words("涛声 项目，甲"))
    }

    @Test fun pauseReturnsSpeechToOrdinaryDigestAndOldInFlightResultCannotClaimTopic() = runBlocking {
        val id = repo.start(TopicKind.Normal, null).topicId!!
        line(1, time - 1000); time += 1000; repo.pause()
        assertFalse(TopicPolicy.overridesQuiet(db.topics().get(id)!!.topic(), time))
        line(2, time + 1000); time += 3000
        assertEquals(listOf(2L), db.memory().undigestedLines().map { it.id })
        repo.resume(); line(3, time + 1000)
        assertEquals(listOf(2L), db.memory().undigestedLines().map { it.id })
        val rows = db.memory().refreshLines(listOf(1, 2, 3))
        db.memory().saveDigest(listOf(event(rows.first().startAt, rows.last().endAt) to listOf(1, 2, 3)), { emptyList() }, listOf(1, 2, 3), time)
        assertTrue(db.memory().refreshLines(listOf(1, 2, 3)).all { it.eventId == null })
        time += 3000; repo.end()
        assertEquals(listOf(1L, 3L), db.topics().lines(id).map { it.id })
    }

    @Test fun pausedDeadlineDoesNotMoveAndUnboundedTopicEndsAtFourHours() = runBlocking {
        val id = repo.start(TopicKind.Normal, null, 15).topicId!!
        val end = db.topics().get(id)!!.row.plannedEndAt!!
        time += 60_000; repo.pause(); time = end; repo.tick()
        assertEquals("Ended", db.topics().get(id)!!.row.status)
        assertEquals(end, db.topics().get(id)!!.row.plannedEndAt)
        time += 1000
        val next = repo.start(TopicKind.Normal, null).topicId!!
        val automaticEnd = db.topics().get(next)!!.spans.single().startAt + TopicPolicy.MAX_DURATION
        time = automaticEnd
        assertFalse(TopicPolicy.activeAt(db.topics().get(next)!!.topic(), time))
        assertFalse(TopicPolicy.overridesQuiet(db.topics().get(next)!!.topic(), time))
        repo.tick()
        assertEquals("Ended", db.topics().get(next)!!.row.status)
        assertEquals(automaticEnd, db.topics().get(next)!!.spans.single().endAt)
    }

    @Test fun overlappingLiveRetroactiveAndFutureWeeklyReservationsAreRejected() = runBlocking {
        val future = time + 10 * 86_400_000L
        val day = java.time.Instant.ofEpochMilli(future).atZone(ZoneId.of("Asia/Shanghai")).dayOfWeek
        assertTrue(repo.schedule(TopicKind.Meeting, null, future, future + 60_000, setOf(day)).isSuccess)
        assertTrue(repo.schedule(TopicKind.Meeting, null, future + 7 * 86_400_000L, future + 7 * 86_400_000L + 60_000).isFailure)
        val saturday = ZonedDateTime.of(2027, 1, 2, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        assertTrue(repo.schedule(TopicKind.Meeting, null, saturday, saturday + 60 * 60 * 60_000L,
            setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)).isFailure)
        val id = repo.start(TopicKind.Normal, null, 15).topicId!!
        assertNull(repo.start(TopicKind.Normal, null).topicId)
        assertTrue(repo.createFromRange(time - 10_000, time, TopicKind.Normal, null).isFailure)
        val deadline = db.topics().get(id)!!.deadline()
        assertTrue(repo.schedule(TopicKind.Meeting, null, deadline, deadline + 60_000).isSuccess)
        assertTrue(runCatching { repo.extend(30) }.exceptionOrNull() is TopicException)
    }

    @Test fun trainingUsesHistoricalSpansAndTopicSegmentsLeadTranscriptionCandidates() = runBlocking {
        val id = repo.start(TopicKind.Training, null, 15).topicId!!
        val prefs = context.getSharedPreferences("topic135-speed", 0).apply { edit().clear().commit() }
        val speed = AndroidAsrSpeedSettings(prefs, scope) { time }.apply { topicTraining = db.topics()::trainingAt }
        assertFalse(speed.effectiveSeparateSpeakers(time)); assertTrue(speed.effectiveSeparateSpeakers(time - 120_001))
        line(1, time - 500_000, status = "Pending"); line(2, time - 1000, status = "Pending")
        time += 2000; repo.end()
        assertFalse(speed.effectiveSeparateSpeakers(time - 2000)); assertTrue(speed.effectiveSeparateSpeakers(time))
        assertEquals(listOf(2L, 1L), db.transcribe().candidates(time).map { it.id })
        assertEquals(1, db.topics().pendingAudio())
        val audio = File.createTempFile("topic135", ".ogg", context.cacheDir).apply { writeText("audio") }
        db.transcribe().update(db.transcribe().segment(2).copy(path = audio.path))
        var separate: Boolean? = null
        val provider = object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                separate = audio.separateSpeakers; beforeAudio(); return AsrResult(listOf(AsrSentence(0, 500, "培训内容", "0")), 500, null)
            }
        }
        try {
            TranscribeRunner(db.transcribe(), provider, { "test" }, { _, _ -> }, topicTraining = db.topics()::trainingAt).run()
            assertEquals(false, separate); assertNull(db.topics().lines(id).single().speakerTag)
            assertEquals(false, db.transcribe().segment(2).separateSpeakers)
            speed.startClassMode(120); assertFalse(speed.effectiveSeparateSpeakers(time + 1))
        } finally { audio.delete() }
    }

    @Test fun weeklyScheduleStartsOnceCreatesNextAndRemindsWithoutTurningRecordingOn() = runBlocking {
        time = ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val start = time + 60_000; val end = start + 15 * 60_000
        val id = repo.schedule(TopicKind.Meeting, null, start, end, setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)).getOrThrow()
        val reminders = mutableListOf<TopicReminder>(); repo.onReminder = { _, r -> reminders += r }
        time = start; repo.tick(); repo.tick()
        assertEquals("Running", db.topics().get(id)!!.row.status)
        assertEquals(start, db.topics().get(id)!!.spans.single().startAt)
        val next = repo.observeScheduled().first().single()
        assertEquals(start + 2 * 86_400_000, next.plannedStartAt)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), next.repeat)
        assertEquals(listOf(TopicReminder.RecordingOff), reminders)
        repo.cancelSchedule(next.id); assertTrue(repo.observeScheduled().first().isEmpty())
        time = end; repo.tick(); assertEquals("Ended", db.topics().get(id)!!.row.status)
    }

    @Test fun quietOnlyRemindsOncePerSilenceAndEndingReminderCanExtendThirtyMinutes() = runBlocking {
        val id = repo.start(TopicKind.Meeting, null, 60).topicId!!
        val reminders = mutableListOf<TopicReminder>(); repo.onReminder = { _, r -> reminders += r }
        time += 15 * 60_000; repo.tick(); repo.tick()
        assertEquals(1, reminders.count { it == TopicReminder.Quiet })
        val oldAction = TopicActionReceiver.intent(context, "end", id)
        val laterAction = TopicActionReceiver.intent(context, "end", id + 1)
        assertNotEquals(oldAction, laterAction)
        assertEquals(id, org.robolectric.Shadows.shadowOf(oldAction).savedIntent.getLongExtra("topicId", 0))
        line(1, time); time += 16 * 60_000; repo.tick()
        assertEquals(2, reminders.count { it == TopicReminder.Quiet })
        repo.pause(); time = db.topics().get(id)!!.deadline() - 5 * 60_000; repo.tick(); repo.tick()
        assertEquals(1, reminders.count { it == TopicReminder.Ending })
        val end = db.topics().get(id)!!.deadline(); repo.extend(30)
        assertEquals(end + 30 * 60_000, db.topics().get(id)!!.deadline())
        assertTrue(TopicNotifications.line(db.topics().get(id)!!.topic(), time).contains("已暂停"))
    }

    @Test fun multipleBatchesBecomeOneEventAndMarksReachReusedSummaryTemplate() = runBlocking {
        val start = time; val id = repo.start(TopicKind.Meeting, "项目例会", 60).topicId!!
        // 602 行减去暂停中的一句，跨过 DigestRunner 的 600 行批上限。
        repeat(602) { i -> line(i + 1L, start + i * 1000L) }
        time = start + 200_000; repo.pause(); repo.mark(); time += 1000; repo.resume()
        time = start + 602_000; repo.end()
        val calls = mutableListOf<Pair<String, String>>()
        val generator = generator { system, user -> calls += system to user; if (system.contains("必须恰好输出一张")) cardReply() else digestReply(user) }
        var recording = true
        val engine = TopicEngine(db, repo, generator, voiceprintReady = { true }, recordingPending = { _, _ -> recording }, now = { time })
        assertTrue(engine.run()); assertTrue(calls.isEmpty())
        recording = false
        val firstSegment = db.transcribe().segment(1)
        db.transcribe().update(firstSegment.copy(voiceprintProcessedAt = null))
        assertTrue(engine.run()); assertTrue(calls.isEmpty())
        db.transcribe().update(firstSegment)
        engine.run()
        val topic = db.topics().get(id)!!; assertEquals("Done", topic.row.status)
        val event = db.memory().event(topic.row.eventId!!)!!
        assertEquals("项目例会", event.title); assertEquals(topic.spans.minOf { it.startAt }, event.startAt)
        assertEquals(time, event.endAt)
        assertEquals(1, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM memory_events").use { it.moveToFirst(); it.getInt(0) })
        assertTrue(calls.count { !it.first.contains("必须恰好输出一张") } >= 2)
        assertTrue(calls.filter { it.first.contains("必须恰好输出一张") }.all { it.second.contains("用户标记的重点时刻") && it.second.contains("★ 重点") && it.second.contains("项目例会") })
        assertEquals("import_meeting", db.skills().card(topic.row.cardId!!).first()!!.skillId)
        assertEquals(601, db.memory().eventLines(event.id).first().size)
    }

    @Test fun scriptContainsTwoFullVersionsAndLocalWordCountAndReadingTime() = runBlocking {
        line(1, time - 1000)
        val id = ended(TopicKind.Script, "口播草稿")
        val output = "好".repeat(441)
        engine(generator { system, user -> if (system == TopicGenerator.SCRIPT_PROMPT)
            LlmReply(JSONObject().put("organized", output).put("verbatim", "嗯，" + output).toString(), 1, 1) else digestReply(user) }).run()
        val topic = db.topics().get(id)!!
        val card = db.skills().card(topic.row.cardId!!).first()!!.card()
        assertEquals(output, card.values.getValue("organized").text)
        assertEquals("嗯，$output", card.values.getValue("verbatim").text)
        assertEquals("441 字 · 约念 3 分钟", card.values.getValue("organized_size").text)
        assertEquals("442 字 · 约念 3 分钟", card.values.getValue("verbatim_size").text)
    }

    @Test fun retrospectiveRangeSnapsOutwardReplacesEventsPreservesChangedTodosAndStar() = runBlocking {
        val e1 = db.memory().insertEvent(event(time - 9000, time - 5000, star = true))
        val e2 = db.memory().insertEvent(event(time - 6000, time - 1000))
        line(1, time - 8000, event = e1, digested = time); line(2, time - 3000, event = e2, digested = time)
        db.memory().insertItems(listOf(todo(e1, "已完成的报告", 1, "Done"), todo(e1, "改过的报告", 1, edited = time), todo(e2, "机器待办", 2)))
        val preview = repo.previewFromEvents(listOf(e1))
        assertEquals(time - 9000, preview.startAt); assertEquals(time - 1000, preview.endAt)
        assertEquals(2, preview.events.size); assertEquals(2, preview.changedTodoCount); assertEquals(1, preview.starredEventCount)
        val retained = db.topics().eventItems(listOf(e1, e2)).filter(TopicPolicy::changed)
        val id = repo.createFromRange(time - 7000, time - 6500, TopicKind.Normal, null).getOrThrow()
        assertNull(db.memory().event(e1)); assertNull(db.memory().event(e2))
        assertTrue(db.topics().lines(id).all { it.digestedAt == null }); assertTrue(db.memory().undigestedLines().isEmpty())
        engine().run()
        val topic = db.topics().get(id)!!; val newEvent = topic.row.eventId!!
        assertTrue(db.memory().event(newEvent)!!.starred)
        assertEquals(retained.map { it.copy(eventId = newEvent) }, db.topics().items(retained.map { it.id }))
        assertFalse(db.topics().eventItems(listOf(newEvent)).any { it.content == "机器待办" })
        assertNull(topic.row.cardId)
    }

    @Test fun cutSplitsSpanReleasesOnlyCutSpeechAndCancelDeletesGeneratedArtifacts() = runBlocking {
        val start = time; val id = repo.start(TopicKind.Meeting, null, 15).topicId!!
        line(1, start); line(2, start + 2000); line(3, start + 4000); time += 6000; repo.end(); engine().run()
        val old = db.topics().get(id)!!.row
        repo.cut(id, start + 1500, start + 3500)
        assertEquals(2, db.topics().get(id)!!.spans.size)
        assertEquals(listOf(2L), db.memory().undigestedLines().map { it.id })
        assertNull(db.memory().event(old.eventId!!)); assertNull(db.skills().card(old.cardId!!).first())
        engine().run(); val new = db.topics().get(id)!!.row
        assertEquals(listOf(1L, 3L), db.memory().eventLines(new.eventId!!).first().map { it.id })
        repo.cancel(id)
        assertEquals("Cancelled", db.topics().get(id)!!.row.status)
        assertEquals(listOf(1L, 2L, 3L), db.memory().undigestedLines().map { it.id })
        assertNull(db.memory().event(new.eventId)); assertNull(db.skills().card(new.cardId!!).first())
    }

    @Test fun failedGenerationHasFixedMessageAndRevisionRejectsStaleNetworkResult() = runBlocking {
        line(1, time - 1000); val id = ended()
        engine(generator { _, _ -> throw IllegalStateException("不能写进错误的原话和名字") }).run()
        assertEquals("Failed", db.topics().get(id)!!.row.status)
        assertEquals(TopicPolicy.GENERATION_ERROR, db.topics().get(id)!!.row.error)
        repo.regenerate(id)
        engine(generator { _, user -> repo.cancel(id); digestReply(user) }).run()
        assertEquals("Cancelled", db.topics().get(id)!!.row.status)
        assertNull(db.topics().get(id)!!.row.eventId)
        val id2 = ended(); engine().run()
        assertEquals("Done", db.topics().get(id2)!!.row.status)
    }

    @Test fun rejectedContentIsSplitAfterOneConfigurationProbeAndRemainingSpeechIsKept() = runBlocking {
        val start = time; val id = repo.start(TopicKind.Normal, null, 15).topicId!!
        repeat(16) { i -> line(i + 1L, start + i * 1000, if (i == 0) "拒收标记" else "正常原话") }
        time += 20_000; repo.end(); var probes = 0
        engine(generator { system, user -> when {
            system == DigestRunner.PROBE_SYSTEM -> { probes++; LlmReply("{\"ok\":true}", 1, 1) }
            user.contains("拒收标记") -> throw LlmException(LlmError.Server, 400)
            else -> digestReply(user)
        } }).run()
        assertEquals(1, probes); val topic = db.topics().get(id)!!; assertEquals("Done", topic.row.status)
        assertTrue(db.memory().event(topic.row.eventId!!)!!.summary.contains("部分原话被模型拒收"))
        assertEquals(16, db.memory().eventLines(topic.row.eventId).first().size)
    }

    @Test fun truncatedLargeResponseSplitsWithoutCreatingSeveralEvents() = runBlocking {
        val start = time; val id = repo.start(TopicKind.Normal, null, 15).topicId!!
        repeat(40) { i -> line(i + 1L, start + i * 1000) }; time += 42_000; repo.end()
        var calls = 0
        engine(generator { _, user -> calls++; if (calls == 1) LlmReply("{\"events\":[", 1, 1) else digestReply(user) }).run()
        assertEquals(3, calls); assertEquals("Done", db.topics().get(id)!!.row.status)
        assertEquals(40, db.memory().eventLines(db.topics().get(id)!!.row.eventId!!).first().size)
    }
}
