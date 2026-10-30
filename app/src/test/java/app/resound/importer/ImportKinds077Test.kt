package app.resound.importer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.resound.backup.BackupArchive
import app.resound.backup.DayVolumeRows
import app.resound.data.*
import app.resound.digest.LlmReply
import app.resound.privacy.RoomPrivacyActions
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ImportKinds077Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(ImportRecordSchema.callback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context.getSharedPreferences("imports", 0).edit().clear().commit()
    }
    @After fun close() { scope.cancel(); db.close() }
    private fun template(kind: ImportKind): Pair<Skill, String> {
        val id = "import_${kind.name.lowercase()}"
        return SkillJson.parse(File("src/main/assets/skills/$id/skill.json").readText(), SkillOrigin.Builtin).skill to
            File("src/main/assets/skills/$id/prompt.md").readText()
    }
    private fun reply(action: String = "提交报告", line: String = "L1") = LlmReply(JSONObject()
        .put("cards", JSONArray().put(JSONObject().put("title", "测试纪要").put("entity", "file").put("at", "09:00")
            .put("summary", "测试摘要").put("values", JSONObject().put("topic", "测试主题").put("conclusion", "已确定")
                .put("actions", JSONArray().put(action)).put("practice", JSONArray().put(action)).put("mine", JSONArray().put(action)))))
        .put("todos", JSONArray().put(JSONObject().put("line", line).put("content", action))).toString(), 100, 50)
    private fun summary(request: suspend (String, String) -> LlmReply = { _, _ -> reply() }) = ImportSummary(::template, request)
    private suspend fun record(kind: ImportKind = ImportKind.Meeting, complete: Boolean = true): ImportRecordRow {
        val row = ImportRecordRow(pendingId = "file-${db.imports().ids().size}", fileName = "测试录音", kind = kind.name,
            startAt = 1000, endAt = 6000, byChannel = false, ingestComplete = complete)
        return row.copy(id = db.imports().insert(row))
    }
    private suspend fun segment(record: ImportRecordRow, id: Long, status: String = "Done", path: String = "/no-audio-$id.ogg") {
        db.capture().segment(SegmentEntity(id = id, startAt = id * 1000, endAt = id * 1000 + 2000,
            sourceId = "import", path = path, sizeBytes = 1, status = status, importRecordId = record.id,
            voiceprintProcessedAt = if (status == "Done") 9000 else null,
            separateSpeakers = ImportKindPolicy.separate(ImportKind.valueOf(record.kind), false),
            speakerCount = ImportKindPolicy.speakers(ImportKind.valueOf(record.kind), false)))
        if (status == "Done") db.transcribe().insertLines(listOf(TranscriptLineEntity(id = id, segmentId = id,
            startAt = id * 1000, endAt = id * 1000 + 1500, text = "我会提交报告", speakerTag = "0", speakerName = "我")))
    }

    @Test fun suggestionsPreferCallCluesThenLastThenMeeting() {
        assertEquals(KindReason.CallFolder, ImportKindPolicy.suggest("录音", "primary:MIUI/sound_recorder/call_rec/a.m4a", false, ImportKind.Training).reason)
        for (name in listOf("通话录音.m4a", "13800138000_20261030.m4a", "010-88888888.m4a"))
            assertEquals(ImportKind.Call, ImportKindPolicy.suggest(name, "", false, null).kind)
        assertEquals(KindReason.DualChannel, ImportKindPolicy.suggest("录音", "", true, ImportKind.Normal).reason)
        assertEquals(KindSuggestion(ImportKind.Training, KindReason.LastUsed), ImportKindPolicy.suggest("20261030103000.m4a", "", false, ImportKind.Training))
        assertEquals(KindSuggestion(ImportKind.Meeting, KindReason.Default), ImportKindPolicy.suggest("录音", "", false, null))
    }

    @Test fun channelDecoderKeepsSidesAcrossOddBufferBoundariesAndIgnoresDuplicateStereo() {
        val samples = ShortArray(64_000) { i -> if (i % 2 == 0) if (i % 8 < 4) 3000 else -3000 else if (i % 10 < 5) 2000 else -2000 }
        val left = StreamingResampler(16000, 2, 0); val right = StreamingResampler(16000, 2, 1)
        val chunks = samples.toList().chunked(777)
        assertArrayEquals(samples.filterIndexed { i, _ -> i % 2 == 0 }.toShortArray(), chunks.flatMap { left.accept(it.toShortArray()).toList() }.toShortArray())
        assertArrayEquals(samples.filterIndexed { i, _ -> i % 2 == 1 }.toShortArray(), chunks.flatMap { right.accept(it.toShortArray()).toList() }.toShortArray())
        assertTrue(StereoEvidence().apply { accept(samples, 2) }.distinct())
        assertFalse(StereoEvidence().apply { accept(ShortArray(64_000) { if (it % 2 == 0) 3000 else -1500 }, 2) }.distinct())
    }

    @Test fun trainingDisablesSpeakersAndCallPassesFixedTwoToAsr() = runBlocking {
        val files = (1..2).map { File.createTempFile("import077", ".ogg", context.cacheDir).apply { writeText("pcm") } }
        try {
            val training = record(ImportKind.Training); val call = record(ImportKind.Call)
            segment(training, 1, "Pending", files[0].path); segment(call, 2, "Pending", files[1].path)
            val requests = mutableListOf<AsrAudio>()
            val provider = object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    requests += audio; beforeAudio()
                    return AsrResult(listOf(AsrSentence(0, 1000, "原话", "0")), 1000, null)
                }
            }
            TranscribeRunner(db.transcribe(), provider, { "fake" }, { _, _ -> }).run()
            assertEquals(listOf(false, true), requests.map { it.separateSpeakers })
            assertEquals(2, requests.last().speakerCount)
            assertNull(db.imports().lines(training.id).single().speakerTag)
            assertTrue(ImportKindPolicy.separate(ImportKind.Meeting, false)); assertNull(ImportKindPolicy.speakers(ImportKind.Normal, false))
            db.capture().segment(SegmentEntity(id = 3, startAt = 3000, endAt = 4000, sourceId = "import", path = "/channel.ogg", sizeBytes = 1,
                status = "Done", separateSpeakers = false, importRecordId = call.id, importChannel = 0))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(3, 3, 3000, 4000, "我去提交报告", "channel:0", "我")))
            assertEquals(listOf(1L), db.transcribe().unseparated(listOf(1, 2, 3)))
            val text = app.resound.digest.DigestPrompt.transcript(db.imports().lines(call.id),
                unseparated = db.transcribe().unseparated(listOf(2, 3)).toSet()).first
            assertTrue(text.contains("我：我去提交报告")); assertEquals(2, db.memory().voiceprintPending(listOf(2, 3)))
        } finally { files.forEach { it.delete() } }
    }

    @Test fun speakerMergeUsesSimilarityAndChannelsOrderWithMeAndOther() {
        val groups = ImportSpeakerGroups.two(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(.99f, .01f), floatArrayOf(.01f, .99f)))
        assertEquals(2, groups.distinct().size); assertEquals(groups[0], groups[2]); assertEquals(groups[1], groups[3])
        val lines = listOf(TranscriptLineEntity(2, 2, 2000, 3000, "回答", "channel:1"),
            TranscriptLineEntity(1, 1, 1000, 1500, "提问", "channel:0", "我"), TranscriptLineEntity(3, 3, 4000, 5000, "再问", "channel:0"))
        val result = ImportSpeakerGroups.channelNames(lines)
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id }); assertEquals(listOf("我", "对方", "我"), result.map { it.speakerName })
        assertEquals(listOf("左声道", "右声道", "左声道"), ImportSpeakerGroups.channelNames(lines.map { it.copy(speakerName = null) }).map { it.speakerName })
    }

    @Test fun longRecordingSummarizesEveryChunkAndPreservesBoundaryActions() = runBlocking {
        val row = record().copy(endAt = 3 * ImportChunks.MILLIS)
        val lines = (0..2).map { i -> TranscriptLineEntity(i + 1L, 1, 1000 + i * ImportChunks.MILLIS,
            2000 + i * ImportChunks.MILLIS, "第${i + 1}块结论与行动", "0", "我") }
        val inputs = mutableListOf<String>()
        val result = summary { _, user -> inputs += user; reply("行动${inputs.size}", "L${inputs.size.coerceAtMost(3)}") }.generate(row, lines)
        assertEquals(4, inputs.size)
        lines.forEach { line -> assertTrue(inputs.any { it.contains(line.text) }) }
        assertTrue(inputs[1].contains("上一块末句"))
        val actions = SkillJson.values(JSONObject(result.card.valuesJson)).getValue("actions").items
        assertTrue(actions.containsAll(listOf("行动1", "行动2", "行动3")))
        assertEquals(row.endAt, result.card.endAt); assertEquals(4, result.todos.size)
    }

    @Test fun typeChangeDeletesOldCardAndRequeuesOnlyWhenSpeakerModeNeedsAudio() = runBlocking {
        val file = File.createTempFile("import077", ".ogg", context.cacheDir).apply { writeText("pcm") }
        try {
            val row = record(); segment(row, 1, path = file.path)
            val engine = ImportKindEngine(db, summary())
            engine.run(); val first = db.imports().get(row.id)!!.cardId!!
            engine.change(row.id, ImportKind.Training)
            assertNull(db.skills().card(first).first()); assertFalse(db.transcribe().segment(1).separateSpeakers!!)
            assertNull(db.voiceprint().line(1)!!.speakerName)
            engine.run(); assertEquals("import_training", db.skills().card(db.imports().get(row.id)!!.cardId!!).first()!!.skillId)
            engine.change(row.id, ImportKind.Call)
            assertEquals("Pending", db.transcribe().segment(1).status); assertEquals(2, db.transcribe().segment(1).speakerCount)
            file.delete(); engine.change(row.id, ImportKind.Meeting)
            assertEquals("Failed", db.imports().get(row.id)!!.status)
            assertTrue(db.imports().get(row.id)!!.error!!.startsWith("原声已清理"))
        } finally { file.delete() }
    }

    @Test fun waitsForEntireFileAndSpeakerCompletionAndRegeneratesFailure() = runBlocking {
        var fail = true; var calls = 0
        val row = record(complete = false); segment(row, 1)
        val engine = ImportKindEngine(db, summary { _, _ -> calls++; if (fail) error("fake") else reply() })
        engine.run(); assertEquals(0, calls)
        db.imports().update(row.copy(ingestComplete = true))
        db.transcribe().update(db.transcribe().segment(1).copy(voiceprintProcessedAt = null))
        engine.run(); assertEquals(0, calls)
        db.transcribe().update(db.transcribe().segment(1).copy(voiceprintProcessedAt = 1))
        engine.run(); assertEquals("Failed", db.imports().get(row.id)!!.status)
        fail = false; engine.regenerate(row.id); engine.run()
        assertEquals("Done", db.imports().get(row.id)!!.status); assertEquals(2, calls)
    }

    @Test fun deletionInvalidatesSummaryAndInFlightResponseCannotResurrectIt() = runBlocking {
        val row = record(); segment(row, 1); segment(row, 2)
        val engine = ImportKindEngine(db, summary())
        engine.run(); val card = db.imports().get(row.id)!!.cardId!!
        RoomPrivacyActions(db).forgetSegment(1)
        assertNull(db.skills().card(card).first()); assertEquals(listOf(2L), db.imports().segments(row.id).map { it.id })
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val delayed = ImportKindEngine(db, summary { _, _ -> entered.complete(Unit); finish.await(); reply() })
        val job = launch { delayed.run() }; entered.await()
        RoomPrivacyActions(db).forgetSegment(2); finish.complete(Unit); job.join()
        assertNull(db.imports().get(row.id)); assertTrue(db.skills().cardsOf("import_meeting").first().isEmpty())
    }

    @Test fun batchGroupingAndPersistentQueueRespectMergeSwitchAndUnknownTimes() = runBlocking {
        val inputs = listOf(ImportKindPolicy.BatchFile("b", 5000, 1000, true), ImportKindPolicy.BatchFile("a", 1000, 2000, true),
            ImportKindPolicy.BatchFile("c", 9000, 1000, false), ImportKindPolicy.BatchFile("d", 900000, 1000, true))
        val groups = ImportKindPolicy.batch(inputs, true)
        assertEquals(listOf(listOf("a", "c", "b"), listOf("d")), groups.map { it.map { it.id } })
        assertEquals(3000L, groups.first()[1].start); assertEquals(4, ImportKindPolicy.batch(inputs, false).size)
        fun importer() = AndroidAudioImporter(context, scope, inspect = { AudioMetadata(1000, null) }, describe = { SharedAudio("音频", null) }, enqueue = {})
        val source = importer()
        val pending = source.receive((1..2).map { i -> Uri.parse("content://audio/$i").also {
            shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(byteArrayOf(1)))
        } }).map { it.getOrThrow() }
        source.startBatch(listOf(pending.mapIndexed { i, p -> ImportKindPolicy.BatchFile(p.id, i * 1000L, 1000, true) }), null, ImportKind.Training)
        withTimeout(3000) { source.progress.first { it.queued == 2 } }
        val restored = importer(); val next = restored.next()!!
        assertEquals(ImportKind.Training, next.kind); assertEquals(2000L, next.recordEnd)
        assertTrue(restored.groupRemaining(next.recordKey!!, next.pending.id))
    }

    @Test fun migration25PreservesRowsAndValidatesCurrentSchema() = runBlocking {
        val name = "imports-v25.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/25.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { j -> old.execSQL(sql(indices.getJSONObject(j).getString("createSql"))) } }
            }
            schema.getJSONArray("setupQueries").let { repeat(it.length()) { i -> old.execSQL(it.getString(i)) } }
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'import','old.ogg',10,'Done')")
            old.version = 25
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertNull(migrated.transcribe().segment(1).importRecordId); assertTrue(migrated.imports().ids().isEmpty())
            assertTrue(migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun summaryTodosStayPendingAndMergeWithDigestWithoutRevivingDeletedItems() = runBlocking {
        val row = record(); segment(row, 1)
        val engine = ImportKindEngine(db, summary()); engine.run()
        assertEquals("Pending", db.imports().todos(row.id).single().status)
        db.memory().insertItems(listOf(db.imports().todos(row.id).single().copy(id = 0, content = "我提交报告")))
        ImportTodoMerge.all(db)
        assertEquals(1, db.imports().todos(row.id).count { it.mergedInto == null })
        db.openHelper.writableDatabase.execSQL("UPDATE memory_items SET deleted = 1 WHERE mergedInto IS NULL")
        engine.regenerate(row.id); engine.run()
        assertEquals(2, db.imports().todos(row.id).size)
        assertTrue(db.imports().todos(row.id).filter { it.mergedInto == null }.all { it.deleted })
    }

    @Test fun incrementalRowsRoundTripImportsAndTemplatesStayHidden() = runBlocking {
        db.close(); context.deleteDatabase("backup077.db"); db = CaptureDatabase.open(context, "backup077.db")
        val row = record(); segment(row, 1); ImportKindEngine(db, summary()).run()
        val folder = File(context.cacheDir, "backup077").apply { deleteRecursively(); mkdirs() }
        try {
            val snapshot = File(folder, "snapshot.db"); BackupArchive.snapshot(db, snapshot)
            val (version, rows) = DayVolumeRows.write(snapshot, File(folder, "rows"))
            val target = File(folder, "restore.db")
            DayVolumeRows.rebuild(context, target, version, rows.getValue("state"), rows.filterKeys { it != "state" }.values.toList())
            val restored = CaptureDatabase.open(context, target.path)
            try {
                assertEquals(row.id, restored.transcribe().segment(1).importRecordId)
                assertEquals("Done", restored.imports().get(row.id)!!.status)
                assertNotNull(restored.skills().card(restored.imports().get(row.id)!!.cardId!!).first())
            } finally { restored.close() }
            val files = SkillFiles(File(folder, "skills")) {
                File("src/main/assets/skills").listFiles()!!.filter { it.isDirectory }.map { dir ->
                    SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()
                }
            }
            assertFalse(files.skills.value.any { it.id.startsWith("import_") })
            for (kind in listOf(ImportKind.Meeting, ImportKind.Training, ImportKind.Call)) assertEquals(SkillTrigger.Manual, template(kind).first.trigger)
        } finally { folder.deleteRecursively() }
    }
}
