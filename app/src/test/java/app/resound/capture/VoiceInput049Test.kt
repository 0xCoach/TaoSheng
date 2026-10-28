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
    /** 停下说话后截图看输入框，不急着存；点发送后再截一张，取发出的整句和发送后的上下文 */
    private val sentShot = OcrInput("", listOf("对方：先前的话", "对方：刚来的回复"), "老王", "我的原话！")
    private fun run(results: List<OcrInput?>, leaveAt: Long? = null, sendAt: Long? = null): Pair<List<OcrRequest>, List<InputChange>> {
        val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
        val e = VoiceInputEngine(requests::add, changes::add)
        e.recording(true, 0, screen, settings); e.recording(false, 100, screen, settings)
        val times = (listOf(599L, 600L, 3100L, 10100L, 30100L, 64000L) + listOfNotNull(sendAt, sendAt?.plus(500))).sorted()
        for (t in times) {
            val here = if (leaveAt != null && t >= leaveAt) screen.copy(packageName = "other") else screen.copy(send = t == sendAt)
            e.tick(t, here, settings.copy(replies = true))
            requests.filter { e.accepts(it) }.forEach { e.recognized(it, results.getOrElse(it.shot) { results.last() }) }
        }
        return requests to changes
    }
    private fun saved(changes: List<InputChange>) = changes.filterIsInstance<InputChange.Save>().single().record
    @Test fun sendEndsMessageAndContextComesFromAfterSend() {
        val (requests, changes) = run(listOf(output.copy(text = "我的原"), output, sentShot), sendAt = 4000)
        assertEquals(listOf(0, 1, 2), requests.map { it.shot })
        val record = saved(changes)
        assertEquals("我的原话！", record.text); assertNull(record.meta.after); assertNull(record.replyUntil)
        assertEquals("对方：先前的话\n对方：刚来的回复", record.meta.before)
        // 一直没发：60 秒没动静按输入框存，上下文用输入框那张
        val idle = saved(run(listOf(output.copy(text = "我的原"), output)).second)
        assertEquals("我的原话", idle.text); assertEquals("先前的话", idle.meta.before)
    }
    @Test fun severalPiecesThenOneSendBecomeOneMessage() {
        val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
        val e = VoiceInputEngine(requests::add, changes::add)
        fun answer(r: OcrInput) = requests.filter { e.accepts(it) }.forEach { e.recognized(it, r) }
        e.recording(true, 0, screen, settings); e.recording(false, 100, screen, settings)
        e.tick(600, screen, settings); answer(output.copy(text = "第一段"))
        // 接着说第二段：同一条消息，不收尾
        e.recording(true, 1000, screen, settings); e.recording(false, 1500, screen, settings)
        e.tick(2000, screen, settings); answer(output.copy(text = "第一段接着第二段"))
        assertTrue(changes.isEmpty())
        // 键盘上的发送键没有点击可看：界面一变就截，输入框空了就算发出
        val after = OcrInput("", listOf("对方：先前的话", "我：第一段接着第二段"), "老王")
        e.tick(3600, screen.copy(changed = true), settings); e.tick(4100, screen, settings); answer(after)
        // 输入框空了再确认一张，确实没有残留才收尾
        assertTrue(changes.isEmpty())
        e.tick(5200, screen, settings); answer(after)
        val record = saved(changes)
        assertEquals("第一段接着第二段", record.text)
        assertEquals("对方：先前的话\n我：第一段接着第二段", record.meta.before)
    }
    @Test fun leavingAppCancelsBeforeScreenshotAndKeepsWhatWasRead() {
        // 0.5 秒前就离开：一张不截、不记
        val (r0, c0) = run(listOf(output, output, output), leaveAt = 500)
        assertTrue(r0.isEmpty()); assertTrue(c0.isEmpty())
        // 截过两张后离开：已截的照用，后面的不再截
        val (r1, c1) = run(listOf(output, output, output), leaveAt = 3200)
        assertEquals(listOf(0, 1), r1.map { it.shot }); assertEquals("我的原话", saved(c1).text)
    }
    @Test fun readableNodesOrDisabledOcrNeverScreenshot() {
        for (restoreNodes in listOf(false, true)) {
            val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
            val e = VoiceInputEngine(requests::add, changes::add)
            val before = if (restoreNodes) screen else screen.copy(field = "field", text = "")
            val after = screen.copy(field = "field", text = "可读原文")
            val rich = listOf("第一行", "第二行", "第三行")
            e.recording(true, 0, before.copy(lines = rich), settings); e.recording(false, 100, after.copy(lines = rich), settings)
            e.tick(1100, after.copy(lines = rich), settings); e.tick(3100, after.copy(lines = rich), settings); e.tick(61000, after.copy(lines = rich), settings)
            assertTrue(requests.isEmpty()); assertEquals("可读原文", changes.filterIsInstance<InputChange.Save>().single().record.text)
        }
    }
    /** 原文读得到、上下文读不全（微信）：原文照用读到的，另截一张图补上下文；截不成照原样存 */
    @Test fun readableTextWithSparseContextTakesOneContextScreenshot() {
        val after = screen.copy(field = "field", text = "可读原文", lines = listOf("一行"))
        for (shot in listOf(OcrInput("", listOf("对方：报告能交吗", "我：在写", "对方：好的"), "老王"), null)) {
            val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
            val e = VoiceInputEngine(requests::add, changes::add)
            e.recording(true, 0, after.copy(text = ""), settings); e.recording(false, 100, after, settings)
            e.tick(61000, after, settings)
            assertTrue(changes.isEmpty()); assertTrue(e.accepts(requests.single()))
            e.recognized(requests.single(), shot)
            val record = changes.filterIsInstance<InputChange.Save>().single().record
            assertEquals("可读原文", record.text)
            assertEquals(if (shot != null) "对方：报告能交吗\n我：在写\n对方：好的" else "一行", record.meta.before)
        }
        // 等截图时被暂停：照原样存，不丢
        val requests = mutableListOf<OcrRequest>(); val changes = mutableListOf<InputChange>()
        val e = VoiceInputEngine(requests::add, changes::add)
        e.recording(true, 0, after.copy(text = ""), settings); e.recording(false, 100, after, settings); e.tick(61000, after, settings)
        e.reset(); e.recognized(requests.single(), null)
        assertEquals("一行", changes.filterIsInstance<InputChange.Save>().single().record.meta.before)
    }
    @Test fun disabledOcrNeverScreenshots() {
        val requests = mutableListOf<OcrRequest>(); val e = VoiceInputEngine(requests::add) {}
        val disabled = settings.copy(screenOcr = false)
        e.recording(true, 0, screen, disabled); e.recording(false, 100, screen, disabled); e.tick(1100, screen, disabled)
        assertTrue(requests.isEmpty())
    }
    @Test fun failedOrBlackScreenshotSkipsWithoutRetryAndPauseInvalidatesResult() {
        assertTrue(VoiceInputOcr.black(100, 200, 20, 120) { _, y -> if (y < 20) 0xffffff else 0 })
        assertFalse(VoiceInputOcr.black(100, 200, 20, 120) { _, _ -> 0xffffff })
        // 都失败：每张只截一次，不重试，不记
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
            // 第四句的上下文全都记过：留最后三行，单看也知道在接什么话
            assertEquals(listOf("旧上下文", "我的第一句\n新增甲", "我的第二句\n新增乙", "旧上下文\n新增甲\n新增乙"), rows.map { VoiceInputMetadata.read(it.voiceInputJson)!!.before })
            assertTrue(rows.all { VoiceInputMetadata.read(it.voiceInputJson)!!.after == null && it.voiceInputPendingUntil == null })
        } finally { db.close() }
    }
    @Test fun dedupRespectsTimeChatAppNullTitleAndExactEquality() = runBlocking {
        val db = database()
        try {
            val store = VoiceInputStore(db)
            store.save(record(100, "我说甲", "上下文", null))
            store.save(record(200, "我说乙", "上下文\n新的一行", null))
            assertEquals("新的一行", VoiceInputMetadata.read(db.voiceInput().observed().first().first().voiceInputJson)!!.before)
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
        // 第二张输入框已清空（只剩残渣）：先不收尾，再截一张，出现新的右侧气泡，以气泡为准
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
