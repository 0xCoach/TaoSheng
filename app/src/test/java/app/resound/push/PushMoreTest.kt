package app.resound.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.diary.LocalDiaryRepository
import app.resound.review.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PushMoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val date = LocalDate.parse("2026-10-24")
    private val at = 1_700_000_000_000L
    private val message = PushMessage("标题<&", "## 内容\n**要点** & <hello>", PushTopic.NewEvent, null, at)
    private val hook = mapOf("url" to "http://100.64.1.2/hook")
    private val database = "0123456789abcdef0123456789abcdef"
    private val notion = mapOf("token" to "fake-notion-token", "databaseId" to database)
    private fun document(markdown: String = "# 记忆\n- 要点\n> 原话") = message.copy(title = "每日记忆文档 · $date", body = "回顾简洁版", topic = PushTopic.DailyMemory,
        memory = dailyMemoryJson(date, at, markdown, null, ReviewInput(emptyList(), emptyList()), emptyList(), null))

    private class Fake : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val originals = Collections.synchronizedList(mutableListOf<Request>())
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); originals += r
            // 所有目的地强制改为本地，连 DNS 都不访问服务商。
            chain.proceed(r.newBuilder().url(server.url(r.url.encodedPath + (r.url.encodedQuery?.let { "?$it" } ?: ""))).build())
        }.build(), now = { 1_700_000_000_000L })
        fun reply(body: String = "{}", code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(if (code == 204) "" else body)) }
        fun take() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        override fun close() = server.shutdown()
    }
    private class Secrets : PushSecrets {
        val entries = mutableMapOf<String, String>()
        override fun read(ref: String) = entries[ref].orEmpty()
        override fun write(ref: String, value: String) { entries[ref] = value }
        override fun remove(ref: String) { entries.remove(ref) }
    }
    private fun repo(fake: Fake, secrets: Secrets = Secrets(), queue: MutableList<String> = mutableListOf()) = AndroidPushChannels(
        context.getSharedPreferences("more-push", 0), context.getSharedPreferences("more-outbox", 0), secrets, fake.transport, { queue += it })

    @Test fun feishuKnownSignatureAndInteractiveJsonFallback() = runBlocking {
        Fake().use { f ->
            f.reply("{\"code\":0}")
            val values = mapOf("webhook" to "https://open.feishu.cn/open-apis/bot/v2/hook/fake", "signSecret" to "secret")
            f.transport.send(PushKind.Feishu, PushFormat.Json, values, message).getOrThrow()
            val j = JSONObject(f.take().body.readUtf8())
            assertEquals("fiWS2+gh28DOydAv7hzONH/mDn9+b1Y4Y5ivXWXy8vA=", j.getString("sign"))
            assertEquals("1700000000", j.getString("timestamp"))
            assertEquals("interactive", j.getString("msg_type"))
            assertEquals(message.body, j.getJSONObject("card").getJSONArray("elements").getJSONObject(0).getString("content"))
            f.reply("{\"code\":0}"); f.transport.send(PushKind.Feishu, PushFormat.Text, values, message).getOrThrow()
            assertEquals("text", JSONObject(f.take().body.readUtf8()).getString("msg_type"))
        }
    }
    @Test fun dingTalkKnownSignatureIsUrlEncodedAndUsesMarkdown() = runBlocking {
        Fake().use { f ->
            f.reply("{\"errcode\":0}")
            f.transport.send(PushKind.DingTalk, PushFormat.Markdown, mapOf("webhook" to "https://oapi.dingtalk.com/robot/send?access_token=fake", "signSecret" to "secret"), message).getOrThrow()
            val url = f.originals.single().url
            assertEquals("OuzzJR5+xZ4/EYwqtNt6sMYZQMTa/HEGvc9miJe7XzY=", url.queryParameter("sign"))
            assertTrue(url.encodedQuery!!.contains("%2B")); assertEquals("1700000000000", url.queryParameter("timestamp"))
            assertEquals(message.title, JSONObject(f.take().body.readUtf8()).getJSONObject("markdown").getString("title"))
        }
    }
    @Test fun telegramEscapesThenTruncatesAndUploadsDailyDocumentThroughProxy() = runBlocking {
        Fake().use { f ->
            val values = mapOf("token" to "123:fake_token", "chatId" to "-10012", "baseUrl" to "https://proxy.example/api")
            f.reply("{\"ok\":true}")
            f.transport.send(PushKind.Telegram, PushFormat.Json, values, message.copy(body = "<&".repeat(3000))).getOrThrow()
            val j = JSONObject(f.take().body.readUtf8())
            assertEquals("HTML", j.getString("parse_mode")); assertTrue(j.getString("text").startsWith("<b>标题&lt;&amp;</b>"))
            assertTrue(j.getString("text").endsWith("（已截断）")); assertTrue(j.getString("text").replace("&lt;", "<").replace("&amp;", "&").replace("<b>", "").replace("</b>", "").length <= 4000)
            assertEquals("https://proxy.example/api/bot123:fake_token/sendMessage", f.originals.last().url.toString())
            f.reply("{\"ok\":true}"); f.transport.send(PushKind.Telegram, PushFormat.Text, values, message).getOrThrow()
            assertFalse(JSONObject(f.take().body.readUtf8()).has("parse_mode"))
            f.reply("{\"ok\":true}"); f.transport.send(PushKind.Telegram, PushFormat.Markdown, values, document().copy(body = "摘".repeat(1100))).getOrThrow()
            val request = f.take(); val body = request.body.readUtf8()
            assertTrue(request.path!!.endsWith("sendDocument")); assertTrue(body.contains("回响-$date.md")); assertTrue(body.contains("# 记忆")); assertTrue(body.contains("已截断"))
        }
    }
    @Test fun smtpLocalAuthDataTlsModesAndMimeAttachment() {
        ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<List<String>> {
                (1..2).map {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(); val writer = socket.getOutputStream().bufferedWriter()
                        fun reply(s: String) { writer.write(s + "\r\n"); writer.flush() }
                        reply("220 local")
                        assertEquals("EHLO resound.local", reader.readLine()); reply("250-local\r\n250 AUTH LOGIN STARTTLS")
                        var command = reader.readLine()
                        if (command == "STARTTLS") {
                            reply("220 ready"); assertEquals("EHLO resound.local", reader.readLine()); reply("250 AUTH LOGIN"); command = reader.readLine()
                        }
                        assertEquals("AUTH LOGIN", command); reply("334 user")
                        assertEquals("from@example.com", String(Base64.getDecoder().decode(reader.readLine()))); reply("334 pass")
                        assertEquals("fake-password", String(Base64.getDecoder().decode(reader.readLine()))); reply("235 ok")
                        assertEquals("MAIL FROM:<from@example.com>", reader.readLine()); reply("250 ok")
                        assertEquals("RCPT TO:<to@example.com>", reader.readLine()); reply("250 ok")
                        assertEquals("DATA", reader.readLine()); reply("354 data")
                        val data = buildList { while (true) { val line = reader.readLine(); if (line == ".") break; add(line) } }.joinToString("\r\n")
                        reply("250 queued"); assertEquals("QUIT", reader.readLine()); data
                    }
                }
            }
            var tlsCalls = 0
            val sender = SmtpSender(object : SmtpSockets() {
                override fun connect(host: String, port: Int) = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5000 }
                override fun tls(socket: Socket, host: String, port: Int): Socket { tlsCalls++; return socket }
            })
            val values = mapOf("host" to "smtp.example.com", "account" to "from@example.com", "password" to "fake-password", "to" to "to@example.com")
            try {
                sender.send(values + ("port" to "465"), PushFormat.Json, message)
                sender.send(values + ("port" to "587"), PushFormat.Text, document())
                val results = received.get(5, TimeUnit.SECONDS)
                assertEquals(2, tlsCalls); assertTrue(results[0].contains("Subject: =?UTF-8?B?"))
                val html = String(Base64.getMimeDecoder().decode(results[0].substringAfter("\r\n\r\n")))
                assertTrue(html.contains("<h2>内容</h2>")); assertTrue(html.contains("<strong>要点</strong>")); assertTrue(html.contains("&lt;hello&gt;"))
                assertTrue(SmtpSender.markdownHtml("- 条目").contains("<li>条目</li>"))
                assertTrue(results[1].contains("multipart/mixed")); assertTrue(results[1].contains("filename*=UTF-8''%E5%9B%9E%E5%93%8D-2026-10-24.md"))
                assertTrue(results[1].contains(Base64.getEncoder().encodeToString(document().memory!!.getString("markdown").toByteArray())))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun notionDiscoversTitleArchivesPreviousDayAndBatchesBlocksWithCache() = runBlocking {
        Fake().use { f ->
            f.reply("""{"properties":{"名称":{"type":"title"}}}""")
            f.reply("""{"results":[{"id":"old-page"}],"has_more":false}""")
            f.reply("{}"); f.reply("{\"id\":\"new-page\"}"); f.reply("{}")
            val markdown = "# 标题\n- 清单\n> 引用\n" + "长".repeat(2100) + "\n" + (1..92).joinToString("\n") { "段落$it" }
            f.transport.send(PushKind.Notion, PushFormat.Json, notion, document(markdown)).getOrThrow()
            assertEquals("/v1/databases/$database", f.take().path)
            val query = JSONObject(f.take().body.readUtf8()); assertEquals("名称", query.getJSONObject("filter").getString("property"))
            val archive = f.take(); assertEquals("PATCH", archive.method); assertTrue(JSONObject(archive.body.readUtf8()).getBoolean("archived"))
            val create = f.take(); assertEquals("2022-06-28", create.getHeader("Notion-Version"))
            val page = JSONObject(create.body.readUtf8()); assertEquals(database, page.getJSONObject("parent").getString("database_id"))
            assertEquals("回响 · $date", page.getJSONObject("properties").getJSONObject("名称").getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content"))
            val blocks = page.getJSONArray("children"); assertEquals(90, blocks.length())
            assertEquals("heading_1", blocks.getJSONObject(0).getString("type")); assertEquals("bulleted_list_item", blocks.getJSONObject(1).getString("type")); assertEquals("quote", blocks.getJSONObject(2).getString("type"))
            assertEquals(2000, blocks.getJSONObject(3).getJSONObject("paragraph").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content").length)
            val append = f.take(); assertEquals("/v1/blocks/new-page/children", append.path); assertEquals(7, JSONObject(append.body.readUtf8()).getJSONArray("children").length())
            f.reply("{\"id\":\"ordinary\"}"); f.transport.send(PushKind.Notion, PushFormat.Markdown, notion, message).getOrThrow()
            assertEquals("paragraph", JSONObject(f.take().body.readUtf8()).getJSONArray("children").getJSONObject(0).getString("type"))
            assertEquals(6, f.server.requestCount) // 第二次没有重新 GET 属性。
        }
    }
    @Test fun webdavAppendsCreatesDirectoriesAndDailyOverwriteUsesSeparatePath() = runBlocking {
        Fake().use { f ->
            val values = mapOf("account" to "user", "password" to "fake", "folder" to "Obsidian/回响")
            f.server.enqueue(MockResponse().setBody("已有内容\n").setHeader("ETag", "\"v1\""))
            repeat(3) { f.reply(code = 405) }; f.reply(code = 204)
            f.transport.send(PushKind.MarkdownFolder, PushFormat.Markdown, values, message).getOrThrow()
            assertEquals("GET", f.take().method)
            val directories = List(3) { f.take() }; assertTrue(directories.all { it.method == "MKCOL" }); assertEquals("/dav/Obsidian/", directories[0].path)
            val put = f.take(); assertEquals("\"v1\"", put.getHeader("If-Match")); assertTrue(java.net.URLDecoder.decode(put.path!!, "UTF-8").contains("/推送记录/"))
            assertTrue(put.body.readUtf8().startsWith("已有内容\n\n## "))
            f.reply(code = 404); repeat(3) { f.reply(code = 201) }; f.reply(code = 201)
            f.transport.send(PushKind.MarkdownFolder, PushFormat.Text, values, message).getOrThrow()
            repeat(4) { f.take() }; assertEquals("*", f.take().getHeader("If-None-Match"))
            repeat(2) { f.reply(code = 405) }; f.reply(code = 204)
            f.transport.send(PushKind.MarkdownFolder, PushFormat.Json, values, document()).getOrThrow()
            repeat(2) { assertEquals("MKCOL", f.take().method) }
            val daily = f.take(); assertEquals("/dav/Obsidian/回响/$date.md", java.net.URLDecoder.decode(daily.path!!, "UTF-8")); assertEquals(document().memory!!.getString("markdown"), daily.body.readUtf8())
        }
    }
    @Test fun validationRejectsUnsafeInputsButAllowsPrivateWebhookRanges() {
        listOf("10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255", "100.64.0.1", "100.127.255.255", "agent.local", "agent.tail.ts.net").forEach { PushFields.validate(PushKind.Webhook, mapOf("url" to "http://$it/hook")) }
        listOf("example.com", "172.32.0.1", "100.128.0.1", "100.63.0.1", "192.169.1.1", "agent.ts.net.evil.com").forEach { assertTrue(runCatching { PushFields.validate(PushKind.Webhook, mapOf("url" to "http://$it")) }.isFailure) }
        val invalid = listOf(
            PushKind.Feishu to mapOf("webhook" to "https://open.feishu.cn.evil/hook"), PushKind.DingTalk to mapOf("webhook" to "http://oapi.dingtalk.com/hook"),
            PushKind.Telegram to mapOf("token" to "123:abc", "chatId" to "1&x", "baseUrl" to "https://api.telegram.org"),
            PushKind.Telegram to mapOf("token" to "123:abc", "chatId" to "@user", "baseUrl" to "http://proxy.local"),
            PushKind.Email to mapOf("host" to "smtp.qq.com", "port" to "65536", "account" to "a@b.com", "to" to "c@d.com", "password" to "x"),
            PushKind.Email to mapOf("host" to "smtp.qq.com", "account" to "bad\r\nInjected", "to" to "c@d.com", "password" to "x"),
            PushKind.Notion to mapOf("token" to "x", "databaseId" to "not-an-id"),
            PushKind.MarkdownFolder to mapOf("account" to "x", "password" to "x", "folder" to "../secret"),
            PushKind.MarkdownFolder to mapOf("account" to "x", "password" to "x", "folder" to "/absolute"))
        invalid.forEach { (kind, values) -> assertTrue("$kind", runCatching { PushFields.validate(kind, values) }.isFailure) }
        listOf("Host: x", "Content-Length: 0", "X-Resound-Signature: fake", "Authorization: fake", "X-A: 1\nX-a: 2", "X: ok\rBAD", (1..6).joinToString("\n") { "X-$it: a" }, "missing colon").forEach { assertTrue(it, runCatching { PushFields.headers(it) }.isFailure) }
        assertEquals("0123456789abcdef0123456789abcdef", PushFields.databaseId("https://www.notion.so/Name-0123456789abcdef0123456789abcdef?v=other"))
        assertEquals(database, PushFields.databaseId("01234567-89ab-cdef-0123-456789abcdef"))
        assertTrue(PushFields.fields(PushKind.Webhook).single { it.key == "headers" }.secret)
    }
    @Test fun webhookEnvelopeTypesAndIndependentSignatureCoverExactWireBytes() = runBlocking {
        Fake().use { f ->
            val types = mapOf(PushTopic.DailyReview to "daily_review", PushTopic.TodoReminder to "todo_reminder", PushTopic.NewEvent to "new_event", PushTopic.SkillCard to "skill_card", null to "test")
            for ((topic, type) in types) {
                f.reply(code = 202)
                f.transport.send(PushKind.Webhook, PushFormat.Json, hook + mapOf("authorization" to "Bearer fake", "headers" to "X-Agent: home", "signSecret" to "independent-key"), message.copy(topic = topic)).getOrThrow()
                val r = f.take(); val bytes = r.body.readByteArray(); val j = JSONObject(String(bytes, Charsets.UTF_8))
                assertEquals(type, j.getString("type")); assertEquals(1, j.getInt("version")); assertEquals("回响", j.getString("app")); assertEquals("home", r.getHeader("X-Agent"))
                val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec("independent-key".toByteArray(), "HmacSHA256"))
                mac.update((r.getHeader("X-Resound-Timestamp") + ".").toByteArray())
                val signature = mac.doFinal(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("sha256=$signature", r.getHeader("X-Resound-Signature"))
            }
        }
    }
    private fun event(id: Long = 1, start: Long = at) = MemoryEventEntity(id, start, start + 1000, "会议", "讨论方案", "摘要", "[\"要点\"]", "[\"小王\"]", null, null, null, true, false, start, "test")
    private fun card() = SkillCard(1, "meeting", "会议纪要", "meeting", date, at, null, "方案会", "会议摘要",
        listOf(SkillField("points", "要点", FieldType.Bullets, ""), SkillField("todo", "清单", FieldType.Checklist, ""), SkillField("quotes", "原话", FieldType.Quotes, "")),
        mapOf("points" to FieldValue(items = listOf("报价")), "todo" to FieldValue(items = listOf("发送"), checked = listOf(true)), "quotes" to FieldValue(quotes = listOf(CardQuote(1, "我", at, "引用\n下一行")))), listOf(1), at, false)
    @Test fun dailyJsonHasCompleteFieldsAndOptionalLinesSurviveRetrySnapshot() {
        val input = ReviewInput(listOf(event()), listOf(MemoryItemEntity(1, 1, "待办", "我要做", "发报价", null, at, "小王", 1, "Accepted", at)))
        val review = DailyReview(date, "简述", listOf(ReviewSection("一句话", listOf("内容"), listOf(1))), at)
        val lines = listOf(TranscriptLineEntity(1, 1, at, at + 1000, "原句", "speaker1", "我"))
        val j = dailyMemoryJson(date, at, "# 文档", review, input, listOf(card()), lines)
        assertEquals(setOf("type", "version", "app", "date", "generatedAt", "markdown", "data"), j.keys().asSequence().toSet())
        val data = j.getJSONObject("data")
        assertEquals(setOf("review", "events", "items", "cards", "lines"), data.keys().asSequence().toSet())
        assertEquals(setOf("id", "start", "end", "title", "summary", "scene", "people", "important"), data.getJSONArray("events").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("id", "category", "kind", "content", "due", "who", "status"), data.getJSONArray("items").getJSONObject(0).keys().asSequence().toSet())
        assertEquals("会议纪要", data.getJSONArray("cards").getJSONObject(0).getString("skill")); assertEquals("我", data.getJSONArray("lines").getJSONObject(0).getString("speaker"))
        val message = document().copy(memory = j)
        assertEquals(j.toString(), PushMessage.from(message.json()).envelope(PushFormat.Text).toString())
        assertFalse(dailyMemoryJson(date, at, "md", review, input, listOf(card()), null).getJSONObject("data").has("lines"))
    }
    private val quietNotifier = object : Notifier {
        override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {}
    }
    @Test fun diaryAndDailySourceIncludeTypedCardsAndUseReviewCutoffWindow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val zone = ZoneId.systemDefault()
            val cutoff = date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            val start = date.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            db.memory().insertEvent(event(1, start + 1000).copy(title = "前夜在窗口内"))
            db.memory().insertEvent(event(2, start).copy(title = "起点应排除"))
            db.memory().insertEvent(event(3, cutoff + 1).copy(title = "截止后应排除"))
            db.memory().insertEvent(event(4, cutoff).copy(title = "终点应包含"))
            db.memory().insertItems(listOf(MemoryItemEntity(1, 1, "待办", "我要做", "发送报价", null, cutoff, "小王", null, "Accepted", start + 2000)))
            db.capture().segment(SegmentEntity(1, start, cutoff + 1000, "mic", "/fake", 1, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, start + 1000, start + 2000, "窗口内原话", null), TranscriptLineEntity(2, 1, cutoff + 1, cutoff + 2, "窗口外原话", null)))
            db.review().save(ReviewEntity(startDate = "$date", endDate = "$date", brief = "当日简述", sectionsJson = "[]", generatedAt = cutoff, model = "test"))
            val c = card().copy(at = cutoff - 1000)
            db.skills().save(SkillCardRow(skillId = c.skillId, skillVersion = 1, skillName = c.skillName, icon = c.icon, date = date.toEpochDay(), entity = "day", at = c.at, endAt = null, title = c.title, summary = c.summary,
                fieldsJson = SkillJson.fields(c.fields).toString(), valuesJson = SkillJson.values(c.values).toString(), evidenceJson = "[]", generatedAt = cutoff, manual = false))
            val llm = LlmClient(); val settings = AndroidDigestSettings(context, llm)
            val memory = RoomMemoryRepository(db.memory(), DigestRunner(context, db.memory(), llm, settings, { "" }, { false }), { error("不能触发整理") }, scope)
            val reviews = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("diary-review", 0), scope,
                { error("不能调用模型") }, { _, _, _, _ -> error("不能调用模型") }, { "" }, "回响", quietNotifier, {})
            val diary = LocalDiaryRepository(context, memory, reviews, db.memory(), cards = { d -> db.skills().cards(d.toEpochDay()).first().map { it.card() } })
            val md = diary.markdown(date, date, false)
            assertTrue(md.indexOf("## 卡片") > md.indexOf("## 回顾")); assertTrue(md.contains("会议纪要 ·")); assertTrue(md.contains("- 报价")); assertTrue(md.contains("- [x] 发送")); assertTrue(md.contains("> 下一行"))
            val source = DailyMemorySource(context, memory, reviews, db.memory(), db.review(), db.skills())
            val with = source.load(date, true)!!; val without = source.load(date, false)!!
            val data = with.memory!!.getJSONObject("data")
            assertEquals(listOf(1L, 4L), (0 until data.getJSONArray("events").length()).map { data.getJSONArray("events").getJSONObject(it).getLong("id") })
            assertEquals(1, data.getJSONArray("lines").length()); assertFalse(without.memory!!.getJSONObject("data").has("lines"))
            assertTrue(with.memory.getString("markdown").contains("前夜在窗口内")); assertFalse(with.memory.getString("markdown").contains("起点应排除")); assertFalse(with.memory.getString("markdown").contains("窗口外原话"))
            assertFalse(without.memory.getString("markdown").contains("## 原话")); assertTrue(with.memory.getString("markdown").contains("## 卡片"))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
    @Test fun onlySuccessfulScheduledReviewDispatchesSubscribedEnabledMemoryChannels() = runBlocking {
        Fake().use { f ->
            val channels = repo(f)
            val enabled = channels.save(null, PushKind.Webhook, "记忆", hook, setOf(PushTopic.DailyMemory), PushFormat.Json, true).getOrThrow()
            channels.save(null, PushKind.Webhook, "其它", hook, setOf(PushTopic.NewEvent), PushFormat.Json).getOrThrow()
            val disabled = channels.save(null, PushKind.Webhook, "停用", hook, setOf(PushTopic.DailyMemory), PushFormat.Json).getOrThrow(); channels.setEnabled(disabled, false)
            val unsupported = channels.save(null, PushKind.Feishu, "不支持", mapOf("webhook" to "https://open.feishu.cn/hook/fake"), setOf(PushTopic.DailyMemory), PushFormat.Text).getOrThrow()
            assertFalse(channels.channels.first().single { it.id == unsupported }.topics.contains(PushTopic.DailyMemory))
            var loads = 0
            channels.memoryDocument = { day, lines -> assertEquals(date, day); assertTrue(lines); loads++; document() }
            val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val zone = ZoneId.systemDefault(); val cutoff = date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            var fail = false
            try {
                db.memory().insertEvent(event(start = cutoff - 1000))
                val reviews = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("scheduled-review", 0), scope,
                    { LlmPreset("fake", "fake", "https://unused.invalid", "fake") to "fake" },
                    { _, _, _, _ -> if (fail) error("model failed") else LlmReply("""{"brief":"简述","sections":[{"title":"一句话","lines":[{"text":"内容","eventId":1}]},{"title":"发生了什么","lines":[{"text":"内容","eventId":1}]}]}""", 1, 1) },
                    { "" }, "回响", quietNotifier, {}, { cutoff }, { zone }, channels::dispatchDailyMemory)
                reviews.setPushEnabled(false) // 每日文档由通道订阅控制，独立于本地回顾通知开关。
                reviews.run(date, false); assertEquals(0, loads)
                f.reply(code = 204); reviews.run(date, true)
                assertEquals(1, loads); assertEquals("daily_memory", JSONObject(f.take().body.readUtf8()).getString("type"))
                assertEquals(enabled, channels.logs().first().single().channelId)
                fail = true
                db.memory().insertEvent(event(2, cutoff + 1000)); reviews.run(date.plusDays(1), true)
                assertEquals(1, loads); assertNotNull(reviews.state.value.lastError)
                db.memory().deleteEvent(1); reviews.run(date, true) // 删空后也不推旧回顾。
                assertEquals(1, loads); assertEquals(1, f.server.requestCount)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
        }
    }
    @Test fun dailyTestPersistsOptionsSecretsAndRetriesOriginalDocumentWithoutRereading() = runBlocking {
        Fake().use { f ->
            val secrets = Secrets(); val queue = mutableListOf<String>(); val channels = repo(f, secrets, queue)
            val values = hook + mapOf("headers" to "X-Private: fake-header-secret", "signSecret" to "fake-sign-secret", "authorization" to "Bearer fake-auth")
            val id = channels.save(null, PushKind.Webhook, "文档 fake-header-secret", values, setOf(PushTopic.DailyMemory), PushFormat.Json, true).getOrThrow()
            assertFalse(channels.channels.first().single().name.contains("fake-header-secret"))
            channels.save(id, PushKind.Webhook, "文档", hook, setOf(PushTopic.DailyMemory), PushFormat.Json, true).getOrThrow()
            val restored = repo(f, secrets, queue)
            assertTrue(restored.channels.first().single().memoryWithLines)
            val prefs = context.getSharedPreferences("more-push", 0)
            assertFalse(prefs.all.toString().contains("fake-header-secret")); assertFalse(prefs.all.toString().contains("fake-sign-secret"))
            restored.setEnabled(id, false)
            var reads = 0
            restored.memoryDocument = { day, lines -> assertNull(day); assertTrue(lines); reads++; document("# 最近一天\n文档") }
            f.reply("fake-header-secret reflected", 503)
            assertTrue(restored.test(id).isFailure); f.take()
            assertEquals(1, reads); assertEquals(1, queue.size)
            restored.memoryDocument = { _, _ -> error("重试不得读成另一天") }
            f.reply(code = 204); assertFalse(restored.retry(queue.single(), 0))
            val retried = JSONObject(f.take().body.readUtf8()); assertEquals("# 最近一天\n文档", retried.getString("markdown")); assertEquals("$date", retried.getString("date"))
            assertTrue(restored.logs().first().first().ok); assertEquals("每日记忆文档 · $date", restored.logs().first().first().title)
            assertFalse(restored.logs().first().toString().contains("fake-header-secret"))
            assertTrue(context.getSharedPreferences("more-outbox", 0).all.isEmpty())
            restored.setEnabled(id, true)
            restored.memoryDocument = { _, _ -> error("模拟本地读取暂时失败") }
            restored.dispatchDailyMemory(date)
            assertEquals(2, queue.size)
            restored.memoryDocument = { day, lines -> assertEquals(date, day); assertTrue(lines); document() }
            f.reply(code = 204); assertFalse(restored.retry(queue.last(), 0))
            assertEquals("$date", JSONObject(f.take().body.readUtf8()).getString("date"))
            assertTrue(context.getSharedPreferences("more-outbox", 0).all.isEmpty())
        }
    }
}
