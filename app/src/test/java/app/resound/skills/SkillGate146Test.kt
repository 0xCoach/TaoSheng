package app.resound.skills

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.MemoryEventEntity
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import app.resound.digest.snapshot
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.push.Notifier
import app.resound.push.PushTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SkillGate146Test {
    private val day = LocalDate.parse("2026-11-12")
    private val start = day.atTime(10, 0).atZone(SKILL_ZONE).toInstant().toEpochMilli()
    private val gate = SkillGate(2, 10, 20, false)
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var files: SkillFiles
    private lateinit var scope: CoroutineScope
    private lateinit var official: SkillDefinition
    private var calls = 0
    private var pushes = 0
    private var input = ""
    private var reply = ""

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "skills146").apply { deleteRecursively(); mkdirs() }
        official = SkillJson.parse(File("src/main/assets/skills/meeting/skill.json").readText(), SkillOrigin.Builtin)
        files = SkillFiles(root) { listOf(official to "按现场讨论整理") }
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reply = output(card("valid", "10:00", "10:15"))
    }

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close(); root.deleteRecursively()
        Unit
    }

    private fun repo() = LocalSkillRepository(files, db.skills(), db.memory(), scope,
        { LlmPreset("fake", "测试", "https://example.invalid", "fake") to "test-only" },
        { _, _, _, user -> calls++; input = user; LlmReply(reply, 10, 2) }, { emptyList() },
        object : Notifier {
            override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { pushes++ }
        }, { _, _ -> }, { start + 12 * 3_600_000L })

    private fun card(entity: String, at: String, end: String?) =
        """{"entity":"$entity","title":"方案评审","summary":"先试点","at":"$at","end":${end?.let { "\"$it\"" } ?: "null"},"values":{"topic":"流程优化","conclusion":"先试点再评估"},"evidence":["L1"]}"""
    private fun output(vararg cards: String) = """{"cards":[${cards.joinToString(",")}]}"""
    private fun current(id: String = "meeting") = files.skills.value.first { it.id == id }
    private fun mineFile(id: String) = File(root, "mine/$id/skill.json")
    private fun nothing(result: SkillRunResult, reason: String) {
        assertTrue(result.toString(), result is SkillRunResult.Nothing)
        assertTrue(result.toString(), (result as SkillRunResult.Nothing).reason.contains(reason))
    }

    private suspend fun seed(count: Int, minutes: Int, speakers: Int, typed: Boolean = false,
        unknown: Boolean = false, longText: Boolean = false) {
        val end = start + minutes * 60_000L
        db.capture().segment(SegmentEntity(1, start, end + 1000, "mic", "/unused", 0, "Done"))
        db.memory().insertEvent(MemoryEventEntity(1, start, end, "讨论", "流程评审", "讨论流程改进", "[]", "[]",
            null, null, null, false, false, end + 2000, "fake"))
        db.transcribe().insertLines((0 until count).map { i ->
            val at = start + minutes * 60_000L * i / (count - 1).coerceAtLeast(1)
            TranscriptLineEntity(id = i + 1L, segmentId = 1, startAt = at, endAt = at + 1000,
                text = if (longText) "讨论流程优化".repeat(400) else "第 $i 句：先试点再评估",
                speakerTag = if (unknown && i > 0) "uncertain:$i" else null,
                speakerName = if (unknown && i > 0) null else "成员${i % speakers}", eventId = 1,
                voiceInputJson = if (typed) """{"app":"聊天","text":"输入文字"}""" else null)
        })
    }

    @Test fun twoLineSmallTalkDoesNotCallModel() = runBlocking {
        seed(2, 1, 2)
        nothing(repo().runNow("meeting", day), "不到 10 分钟")
        assertEquals(0, calls)
        assertEquals(0L, db.skills().runsOf("meeting").single().tokens)
    }

    @Test fun longMonologueDoesNotBecomeDiscussion() = runBlocking {
        seed(40, 15, 1)
        nothing(repo().runNow("meeting", day), "1 个人")
        assertEquals(0, calls)
    }

    @Test fun voiceInputIsExcludedFromAllStatsUnlessExplicitlyCounted() = runBlocking {
        seed(30, 20, 2, typed = true)
        val r = repo()
        nothing(r.trial("meeting", day), "不到 10 分钟")
        assertEquals(0, calls)
        assertEquals(SkillGateStats(0, 0, 0), SkillGateRules.stats(gate,
            SkillInputs.gateLines(db.skills().eventLines(listOf(1)).map { it.snapshot() }, SkillSpeakers.All)))
        official = official.copy(skill = official.skill.copy(gate = gate.copy(countVoiceInput = true)))
        files.reload()
        reply = output(card("typed", "10:00", "10:20"))
        assertTrue(r.trial("meeting", day) is SkillRunResult.Cards)
        assertEquals(1, calls)
    }

    @Test fun uncertainLabelsDoNotAddPeople() = runBlocking {
        seed(40, 15, 1, unknown = true)
        nothing(repo().runNow("meeting", day), "1 个人")
        assertEquals(0, calls)
    }

    @Test fun realMeetingUsesCompleteLinesBeforeCharacterTrimming() = runBlocking {
        seed(40, 15, 3, longText = true)
        val result = repo().runNow("meeting", day) as SkillRunResult.Cards
        assertEquals(1, calls); assertEquals(1, result.cards.size)
        assertTrue(input.contains("原话按身份/字数裁剪"))
        val source = SkillInputs.source(db.skills(), current(), day, null, start + 12 * 3_600_000)
        assertTrue(app.resound.skills.core.SkillInputs.boundedLines(source.lines, SkillSpeakers.All).size < 20)
        assertEquals(1, db.skills().dayRows("meeting", day.toEpochDay()).size)
    }

    @Test fun shortCardInsideLongWindowIsDiscardedBeforeSavingAndPush() = runBlocking {
        seed(161, 120, 2) // 10:30～10:33 内恰好 5 句
        files.change("meeting") { it.put("push", true) }
        reply = output(card("too_short", "10:30", "10:33"))
        val r = repo()
        r.afterDigest()
        val run = db.skills().runsOf("meeting").single()
        assertTrue(run.result, run.result.contains("整理出的讨论不到 10 分钟"))
        assertTrue(run.result, run.result.contains("门槛复核丢弃 1 张"))
        assertEquals(1, calls); assertEquals(0, pushes)
        assertTrue(r.cards(day).first().isEmpty())
    }

    @Test fun previewKeepsPassingCardAndRejectsCardWithoutEnd() = runBlocking {
        seed(40, 15, 3)
        reply = output(card("missing_end", "10:00", null), card("valid", "10:00", "10:15"))
        val r = repo()
        val result = r.trial("meeting", day) as SkillRunResult.Cards
        assertEquals(1, result.cards.size); assertNotNull(result.cards.single().endAt)
        assertEquals(1, calls); assertTrue(r.cards(day).first().isEmpty())
        assertTrue(db.skills().runsOf("meeting").single().result.contains("出了 1 张卡；门槛复核丢弃 1 张"))
    }

    @Test fun enoughTimeAndPeopleStillRequiresEnoughLines() = runBlocking {
        seed(6, 15, 2)
        nothing(repo().runNow("meeting", day), "原话只有 6 句")
        assertEquals(0, calls)
    }

    @Test fun legacyFileWithoutGateStillCallsModel() = runBlocking {
        val old = JSONObject(SkillJson.encode(official)).apply { remove("gate") }.toString()
        official = SkillJson.parse(old, SkillOrigin.Builtin); files.reload()
        assertNull(current().gate)
        seed(2, 1, 2)
        assertTrue(repo().runNow("meeting", day) is SkillRunResult.Cards)
        assertEquals(1, calls)
        assertNull(SkillJson.parse(File("src/main/assets/skills/import_meeting/skill.json").readText(), SkillOrigin.Builtin).skill.gate)
    }

    @Test fun copiedAndLegacyMineInheritCurrentOfficialEvenAfterUnrelatedSave() = runBlocking {
        val id = files.copyAsMine("meeting").getOrThrow()
        assertFalse(JSONObject(mineFile(id).readText()).has("gate"))
        assertEquals(gate, current(id).gate)
        files.saveMine(current(id).copy(name = "我的例会"), "新的要求").getOrThrow()
        assertFalse(JSONObject(mineFile(id).readText()).has("gate"))
        official = official.copy(skill = official.skill.copy(gate = gate.copy(minSpanMinutes = 12)))
        files.reload()
        assertEquals(12, current(id).gate!!.minSpanMinutes)
        seed(40, 11, 3)
        nothing(repo().runNow(id, day), "不到 12 分钟")
        assertEquals(0, calls)
    }

    @Test fun explicitNullAndCustomGateSurviveOfficialChangesAndReload() = runBlocking {
        val off = files.copyAsMine("meeting").getOrThrow()
        files.saveMine(current(off).copy(gate = null), "不设门槛").getOrThrow()
        assertTrue(JSONObject(mineFile(off).readText()).has("gate"))
        assertTrue(JSONObject(mineFile(off).readText()).isNull("gate"))
        val custom = files.copyAsMine("meeting").getOrThrow()
        files.saveMine(current(custom).copy(gate = SkillGate(2, 1, 2)), "短讨论").getOrThrow()
        official = official.copy(skill = official.skill.copy(gate = gate.copy(minSpanMinutes = 30)))
        files.reload()
        assertNull(current(off).gate); assertEquals(SkillGate(2, 1, 2), current(custom).gate)
        seed(2, 1, 2)
        val r = repo()
        assertTrue(r.runNow(off, day) is SkillRunResult.Cards)
        assertTrue(r.runNow(custom, day) is SkillRunResult.Cards)
        assertEquals(2, calls)
    }

    @Test fun gateJsonRoundTripsAndRejectsInvalidValues() {
        assertEquals(gate, SkillJson.parse(SkillJson.encode(official), SkillOrigin.Builtin).skill.gate)
        val base = JSONObject(SkillJson.encode(official))
        val invalid = listOf("minSpeakers" to 0, "minSpeakers" to 21, "minSpanMinutes" to -1,
            "minSpanMinutes" to 601, "minLines" to -1, "minLines" to 5001, "minLines" to 2.5,
            "minSpeakers" to "2", "countVoiceInput" to "false")
        for ((key, value) in invalid) {
            val json = JSONObject(base.toString()).apply { getJSONObject("gate").put(key, value) }
            assertTrue("$key=$value", runCatching { SkillJson.parse(json.toString(), SkillOrigin.Builtin) }.isFailure)
        }
        assertEquals(SkillGate(20, 600, 5000, true), SkillGateJson.read(
            """{"gate":{"minSpeakers":20,"minSpanMinutes":600,"minLines":5000,"countVoiceInput":true}}""").gate)
        assertEquals(SkillGate(), SkillGateJson.read("""{"gate":{}}""").gate)
    }

    @Test fun drafterAcceptsReasonableGateAndRejectsOutOfRangeInsteadOfDisabling() {
        val skill = JSONObject(SkillJson.encode(official)).put("prompt", "按实际访谈写结论")
        val result = SkillDrafter.step(JSONObject().put("skill", skill).toString(), "my_interview", false) as DraftStep.Done
        assertEquals(gate, result.draft.skill.gate)
        skill.getJSONObject("gate").put("minSpanMinutes", 601)
        assertTrue(runCatching { SkillDrafter.step(JSONObject().put("skill", skill).toString(), "my_interview", false) }.isFailure)
        assertTrue(SkillDrafter.SYSTEM.contains("会议、访谈、例会"))
    }

    @Test fun singleCardGateKeepsActualTimeAndReadsWeekWithoutLinesInPrompt() = runBlocking {
        official = official.copy(skill = official.skill.copy(maxCardsPerDay = 1,
            scope = SkillScope(SkillRange.Week, SkillSpeakers.All, false)))
        files.reload(); seed(20, 10, 2)
        reply = output(card("boundary", "10:00", "10:10"))
        val result = repo().runNow("meeting", day) as SkillRunResult.Cards
        assertEquals(start, result.cards.single().at)
        assertEquals(1, calls); assertFalse(input.contains("第 0 句"))
    }

    @Test fun cardRecheckDoesNotBorrowVoiceInputOrOtherSpeakersOutsideItsTime() = runBlocking {
        seed(40, 15, 2)
        // 全范围已有两人真会议，第二张覆盖的时段只有语音输入。
        db.transcribe().insertLines((0 until 30).map { i -> TranscriptLineEntity(
            id = 100L + i, segmentId = 1, startAt = start + 3_600_000 + i * 60_000L,
            endAt = start + 3_601_000 + i * 60_000L, text = "输入文字", speakerTag = null,
            speakerName = "输入者${i % 2}", eventId = 1, voiceInputJson = "{}") })
        reply = output(card("typed", "11:00", "11:29"))
        nothing(repo().runNow("meeting", day), "不到 10 分钟")
        assertEquals(1, calls); assertTrue(db.skills().dayRows("meeting", day.toEpochDay()).isEmpty())
    }
}
