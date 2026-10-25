package app.resound.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import app.resound.data.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.*
import java.util.Collections
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Subscription042Test {
    init { app.resound.appDisplayName = "涛声" }
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 24)
    private val at = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val subscription = PushSubscription.ForAgents.copy(backfillDays = null, fullCheckAt = null)
    private val folder = mapOf("url" to "https://dav.example.test/dav/", "account" to "test@example.test", "password" to "fake-password", "folder" to "Resound-Memory")
    private val hook = mapOf("url" to "https://example.test/hook", "signSecret" to "fake-sign")
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
    private fun event(id: Long = 1, timestamp: Long = at, title: String = "讨论测试方案") = MemoryEventEntity(id, timestamp, timestamp + 60_000, "工作", title, "确定虚构样例的验证范围", "[\"采用模拟服务\"]", "[\"我\",\"小林\"]", null, null, null, true, false, timestamp, "test", true, timestamp)
    private fun item(id: Long = 1, category: String = "灵感", timestamp: Long = at) = MemoryItemEntity(id, 1, category, null, "测试条目 $id", null, null, null, null, "Accepted", timestamp)
    private fun line(id: Long, speaker: String, eventId: Long? = 1) = TranscriptLineEntity(id, 1, at, at + 1000, "虚构测试原话 $id", "speaker", speaker, eventId)
    private fun card() = SkillCardRow(1, "test", 1, "测试卡片", "card", date.toEpochDay(), "day", at, null, "测试清单", "测试摘要",
        SkillJson.fields(listOf(SkillField("list", "检查", FieldType.Checklist, ""))).toString(),
        SkillJson.values(mapOf("list" to FieldValue(items = listOf("模拟服务"), checked = listOf(true)))).toString(), "[1]", at, false)
    private fun fixture() = PushSnapshot(listOf(event()), listOf(item(), item(2, "待办").copy(dueAt = at)), listOf(card()),
        listOf(ReviewEntity(1, "daily", "$date", "$date", "完成测试方案讨论", "[]", at, "test")), listOf(line(1, "我")), mapOf(1L to 3))
    private class Secrets : PushSecrets {
        val data = mutableMapOf<String, String>()
        override fun read(ref: String) = data[ref].orEmpty()
        override fun write(ref: String, value: String) { data[ref] = value }
        override fun remove(ref: String) { data.remove(ref) }
    }
    private class Server : AutoCloseable {
        val files = Collections.synchronizedMap(mutableMapOf<String, String>())
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        var failNextPut = false
        var failHook = false
        val server = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val method = request.method
                if (path.startsWith("/v1/")) return when {
                    method == "GET" -> response("{\"properties\":{\"Name\":{\"type\":\"title\"}}}")
                    path.endsWith("/query") -> response("{\"results\":[{\"id\":\"old-page\"}],\"has_more\":false}")
                    else -> response("{\"id\":\"new-page\"}")
                }
                if (path == "/hook") return MockResponse().setResponseCode(if (failHook) 503 else 204)
                if (path.contains("/sendDocument")) return response("{\"ok\":true}")
                return when (method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "HEAD" -> MockResponse().setResponseCode(if (files.containsKey(path)) 200 else 404)
                    "PUT" -> if (failNextPut) { failNextPut = false; MockResponse().setResponseCode(503) } else {
                        files[path] = request.body.clone().readUtf8(); MockResponse().setResponseCode(201)
                    }
                    "DELETE" -> { files.remove(path); MockResponse().setResponseCode(204) }
                    "MOVE" -> {
                        val target = HttpUrl.Builder().scheme("https").host("unused.test").build().resolve(request.getHeader("Destination")!!)!!.encodedPath
                        if (files.containsKey(target)) MockResponse().setResponseCode(409) else {
                            files[target] = files.remove(path) ?: ""; MockResponse().setResponseCode(201)
                        }
                    }
                    else -> response("{}")
                }
            }
            fun response(body: String) = MockResponse().setResponseCode(200).setBody(body)
        }; start() }
        val originals = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            originals += chain.request()
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val transport = PushTransport(client)
        override fun close() { server.shutdown() }
        fun puts() = requests.count { it.method == "PUT" }
    }
    private fun repo(server: Server, secrets: Secrets = Secrets(), settings: android.content.SharedPreferences = prefs(), directory: File = temp.newFolder()) =
        AndroidPushChannels(settings, prefs(), secrets, server.transport, {}, stateDirectory = directory)
    private fun channel(kind: PushKind = PushKind.Webhook, sub: PushSubscription = subscription) = PushChannel(1, kind, "测试", true, Subscriptions.topics(sub), PushFormat.Markdown, "", subscription = sub)
    private fun runtime(server: Server, ch: PushChannel, time: () -> Long = { at }, queued: MutableList<Long> = mutableListOf(), directory: File = temp.newFolder()): SubscriptionRuntime =
        SubscriptionRuntime(prefs(), directory, { ch }, { ch to if (ch.kind == PushKind.MarkdownFolder) folder else if (ch.kind == PushKind.Notion) mapOf("token" to "test", "databaseId" to "1".repeat(32)) else hook },
            server.transport, { _, whenAt, _ -> queued += whenAt }, { _, _, _ -> }, time, { zone })

    @Test fun migrationPersistsThreeLegacyCases() = runBlocking {
        Server().use { f ->
            val p = prefs()
            val rows = JSONArray()
            listOf(setOf(PushTopic.DailyMemory), setOf(PushTopic.DailyMemory, PushTopic.DailyReview), setOf(PushTopic.SkillCard, PushTopic.TodoReminder)).forEachIndexed { i, topics ->
                rows.put(JSONObject().put("id", i + 1).put("kind", "Webhook").put("name", "旧通道$i").put("enabled", true).put("format", "Json")
                    .put("topics", JSONArray(topics.map { it.name })).put("memoryWithLines", i == 1).put("values", JSONObject(hook)).put("secrets", JSONObject()))
            }
            p.edit().putString("channels", rows.toString()).commit()
            val channels = repo(f, settings = p).channels.first()
            assertEquals(RecordKind.entries.toSet(), channels[0].subscription.records)
            assertEquals(LinesMode.Off, channels[0].subscription.lines)
            assertEquals(LinesMode.All, channels[1].subscription.lines)
            assertEquals(RecordTrigger.Schedule(listOf(1320)), channels[1].subscription.trigger)
            assertFalse(channels[1].subscription.incremental)
            assertTrue(channels[2].subscription.records.isEmpty()); assertTrue(channels[2].subscription.summaries); assertTrue(channels[2].subscription.reminders)
            assertTrue(JSONArray(p.getString("channels", "[]")).getJSONObject(0).has("subscription"))
        }
    }
    @Test fun capabilitiesTightenAndValidateRoundTrip() = runBlocking {
        Server().use { f ->
            val p = prefs(); val secrets = Secrets(); val r = repo(f, secrets, p)
            r.saveWithSubscription(null, PushKind.PushPlus, "聊天", mapOf("token" to "fake"), subscription.copy(lines = LinesMode.All), PushFormat.Json, false).getOrThrow()
            val c = repo(f, secrets, p).channels.first().single()
            assertTrue(c.subscription.records.isEmpty()); assertEquals(LinesMode.Off, c.subscription.lines); assertFalse(c.subscription.incremental)
            assertEquals(PushFormat.Text, c.format)
            for (invalid in listOf(subscription.copy(trigger = RecordTrigger.OnChange(4)), subscription.copy(trigger = RecordTrigger.Schedule(listOf(1440))), subscription.copy(fullCheckAt = -1))) {
                assertTrue(r.saveWithSubscription(null, PushKind.Webhook, "无效", hook, invalid, PushFormat.Json, false).isFailure)
            }
            val custom = subscription.copy(lines = LinesMode.MeOnly, trigger = RecordTrigger.Schedule(listOf(500, 1320), setOf(1, 7)), rangeDays = 17, backfillDays = 0, excludeMedia = false, layout = FolderLayout.ByDay)
            assertEquals(custom, Subscriptions.read(Subscriptions.json(custom)))
        }
    }
    @Test fun linkedCredentialsAreLiveNotCopiedAndFolderConflictRejected() = runBlocking {
        Server().use { f ->
            val p = prefs(); val secrets = Secrets(); val r = repo(f, secrets, p)
            val sub = subscription.copy(trigger = RecordTrigger.Manual)
            assertEquals("先在数据备份里连接坚果云，或改为单独配置", r.saveWithSubscription(null, PushKind.MarkdownFolder, "资料", emptyMap(), sub, PushFormat.Markdown, true).exceptionOrNull()?.message)
            var credentials = folder - "folder"
            val accounts = MutableStateFlow<String?>(credentials["account"])
            r.linkedCredentials = { credentials }; r.linkedAccount = accounts
            val id = r.saveWithSubscription(null, PushKind.MarkdownFolder, "资料", emptyMap(), sub, PushFormat.Markdown, true).getOrThrow()
            assertTrue(secrets.data.isEmpty()); assertFalse(p.all.toString().contains("fake-password"))
            assertEquals(accounts.value, r.linkedNutstoreAccount.first())
            assertEquals("这个文件夹已经给“资料”通道用了，换个名字", r.saveWithSubscription(null, PushKind.MarkdownFolder, "重复", folder, sub, PushFormat.Markdown, false).exceptionOrNull()?.message)
            credentials = credentials + ("password" to "changed-secret"); accounts.value = "new@example.test"
            assertEquals("new@example.test", r.linkedNutstoreAccount.first())
            r.subscriptions.source = { _, _ -> fixture() }
            r.pushNow(id).getOrThrow()
            assertTrue(f.originals.all { it.header("Authorization") == Credentials.basic(credentials.getValue("account"), "changed-secret", Charsets.UTF_8) })
            r.linkedCredentials = { null }
            assertTrue(r.pushNow(id).isFailure); assertNotNull(r.status(id).first().lastError)
        }
    }
    @Test fun filenamesUseEnglishWeekdayAndIsoWeekYear() {
        assertEquals("daily/2026-10/2026-10-24-Sat_daily-record.md", MemoryFolder.dailyPath(date))
        assertEquals("transcripts/2026-10/2026-10-24-Sat_transcript.md", MemoryFolder.transcriptPath(date))
        assertEquals("weekly/2020-W53_1228-0103_weekly-review.md", MemoryFolder.weekPath(LocalDate.of(2021, 1, 1)))
        assertEquals("weekly/2026-W44_1026-1101_weekly-review.md", MemoryFolder.weekPath(LocalDate.of(2026, 11, 1)))
    }
    @Test fun selectionFrontMatterTypedCardsAndMeOnlyNeverLeakMedia() {
        val media = event(2).copy(scene = "媒体", starred = true)
        val data = fixture().copy(events = listOf(event(), media), cards = listOf(card().copy(evidenceJson = "[10]")), lines = listOf(line(1, "我"), line(2, "别人"), line(3, "我", 2), line(10, "别人", 1)))
        val s = subscription.copy(lines = LinesMode.MeOnly)
        val selected = data.select(s, null, date, zone)
        assertEquals(listOf(1L), selected.events.map { it.id }); assertEquals(listOf(1L), selected.lines.map { it.id })
        val files = MemoryFolder(date, zone).generate(selected, s)
        val daily = files.getValue(MemoryFolder.dailyPath(date))
        assertTrue(daily.startsWith("---\n类型:")); assertTrue(daily.contains("来源: 涛声自动生成，请勿手动修改（改了会被覆盖）"))
        assertTrue(daily.contains("# 2026年10月24日 星期六 · 讨论测试方案等 1 件事"))
        assertTrue(daily.contains("E1")); assertTrue(daily.contains("说过 3 次")); assertTrue(daily.contains("进行中")); assertTrue(daily.contains("- [x] 模拟服务"))
        assertFalse(daily.contains("虚构测试原话")); assertTrue(daily.contains("[[transcripts/"))
        assertFalse(selected.select(s.copy(rangeDays = 1), date.plusDays(1), date.plusDays(1), zone).events.isNotEmpty())
    }
    @Test fun yearlyArchiveByDayAndSampleOutput() {
        val oldAt = date.minusYears(2).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val data = fixture().copy(items = fixture().items + item(3, timestamp = oldAt) + item(4, "知识", oldAt),
            reviews = fixture().reviews + ReviewEntity(2, "weekly", "2026-10-19", "2026-10-25", "虚构周回顾", "[]", at, "test"))
        val sampleSubscription = subscription.copy(lines = LinesMode.All)
        val files = MemoryFolder(date, zone).generate(data.select(sampleSubscription, null, date, zone), sampleSubscription)
        assertTrue(files.getValue("archive/2024/ideas.md").contains("I3")); assertFalse(files.getValue("ideas.md").contains("I3"))
        assertTrue(files.containsKey("archive/2024/decisions-and-facts.md"))
        assertTrue(files.getValue(MemoryFolder.weekPath(date)).contains("2026年第43周（10月19日—10月25日）"))
        val byDay = MemoryFolder(date, zone).generate(data, subscription.copy(layout = FolderLayout.ByDay))
        assertTrue(byDay.keys.all { it == "README.md" || it.startsWith("daily/") || it.startsWith("weekly/") || it.startsWith("transcripts/") })
        val output = File("build/outputs/042-sample/Resound-Memory")
        files.forEach { (path, text) -> File(output, path).apply { parentFile!!.mkdirs(); writeText(text.replace(MemoryFolder.UPDATED, "2026-10-24 10:00")) } }
        File(output, "inbox").mkdirs()
    }
    @Test fun folderDiffDeletionAndUnknownFilesAreProtected() {
        Server().use { f ->
            val state = PushSyncState(File(temp.newFolder(), "1.json"))
            val sync = FolderSync(FolderRemote(folder, PushHttp(f.client)), state)
            val generator = MemoryFolder(date, zone)
            val first = generator.generate(fixture(), subscription)
            sync.run(first, true, at); val original = f.puts()
            assertEquals(0, sync.run(first, true, at + 60_000).updated); assertEquals(original, f.puts())
            f.files["/dav/Resound-Memory/my-notes/mine.md"] = "用户内容"; f.files["/dav/Resound-Memory/unknown.md"] = "未知文件"
            val edited = generator.generate(fixture().copy(events = listOf(event(title = "修改后的标题"))), subscription)
            val delta = sync.run(edited, true, at + 120_000)
            assertTrue(delta.updated in 1 until first.size)
            val empty = generator.generate(PushSnapshot(), subscription)
            sync.run(empty, true, at + 180_000)
            assertFalse(f.files.containsKey("/dav/Resound-Memory/${MemoryFolder.dailyPath(date)}"))
            assertEquals("用户内容", f.files["/dav/Resound-Memory/my-notes/mine.md"]); assertEquals("未知文件", f.files["/dav/Resound-Memory/unknown.md"])
            assertFalse(f.requests.any { it.path!!.contains("my-notes") || it.path!!.contains("unknown.md") })
        }
    }
    @Test fun atomicMoveDeletesExistingTargetAndRebuildBatchesTwenty() {
        Server().use { f ->
            val stateFile = File(temp.newFolder(), "1.json")
            val state = PushSyncState(stateFile)
            val files = (0L..24L).associate { MemoryFolder.dailyPath(date.minusDays(it)) to "test $it" }
            val sync = FolderSync(FolderRemote(folder, PushHttp(f.client)), state)
            assertTrue(sync.run(files, false, at).more); assertEquals(20, f.puts())
            assertFalse(FolderSync(FolderRemote(folder, PushHttp(f.client)), PushSyncState(stateFile)).run(files, false, at).more)
            assertEquals(25, f.puts())
            val target = files.keys.first(); val before = f.requests.size
            val restored = PushSyncState(stateFile)
            FolderSync(FolderRemote(folder, PushHttp(f.client)), restored).run(files + (target to "changed"), false, at)
            val methods = f.requests.drop(before).filter { it.method != "MKCOL" }.map { it.method }
            assertEquals(listOf("PUT", "HEAD", "DELETE", "MOVE"), methods)
            restored.json.put("rebuilding", true); restored.save()
            val rebuilding = FolderSync(FolderRemote(folder, PushHttp(f.client)), restored)
            assertTrue(rebuilding.run(files, false, at).more)
            assertTrue(rebuilding.run(files, false, at).more)
            assertFalse(rebuilding.run(files, false, at).more)
            assertEquals(25, restored.files.length()); assertEquals(51, f.puts())
        }
    }
    @Test fun throttlingStopsImmediatelyAndResumesWithoutAdvancingFingerprint() {
        Server().use { f ->
            val stateFile = File(temp.newFolder(), "1.json"); val state = PushSyncState(stateFile)
            val files = mapOf("README.md" to "test", "ideas.md" to "ideas")
            f.failNextPut = true
            assertTrue(runCatching { FolderSync(FolderRemote(folder, PushHttp(f.client)), state).run(files, false, at) }.isFailure)
            assertEquals("PUT", f.requests.last().method); assertEquals(0, state.files.length())
            val fresh = PushSyncState(stateFile)
            assertEquals(2, FolderSync(FolderRemote(folder, PushHttp(f.client)), fresh).run(files, false, at).updated)
            assertFalse(fresh.json.has("temporary"))
            assertEquals(sha256("test"), fresh.files.getJSONObject("README.md").getString("sha256"))
        }
    }
    @Test fun schedulingCombinesMinimumGapMultipleTimesWeekdaysAndManual() = runBlocking {
        assertEquals(at + 300_000, Subscriptions.changeAt(RecordTrigger.OnChange(5), at, at + 1000))
        assertNull(Subscriptions.changeAt(RecordTrigger.Manual, at, at))
        val monday = ZonedDateTime.of(2026, 10, 26, 8, 0, 0, 0, zone)
        val schedule = RecordTrigger.Schedule(listOf(480, 1200), setOf(1, 3))
        assertEquals(monday.withHour(20).toInstant().toEpochMilli(), Subscriptions.nextSchedule(schedule, monday))
        assertEquals(monday.plusDays(2).toInstant().toEpochMilli(), Subscriptions.nextSchedule(schedule, monday.withHour(20)))
        val work = RecordPushScheduler.request(1, at)
        assertEquals(NetworkType.CONNECTED, work.workSpec.constraints.requiredNetworkType); assertEquals(BackoffPolicy.EXPONENTIAL, work.workSpec.backoffPolicy)
        Server().use { f ->
            var clock = at; val queue = mutableListOf<Long>(); val c = channel(sub = subscription.copy(trigger = RecordTrigger.OnChange(5)))
            val r = runtime(f, c, { clock }, queue); r.source = { _, _ -> fixture() }
            r.configure(c, true); r.scheduled(1); queue.clear()
            clock += 1000; r.changed(c); assertEquals(at + 300_000, queue.last())
            clock += 1000; r.changed(c); assertEquals(1, queue.size)
            val manualQueue = mutableListOf<Long>(); val manual = channel(sub = subscription.copy(trigger = RecordTrigger.Manual, fullCheckAt = 1320))
            val m = runtime(f, manual, queued = manualQueue); m.configure(manual, true); m.changed(manual)
            assertTrue(manualQueue.isEmpty())
            val reminder = channel(sub = PushSubscription(summaries = false, reminders = true, trigger = RecordTrigger.Manual, fullCheckAt = null))
            val remindQueue = mutableListOf<Long>(); clock = at
            val rr = runtime(f, reminder, { clock }, remindQueue)
            val dueData = fixture().copy(items = listOf(item(2, "待办").copy(dueAt = at + 60_000)))
            rr.source = { _, _ -> dueData }; rr.configure(reminder, true); rr.scheduled(1)
            assertEquals(at + 60_000, remindQueue.last())
            clock += 60_000; rr.scheduled(1)
            assertEquals("todo_reminder", JSONObject(f.requests.last().body.clone().readUtf8()).getString("type"))
            assertTrue(f.requests.last().body.clone().readUtf8().contains("待办到期"))
            val sent = f.requests.size; rr.scheduled(1); assertEquals(sent, f.requests.size)
            clock = at + 3 * 86_400_000L; rr.scheduled(1)
            assertTrue(f.requests.last().body.clone().readUtf8().contains("重点三天没进展"))
            val progress = dueData.copy(items = listOf(dueData.items.single().copy(doneAt = at + 1000, statusChangedAt = at + 1000, status = "Done")))
            assertTrue(SubscriptionReminders.candidates(progress, reminder.subscription, JSONObject()).isEmpty())
            val folderReminder = reminder.copy(kind = PushKind.MarkdownFolder)
            val fr = runtime(f, folderReminder, { clock }); fr.source = { _, _ -> dueData }
            val puts = f.puts(); fr.configure(folderReminder, true); assertFalse(fr.scheduled(1))
            assertTrue(f.files.containsKey("/dav/Resound-Memory/README.md")); assertEquals(2, f.puts() - puts)
        }
    }
    @Test fun webhookDeltaSignsUpsertsDeletesAndTracksFailureStatus() = runBlocking {
        Server().use { f ->
            var snapshot = fixture()
            val r = runtime(f, channel()); r.source = { _, _ -> assertTrue(r.status(1).value.running); snapshot }
            assertTrue(r.run(1).isSuccess)
            val first = JSONObject(f.requests.last().body.clone().readUtf8()); assertEquals("records_delta", first.getString("type")); assertEquals(1, first.getInt("version"))
            assertTrue(first.getJSONArray("changes").objects().all { it.getString("op") == "upsert" })
            val original = f.originals.last(); assertTrue(original.header("X-Resound-Signature")!!.startsWith("sha256="))
            val count = f.requests.size; r.run(1).getOrThrow(); assertEquals(count, f.requests.size)
            snapshot = PushSnapshot(); f.failHook = true
            assertTrue(r.run(1).isFailure); assertFalse(r.status(1).value.running); assertNotNull(r.status(1).value.lastError)
            f.failHook = false; r.run(1).getOrThrow()
            assertTrue(JSONObject(f.requests.last().body.clone().readUtf8()).getJSONArray("changes").objects().all { it.getString("op") == "delete" })
            assertNull(r.status(1).value.lastError); assertTrue(r.status(1).value.lastResult!!.contains("变化"))
        }
    }
    @Test fun notionOnlyUpdatesChangedDayAndArchivesDeletedDay() = runBlocking {
        Server().use { f ->
            var snapshot = PushSnapshot(events = listOf(event(), event(2, at - 86_400_000)))
            val r = runtime(f, channel(PushKind.Notion)); r.source = { _, _ -> snapshot }
            r.run(1).getOrThrow(); assertEquals(2, f.requests.count { it.path == "/v1/pages" })
            val count = f.requests.size; r.run(1).getOrThrow(); assertEquals(count, f.requests.size)
            snapshot = snapshot.copy(events = listOf(event(title = "变化"), event(2, at - 86_400_000)))
            r.run(1).getOrThrow(); assertEquals(3, f.requests.count { it.path == "/v1/pages" })
            snapshot = snapshot.copy(events = listOf(snapshot.events.first()))
            val before = f.requests.size; r.run(1).getOrThrow()
            val requests = f.requests.drop(before)
            assertTrue(requests.any { it.method == "PATCH" && it.body.clone().readUtf8().contains("archived") })
            assertFalse(requests.any { it.path == "/v1/pages" })
        }
    }
    @Test fun summaryDoesNotBypassManualRecordsAndRebuildBackfills() = runBlocking {
        Server().use { f ->
            var snapshot = fixture(); var clock = at
            val c = channel(PushKind.MarkdownFolder, subscription.copy(trigger = RecordTrigger.Manual))
            val queue = mutableListOf<Long>(); val r = runtime(f, c, { clock }, queue)
            r.source = { _, _ -> snapshot }
            r.configure(c, true); r.run(1).getOrThrow()
            snapshot = snapshot.copy(events = listOf(event(title = "尚未手动推送的新标题")), reviews = listOf(snapshot.reviews.first().copy(brief = "新回顾")))
            clock += 1000; r.changed(c, summary = true); assertFalse(r.scheduled(1))
            val body = f.files.getValue("/dav/Resound-Memory/${MemoryFolder.dailyPath(date)}")
            assertTrue(body.contains("新回顾")); assertFalse(body.contains("尚未手动推送的新标题")); assertTrue(body.contains("讨论测试方案"))
            r.run(1, rebuild = true).getOrThrow()
            assertTrue(f.files.getValue("/dav/Resound-Memory/${MemoryFolder.dailyPath(date)}").contains("尚未手动推送的新标题"))
            val state = PushSyncState(File(temp.newFolder(), "state.json"))
            assertEquals(date, state.since(subscription.copy(backfillDays = 0), date))
            assertEquals(date, state.since(subscription.copy(backfillDays = 30), date.plusDays(1)))
            assertEquals(date.minusDays(29), state.since(subscription.copy(backfillDays = 30), date, true))
        }
    }
    @Test fun roomSnapshotHonorsMergePrivacyAndDeletedCards() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        try {
            db.memory().insertEvent(event()); db.memory().insertEvent(event(2).copy(scene = "媒体"))
            db.memory().insertItems(listOf(item(), item(2).copy(mergedInto = 1)))
            db.capture().segment(SegmentEntity(1, at, at + 1000, "test", "/test", 1, "Done"))
            db.transcribe().insertLines(listOf(line(1, "我"), line(2, "我", 2)))
            db.skills().save(card()); db.skills().save(card().copy(id = 2, deletedAt = at))
            val s = subscription.copy(lines = LinesMode.MeOnly)
            val first = db.push().snapshot(Long.MIN_VALUE, true).select(s, null, date, zone)
            assertEquals(1, first.items.size); assertEquals(2, first.repeats[1L]); assertEquals(1, first.cards.size); assertEquals(listOf(1L), first.lines.map { it.id })
            db.memory().deleteEvent(1)
            val after = db.push().snapshot(Long.MIN_VALUE, true).select(s, null, date, zone)
            assertTrue(after.events.isEmpty()); assertTrue(after.lines.isEmpty()); assertTrue(after.cards.isEmpty())
        } finally { db.close() }
    }
    @Test fun fullWebhookAndTelegramUseDailyMemoryWithMarkdownAttachment() = runBlocking {
        Server().use { f ->
            val r = runtime(f, channel(sub = subscription.copy(incremental = false)))
            r.source = { _, _ -> fixture() }; r.run(1).getOrThrow()
            val payload = JSONObject(f.requests.last().body.clone().readUtf8())
            assertEquals("daily_memory", payload.getString("type")); assertTrue(payload.getJSONObject("data").has("review"))
            assertFalse(payload.getJSONObject("data").has("lines"))
            f.transport.send(PushKind.Telegram, PushFormat.Text, mapOf("token" to "123:fake", "chatId" to "123"),
                PushMessage("文档", "摘要", PushTopic.DailyMemory, null, at, memory = payload)).getOrThrow()
            assertTrue(f.requests.last().path!!.endsWith("/sendDocument")); assertTrue(f.requests.last().body.clone().readUtf8().contains("filename=\"涛声-2026-10-24.md\""))
        }
    }
}
