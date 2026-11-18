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
import kotlinx.datetime.toKotlinLocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SkillGate207Test {
 @org.junit.Before fun zone200Fixture() { app.resound.platform.HistoricalZones.history = { app.resound.platform.ZoneHistory(emptyList(), "Asia/Shanghai") } }
 @org.junit.After fun reset200Zone() { app.resound.platform.HistoricalZones.history = { app.resound.platform.ZoneHistory(emptyList(), kotlinx.datetime.TimeZone.currentSystemDefault().id) } }
    private val day = LocalDate.parse("2026-11-12")
    private val start = day.atTime(10, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
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
        root = File(context.cacheDir, "skills207").apply { deleteRecursively(); mkdirs() }
        official = SkillJson.parse(File("src/main/assets/skills/meeting/skill.json").readText(), SkillOrigin.Builtin)
        files = SkillFiles(root) { listOf(official to "按现场讨论整理") }
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
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
        }, { _, _ -> }, { start + 12 * 3_600_000L }, io = kotlinx.coroutines.Dispatchers.IO)

    private fun card(entity: String, at: String, end: String?) =
        """{"entity":"$entity","title":"方案评审","summary":"先试点","at":"$at","end":${end?.let { "\"$it\"" } ?: "null"},"values":{"topic":"流程优化","conclusion":"先试点再评估"},"evidence":["L1"]}"""
    private fun output(vararg cards: String) = """{"cards":[${cards.joinToString(",")}]}"""
    private fun current(id: String = "meeting") = files.skills.value.first { it.id == id }
    private fun nothing(result: SkillRunResult, reason: String) {
        assertTrue(result.toString(), result is SkillRunResult.Nothing)
        assertTrue(result.toString(), (result as SkillRunResult.Nothing).reason.contains(reason))
    }

    private suspend fun seed(count: Int, minutes: Int, speakers: Int, typed: Boolean = false,
        unknown: Boolean = false, longText: Boolean = false, scene: String = "讨论", unknownForty: Boolean = false) {
        val end = start + minutes * 60_000L
        db.capture().segment(SegmentEntity(1, start, end + 1000, "mic", "/unused", 0, "Done"))
        db.memory().insertEvent(MemoryEventEntity(1, start, end, scene, "流程评审", "讨论流程改进", "[]", "[]",
            null, null, null, false, false, end + 2000, "fake"))
        db.transcribe().insertLines((0 until count).map { i ->
            val at = start + minutes * 60_000L * i / (count - 1).coerceAtLeast(1)
            TranscriptLineEntity(id = i + 1L, segmentId = 1, startAt = at, endAt = at + 1000,
                text = if (longText) "讨论流程优化".repeat(400) else "第 $i 句：先试点再评估",
                speakerTag = if (unknown && i > 0) "uncertain:$i" else null,
                speakerName = if ((unknown && i > 0) || (unknownForty && i % 5 < 2)) null else if (speakers == 1) "我" else "成员${i % speakers}", eventId = 1,
                voiceInputJson = if (typed) """{"app":"聊天","text":"输入文字"}""" else null)
        })
    }


    @Test fun randomMeetingUnknownVoicesProducesCard() = runBlocking {
        seed(30, 15, 1, unknown = true, scene = "会议")
        assertTrue(repo().runNow("meeting", day) is SkillRunResult.Cards)
        assertEquals(1, calls)
    }

    @Test fun unknownFortyPercentWithoutMeetingEventProducesCard() = runBlocking {
        seed(30, 15, 1, unknownForty = true)
        assertTrue(repo().runNow("meeting", day) is SkillRunResult.Cards)
    }

    @Test fun missingEndWithFifteenMinuteQuotesPassesRecheck() = runBlocking {
        seed(30, 15, 2)
        reply = output(card("quoted", "10:00", null)).replace("\"L1\"", "\"L1\",\"L30\"")
        val result = repo().runNow("meeting", day) as SkillRunResult.Cards
        assertEquals(start + 15 * 60_000 + 1000, result.cards.single().endAt)
    }

    @Test fun twentyMinuteMonologueDoesNotCallModel() = runBlocking {
        seed(30, 20, 1)
        nothing(repo().runNow("meeting", day), "1 个人")
        assertEquals(0, calls)
    }

    @Test fun threeMinuteChatDoesNotCallModel() = runBlocking {
        seed(30, 3, 2)
        nothing(repo().runNow("meeting", day), "不到 10 分钟")
        assertEquals(0, calls)
    }

    @Test fun eventReasonSurvivesRepositoryRecreationAndForceUsesEventRange() = runBlocking {
        seed(30, 3, 1, scene = "会议")
        val r = repo()
        nothing(r.runNow("meeting", day), "不到 10 分钟")
        assertTrue(repo().meetingNothingReason(1)!!.contains("不到 10 分钟"))
        assertNull(r.meetingNothingReason(999))
        reply = output(card("forced", "10:00", "10:03"))
        assertTrue(r.generateMeetingEvent(1) is SkillRunResult.Cards)
        assertEquals(1, calls)
        assertFalse(input.contains("11:00"))
    }

    @Test fun backfillRespectsDisabledSkillAndDailyCardLimit() = runBlocking {
        seed(30, 15, 2, scene = "会议")
        files.setEnabled("meeting", false)
        val r = repo()
        r.backfillMeetings207(day.toKotlinLocalDate())
        assertEquals(0, calls)
        files.setEnabled("meeting", true)
        val row = SkillOutput.parse(reply, current(), day, SkillInput("", emptyMap()), start, false).rows.single()
        repeat(current().maxCardsPerDay) { i ->
            db.skills().save(row.copy(id = 0, entity = "existing$i",
                at = start - 3_600_000 - i * 120_000L, endAt = start - 3_540_000 - i * 120_000L))
        }
        r.backfillMeetings207(day.toKotlinLocalDate())
        assertEquals(1, calls)
        assertEquals(current().maxCardsPerDay, db.skills().dayRows("meeting", day.toEpochDay()).size)
        assertTrue(r.meetingNothingReason(1)!!.contains("每天上限"))
    }

    @Test fun backfillSkipsEventsWithExistingCards() = runBlocking {
        seed(30, 15, 1, unknown = true, scene = "会议")
        // 最后一整句的结束时间可比事件边界晚，补全后仍应保存。
        reply = output(card("backfill", "10:00", null)).replace("\"L1\"", "\"L1\",\"L30\"")
        val r = repo()
        r.backfillMeetings207(day.toKotlinLocalDate())
        assertEquals(1, calls)
        r.backfillMeetings207(day.toKotlinLocalDate())
        assertEquals(1, calls)
        assertEquals(1, db.skills().dayRows("meeting", day.toEpochDay()).size)
    }
}
