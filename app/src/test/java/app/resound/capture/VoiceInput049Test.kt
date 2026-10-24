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
        assertEquals("第一行原话第二行原话", result.text)
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
        for (t in listOf(599L, 600L, 1300L, 3100L, 6100L, 7000L)) {
            val here = if (leaveAt != null && t >= leaveAt) screen.copy(packageName = "other") else screen
            e.tick(t, here, settings.copy(replies = true))
            requests.filter { e.accepts(it) }.forEach { e.recognized(it, results.getOrElse(it.shot) { results.last() }) }
        }
        return requests to changes
    }
    private fun saved(changes: List<InputChange>) = changes.filterIsInstance<InputChange.Save>().single().record
    @Test fun oneScreenshotAfterOneSecondAndNoRepliesEvenIfLegacySettingEnabled() {
        val (requests, changes) = run(listOf(output.copy(text = "我的原"), output, output))
        // 3 秒时还没发出（输入框还有字、没有新气泡）：再截 6 秒那张
        assertEquals(listOf(0, 1, 2, 3), requests.map { it.shot })
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
        val (r1, c1) = run(listOf(output, output, output), leaveAt = 1400)
        assertEquals(listOf(0, 1), r1.map { it.shot }); assertEquals("我的原话", saved(c1).text)
    }
    @Test fun readableNodesOrDisabledOcrNeverScreenshot() {
        for (restoreNodes in listOf(false, true)) {
            val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
            val e = VoiceInputEngine(requests::add, changes::add)
            val before = if (restoreNodes) screen else screen.copy(field = "field", text = "")
            val after = screen.copy(field = "field", text = "可读原文")
            e.recording(true, 0, before, settings); e.recording(false, 100, after, settings)
            e.tick(1100, after, settings); e.tick(3100, after, settings); e.tick(61000, after, settings)
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
        assertEquals(4, requests.size); assertTrue(changes.isEmpty())
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
    @Test fun threeDictationsDedupOnlyPreviouslyStoredContext() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(100, "我的第一句", "旧上下文"))
            store.save(record(200, "我的第二句", "旧上下文\n我的第一句\n新增甲"))
            store.save(record(300, "我的第三句", "旧上下文\n新增甲\n我的第一句\n我的第二句\n新增乙"))
            store.save(record(400, "我的第四句", "旧上下文\n新增甲\n新增乙"))
            assertNull(store.save(record(500, "  我的第四句  ", "别的上下文")))
            val rows = db.voiceInput().observed().first().reversed()
            assertEquals(listOf("旧上下文", "我的第一句\n新增甲", "我的第二句\n新增乙", null), rows.map { VoiceInputMetadata.read(it.voiceInputJson)!!.before })
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
    @Test fun terminalTakesPromptLineNotStatusOrKeyRow() {
        // 模拟终端：历史输出 → 提示符行（我正在输入的）→ 状态行 → 按键行 → 输入法
        val source = listOf(line("历史输出一", 700), line("历史输出二", 760),
            line("》合成的终端输入", 900, 5), line("状态 模型 合成 | 限额", 960, 6), line("auto mode On (shift+tab)", 1010, 7),
            line("Ctrl+C Ctrl+V", 1120, 8))
        val r = VoiceInputOcr.extract(source, layout)!!
        assertEquals("合成的终端输入", r.text)
        assertTrue("历史输出二" in r.context)
    }
    @Test fun keyRowsAreNeverTheUtterance() {
        assertFalse(VoiceInputChrome.keep("Ctrl+C Ctrl+V")); assertFalse(VoiceInputChrome.keep("ESC TAB ↑ ↓"))
        assertTrue(VoiceInputChrome.keep("帮我看下 ctrl 键的问题"))
    }
    @Test fun wrappedInputLinesJoinIntoOneSentence() {
        assertEquals("是合成的一句话，折行了也接回来", VoiceInputOcr.joinLines(listOf("是合成的一句话，折行", "了也接回来")))
        assertEquals("hello world", VoiceInputOcr.joinLines(listOf("hello", "world")))
    }
    @Test fun pausedDictationExtendsPreviousLineInsteadOfAddingOne() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            val first = store.save(record(1000, "合成的前半句话我在", "上下文"))!!
            // 识字把前半句个别字认得不一样，后面接着说
            assertEquals(first, store.save(record(5000, "合成的前半句活我在测试后半句", "上下文")))
            val rows = db.voiceInput().observed().first()
            assertEquals(1, rows.size); assertEquals("合成的前半句活我在测试后半句", rows.single().text)
            // 超过 2 分钟就是新的一条
            assertNotNull(store.save(record(200000, "合成的前半句活我在测试后半句再加一点", "上下文")))
            assertEquals(2, db.voiceInput().observed().first().size)
        } finally { db.close() }
    }
    @Test fun titleNeedsRealText() {
        assertFalse(VoiceInputOcr.looksLikeTitle("く")); assertFalse(VoiceInputOcr.looksLikeTitle("<"))
        assertTrue(VoiceInputOcr.looksLikeTitle("老王"))
    }
    @Test fun chatContextIsSidedAndOwnSentBubbleWins() {
        // 右边是我、左边是对方
        val ctx = VoiceInputOcr.sided(listOf(line("对方说的合成一句", 400, 1, left = 60), line("我回的合成一句", 500, 2, left = 600)), 1000)
        assertEquals(listOf("对方：对方说的合成一句", "我：我回的合成一句"), ctx)
        // 1 秒时输入框已清空（只剩残渣）：用 0.5 秒那张；3 秒出现新的右侧气泡，以气泡为准
        val (_, c) = run(listOf(OcrInput("合成的一句完整的话", listOf("旧"), "老王"), OcrInput("(の|", listOf("旧"), "老王"),
            OcrInput("", listOf("旧"), "老王", "合成的一句完整的话。")))
        assertEquals("合成的一句完整的话。", saved(c).text)
        assertNull(VoiceInputOcr.clean("(の|")); assertEquals("合成一句", VoiceInputOcr.clean("合成一句|"))
        assertFalse(VoiceInputOcr.looksLikeTitle("<1344")); assertFalse(VoiceInputOcr.looksLikeTitle("+putOcr.looksLike(\"く\")"))
    }
    @Test fun ownEarlierUtteranceCanEnterContextOnce() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(1000, "我发的第一段合成话", "对方：你好呀合成"))
            // 已存为原话不代表已存为上下文：第一次出现必须保留，以后重复才去掉。
            store.save(record(9000, "我发的第二段合成话", "对方：你好呀合成\n我：我发的第一段合成活\n对方：新的回复合成"))
            store.save(record(20000, "下一句完全不同的内容", "对方：你好呀合成\n我：我发的第一段合成话\n对方：新的回复合成\n我：我发的第二段合成话\n对方：接下来的回复"))
            val rows = db.voiceInput().observed().first().reversed()
            assertEquals(listOf("对方：你好呀合成", "我：我发的第一段合成活\n对方：新的回复合成",
                "我：我发的第二段合成话\n对方：接下来的回复"),
                rows.map { VoiceInputMetadata.read(it.voiceInputJson)!!.before })
        } finally { db.close() }
    }
    @Test fun sidesByEdgeDistanceAndStrictTerminal() {
        fun at(l: Int, r: Int) = OcrLine("合成", l, 500, r, 540)
        // 微信里我发的长消息（离右边近）、对方的长消息（离左边近）、居中的时间（不标）
        assertEquals("我", VoiceInputOcr.side(at(250, 830), 1000, "com.tencent.mm"))
        assertEquals("对方", VoiceInputOcr.side(at(170, 750), 1000, "com.tencent.mm"))
        assertEquals("", VoiceInputOcr.side(at(420, 580), 1000, "com.tencent.mm"))
        // AI 的回答整行铺满：算对方
        assertEquals("对方", VoiceInputOcr.side(at(50, 950), 1000, "com.google.android.googlequicksearchbox"))
        // 按钮带了括号也认得出
        assertFalse(VoiceInputChrome.keep("(发送")); assertFalse(VoiceInputChrome.keep("发送）"))
        // 终端：没有提示符行、或提示符行是空的，宁可不记
        val noPrompt = listOf(line("屏幕上的输出合成", 900), line("状态合成", 1000))
        assertNull(VoiceInputOcr.extract(noPrompt, layout, "com.netease.uuremote"))
        assertNull(VoiceInputOcr.extract(noPrompt + line("》", 950, 4), layout, "com.netease.uuremote"))
        assertEquals("合成输入", VoiceInputOcr.extract(noPrompt + line("》合成输入", 950, 4), layout, "com.netease.uuremote")!!.text)
        // 标题只给微信
        val chat = listOf(line("老王", 80), line("合成上文", 600), line("合成输入框", 1140, 9))
        assertNull(VoiceInputOcr.extract(chat, layout, "com.openai.chatgpt")!!.title)
        assertEquals("老王", VoiceInputOcr.extract(chat, layout, "com.tencent.mm")!!.title)
    }
    @Test fun hotwordsFixNearMissesOnly() {
        assertEquals("我在UU远程里说的", VoiceInputHotwords.correct("我在Du远程里说的", emptyList()))
        assertEquals("用ChatGPT问一下", VoiceInputHotwords.correct("用ChatGP问一下", emptyList()))
        assertEquals("马正涛你好", VoiceInputHotwords.correct("马正滔你好", listOf("马正涛")))
        // 不相干的词不动
        assertEquals("今天天气不错", VoiceInputHotwords.correct("今天天气不错", listOf("马正涛")))
        assertEquals("远程会议", VoiceInputHotwords.correct("远程会议", emptyList()))
    }
    @Test fun wechatUsesContactNameInContext() {
        fun chat(title: String) = listOf(line(title, 80), OcrLine("报告明天能交吗合成", 60, 600, 600, 640, 1), OcrLine("好的合成", 700, 700, 950, 740, 2),
            line("合成输入框", 1140, 9))
        val single = VoiceInputOcr.extract(chat("马正涛"), layout, "com.tencent.mm")!!
        assertEquals("马正涛", single.title)
        assertEquals(listOf("马正涛：报告明天能交吗合成", "我：好的合成"), single.context)
        val group = VoiceInputOcr.extract(chat("周五团建(12)"), layout, "com.tencent.mm")!!
        assertEquals(listOf("对方：报告明天能交吗合成", "我：好的合成"), group.context)
    }
    @Test fun sameTextFromDifferentSpeakersIsNotDuplicateContext() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(1000, "第一句合成输入", "对方：明天见"))
            store.save(record(9000, "第二段新的内容", "对方：明天见\n我：明天见"))
            store.save(record(20000, "最后一段测试", "对方：明天见\n我：明天见\n对方：到时联系"))
            val contexts = db.voiceInput().observed().first().reversed()
                .map { VoiceInputMetadata.read(it.voiceInputJson)!!.before }
            assertEquals(listOf("对方：明天见", "我：明天见", "对方：到时联系"), contexts)
        } finally { db.close() }
    }

}
