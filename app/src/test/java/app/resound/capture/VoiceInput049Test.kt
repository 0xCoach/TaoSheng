package app.resound.capture

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.transcribe.VoiceInputMeta
import app.resound.transcribe.VoiceInputMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VoiceInput049Test {
    private val settings = VoiceInputSettings(enabled = true)
    private val screen = InputScreen("chat", "聊天", "page", null, null, null)
    private val layout = OcrLayout(1000, 2000, 70, 1950, 1200)
    private fun line(text: String, top: Int, block: Int = 0, left: Int = 100) = OcrLine(text, left, top, left + 400, top + 40, block)
    private val output = OcrInput("我的原话", listOf("先前的话"), "老王")

    @Test fun chatMergesMultilineInputAndExtractsTitleAndContext() {
        val result = VoiceInputOcr.extract(listOf(line("老王", 100), line("明天能交吗", 500, 1),
            line("第一行原话", 1080, 2), line("第二行原话", 1135, 2)), layout)!!
        assertEquals("第一行原话\n第二行原话", result.text)
        assertEquals(listOf("明天能交吗"), result.context)
        assertEquals("老王", result.title)
    }
    @Test fun terminalUsesLowestParagraphAndMissingImeUsesBottomFortyPercent() {
        val result = VoiceInputOcr.extract(listOf(line("历史命令输出", 700, 1), line("执行部署检查", 1140, 2)), layout.copy(imeTop = null))!!
        assertEquals("执行部署检查", result.text)
        assertEquals(listOf("历史命令输出"), result.context)
        assertNull(result.title)
        assertNull(VoiceInputOcr.extract(listOf(line("只有远处的聊天", 600)), layout))
    }
    @Test fun filtersSystemBarsKeyboardTimestampsAndChrome() {
        val source = listOf(line("运营商", 10), line("23:41", 200), line("按住说话", 300),
            line("真实上下文", 600), line("真实原话", 1140, 2), line("发送", 1140, 3, 550),
            line("输入法候选", 1250), line("导航条", 1960))
        val result = VoiceInputOcr.extract(source, layout)!!
        assertEquals("真实原话", result.text)
        assertEquals(listOf("真实上下文"), result.context)
    }
    /** 停止录音后 0.5 / 1 / 3 秒各截一张；输入框以 1 秒为准，3 秒出现相近的靠右气泡则以气泡为准；不补记回复 */
    private fun run(results: List<OcrInput?>, leaveAt: Long? = null): Pair<List<OcrRequest>, List<InputChange>> {
        val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
        val e = VoiceInputEngine(requests::add, changes::add)
        e.recording(true, 0, screen, settings); e.recording(false, 100, screen, settings)
        for (t in listOf(599L, 600L, 1100L, 3100L, 4000L)) {
            val here = if (leaveAt != null && t >= leaveAt) screen.copy(packageName = "other") else screen
            e.tick(t, here, settings.copy(replies = true))
            requests.filter { e.accepts(it) }.forEach { e.recognized(it, results[it.shot]) }
        }
        return requests to changes
    }
    private fun saved(changes: List<InputChange>) = changes.filterIsInstance<InputChange.Save>().single().record
    @Test fun oneScreenshotAfterOneSecondAndNoRepliesEvenIfLegacySettingEnabled() {
        val (requests, changes) = run(listOf(output.copy(text = "我的原"), output, output))
        assertEquals(listOf(0, 1, 2), requests.map { it.shot })
        val record = saved(changes)
        assertEquals("我的原话", record.text); assertNull(record.meta.after); assertNull(record.replyUntil)
        assertEquals(listOf(1), changes.map { 1 })
        // 3 秒已发送：气泡与输入框相近，以气泡为准（发送前改了一个字）
        val sent = run(listOf(output, output, OcrInput("", emptyList(), null, "我的原话！"))).second
        assertEquals("我的原话！", saved(sent).text)
        // 1 秒没截成：用 0.5 秒
        assertEquals("我的原话", saved(run(listOf(output, null, null)).second).text)
        // 气泡和输入框对不上（别的旧消息）：仍用输入框
        assertEquals("我的原话", saved(run(listOf(output, output, output.copy(bubble = "完全不同的一条旧消息内容"))).second).text)
    }
    @Test fun leavingAppCancelsBeforeScreenshotAndDiscardsLateResult() {
        // 0.5 秒前就离开：一张不截、不记
        val (r0, c0) = run(listOf(output, output, output), leaveAt = 500)
        assertTrue(r0.isEmpty()); assertTrue(c0.isEmpty())
        // 1 秒后离开：已截的两张照用，3 秒那张不再截
        val (r1, c1) = run(listOf(output, output, output), leaveAt = 1200)
        assertEquals(listOf(0, 1), r1.map { it.shot }); assertEquals("我的原话", saved(c1).text)
    }
    @Test fun readableNodesOrDisabledOcrNeverScreenshot() {
        for (restoreNodes in listOf(false, true)) {
            val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
            val e = VoiceInputEngine(requests::add, changes::add)
            val before = if (restoreNodes) screen else screen.copy(field = "field", text = "")
            val after = screen.copy(field = "field", text = "可读原文")
            e.recording(true, 0, before, settings); e.recording(false, 100, after, settings)
            e.tick(1100, after, settings); e.tick(3100, after, settings)
            assertTrue(requests.isEmpty()); assertEquals("可读原文", changes.filterIsInstance<InputChange.Save>().single().record.text)
        }
        val requests = mutableListOf<OcrRequest>(); val e = VoiceInputEngine(requests::add) {}
        val disabled = settings.copy(screenOcr = false)
        e.recording(true, 0, screen, disabled); e.recording(false, 100, screen, disabled); e.tick(1100, screen, disabled)
        assertTrue(requests.isEmpty())
    }
    @Test fun failedOrBlackScreenshotSkipsWithoutRetryAndPauseInvalidatesResult() {
        assertTrue(VoiceInputOcr.black(100, 200, 20, 120) { _, y -> if (y < 20) 0xffffff else 0 })
        assertFalse(VoiceInputOcr.black(100, 200, 20, 120) { _, _ -> 0xffffff })
        // 三张都失败：每张只截一次，不重试，不记
        val (requests, changes) = run(listOf(null, null, null))
        assertEquals(3, requests.size); assertTrue(changes.isEmpty())
        // 暂停（reset）后迟到的结果作废
        val req = mutableListOf<OcrRequest>(); val ch = mutableListOf<InputChange>()
        val e = VoiceInputEngine(req::add, ch::add)
        e.recording(true, 0, screen, settings); e.recording(false, 100, screen, settings); e.tick(600, screen, settings)
        e.reset(); e.recognized(req.single(), output); assertTrue(ch.isEmpty())
    }
    private fun record(at: Long, text: String, before: String?, title: String? = "老王", pkg: String = "chat") =
        InputRecord("$at-$text", at, text, VoiceInputMeta("聊天", pkg, title, before, "不应存的回复"), 999999L)
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
        .allowMainThreadQueries().build()
    @Test fun threeDictationsOnlyStoreNewContextAndNeverOwnPreviousWords() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(100, "我的第一句", "旧上下文"))
            store.save(record(200, "我的第二句", "旧上下文\n我的第一句\n新增甲"))
            store.save(record(300, "我的第三句", "旧上下文\n新增甲\n我的第一句\n我的第二句\n新增乙"))
            store.save(record(400, "我的第四句", "旧上下文\n新增甲\n新增乙"))
            assertNull(store.save(record(500, "  我的第四句  ", "别的上下文")))
            val rows = db.voiceInput().observed().first().reversed()
            assertEquals(listOf("旧上下文", "新增甲", "新增乙", null), rows.map { VoiceInputMetadata.read(it.voiceInputJson)!!.before })
            assertTrue(rows.all { VoiceInputMetadata.read(it.voiceInputJson)!!.after == null && it.voiceInputPendingUntil == null })
        } finally { db.close() }
    }
    @Test fun dedupRespectsTimeChatAppNullTitleAndExactEquality() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(100, "我说甲", "上下文", null))
            store.save(record(200, "我说乙", "上下文", null))
            assertNull(VoiceInputMetadata.read(db.voiceInput().observed().first().first().voiceInputJson)!!.before)
            store.save(record(300, "我说丙", "上下文", "另一个聊天"))
            store.save(record(400, "我说丁", "上下文", null, "other"))
            store.save(record(1800500, "我说甲", "上下文", null))
            assertTrue(db.voiceInput().observed().first().take(3).all { VoiceInputMetadata.read(it.voiceInputJson)!!.before == "上下文" })
            assertNotEquals(VoiceInputDedup.key("a+b"), VoiceInputDedup.key("a-b"))
            assertNotEquals(VoiceInputDedup.key("明天交"), VoiceInputDedup.key("明天再交"))
        } finally { db.close() }
    }
    @Test fun legacyRepliesDisabledAndScreenOcrPersists() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("voice-input", 0)
        prefs.edit().clear().putBoolean("replies", true).commit()
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = AndroidVoiceInputCapture(context, db, scope)
            assertFalse(first.settings.value.replies); assertTrue(first.settings.value.screenOcr)
            first.update(settings.copy(replies = true, screenOcr = false))
            assertFalse(first.settings.value.replies)
            val second = AndroidVoiceInputCapture(context, db, scope)
            assertFalse(second.settings.value.screenOcr); assertFalse(second.settings.value.replies)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close(); prefs.edit().clear().commit() }
    }
}
