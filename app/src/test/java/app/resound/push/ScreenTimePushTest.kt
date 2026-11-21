package app.resound.push

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.AppGraph
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.ReviewEntity
import app.resound.diagnostics.FailureNotes
import app.resound.screentime.CoverageGap
import app.resound.screentime.DevicePlayback
import app.resound.screentime.GapCode
import app.resound.screentime.NamedAppSession
import app.resound.screentime.PLAYBACK_SOURCE
import app.resound.screentime.SCREEN_SOURCE
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeResult
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ScreenTimePushTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 14)
    private val at = 1_794_657_600_000L
    private fun t(hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime.of(2026, 11, 14, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    private val transport = PushTransport(now = { at })
    private fun Request.text() = Buffer().also { body!!.writeTo(it) }.readUtf8()

    private fun sampleResult() = ScreenTimeResult(
        enabled = true,
        sessions = listOf(NamedAppSession("tv.danmaku.bili", "哔哩哔哩", t(17, 11, 46), t(20, 23, 46))),
        coverageUntil = t(22, 0),
        gaps = listOf(
            CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, t(21, 0), t(22, 0), "等待下一次采集"),
            CoverageGap(GapCode.NOTIFICATION_ACCESS_MISSING, PLAYBACK_SOURCE, t(0, 0), t(22, 0), "设置 → 通知使用权 → 声声不息 开启"),
        ),
        devicePlayback = listOf(
            DevicePlayback("app.podcast.cosmos", "小宇宙", "271.两眼一抹黑，试试也不亏", "…", t(8, 2, 10), t(8, 47, 55)),
        ),
    )

    @Test fun webhookJsonMatchesSampleBytesAndHeaders() = runBlocking {
        val message = buildScreenTimeMessage(date, at, zone, ScreenTimeReader { _, _ -> sampleResult() })!!
        val request = transport.request(PushKind.Webhook, PushFormat.Json, mapOf("url" to "https://example.com/hook"), message)
        val expected = """{"title":"今日屏幕时间 3 小时 12 分","body":"哔哩哔哩 192 分\n设备播放记录：\n小宇宙《271.两眼一抹黑，试试也不亏》 46 分\n部分时段没有数据：等待下一次采集；设置 → 通知使用权 → 声声不息 开启","topic":"ScreenTime","deepLink":null,"at":1794657600000,"screenTime":{"schemaVersion":1,"date":"2026-11-14","coverageUntil":"2026-11-14T22:00:00+08:00","totalMinutes":192,"apps":[{"package":"tv.danmaku.bili","name":"哔哩哔哩","minutes":192}],"timeline":[{"package":"tv.danmaku.bili","name":"哔哩哔哩","start":"2026-11-14T17:11:46+08:00","end":"2026-11-14T20:23:46+08:00"}],"devicePlayback":[{"package":"app.podcast.cosmos","name":"小宇宙","title":"271.两眼一抹黑，试试也不亏","subtitle":"…","start":"2026-11-14T08:02:10+08:00","end":"2026-11-14T08:47:55+08:00"}],"gaps":[{"code":"coverage_lag","source":"screen","from":"2026-11-14T21:00:00+08:00","until":"2026-11-14T22:00:00+08:00","fix":"等待下一次采集"},{"code":"notification_access_missing","source":"playback","from":"2026-11-14T00:00:00+08:00","until":"2026-11-14T22:00:00+08:00","fix":"设置 → 通知使用权 → 声声不息 开启"}]},"type":"screen_time","version":1,"app":"resound"}"""
        assertEquals(expected, request.text())
        assertEquals("screen_time", request.header("X-Resound-Type"))
        assertEquals("2026-11-14", request.header("X-Resound-Date"))
        val markdown = transport.request(PushKind.Webhook, PushFormat.Markdown, mapOf("url" to "https://example.com/hook"), message).text()
        assertFalse(markdown.contains("screenTime"))
        assertTrue(markdown.contains("今日屏幕时间 3 小时 12 分"))
    }

    @Test fun snapshotRoundTripKeepsScreenTimeAndOldSnapshotOmitsIt() = runBlocking {
        val message = buildScreenTimeMessage(date, at, zone, ScreenTimeReader { _, _ -> sampleResult() })!!
        val restored = PushMessage.from(message.json())
        assertEquals(message.screenTime!!.toString(), restored.screenTime!!.toString())
        assertEquals("2026-11-14", restored.screenTime!!.getString("date"))
        val request = transport.request(PushKind.Webhook, PushFormat.Json, mapOf("url" to "https://example.com/hook"), restored)
        assertEquals("2026-11-14", request.header("X-Resound-Date"))
        assertEquals("screen_time", request.header("X-Resound-Type"))
        val old = PushMessage("标题", "正文", PushTopic.DailyReview, null, at)
        val parsed = PushMessage.from(old.json())
        assertNull(parsed.screenTime)
        assertFalse(old.json().has("screenTime"))
    }

    @Test fun dispatchSkipsWhenReaderNullOrDisabledAndDoesNotMarkSent() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val repo = repo(server)
            val id = repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val after = t(22, 1)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, null) }
            assertEquals(0, server.requestCount)
            val skipped = repo.dispatchScreenTime(after, zone)
            assertEquals(0, server.requestCount)
            assertNull(prefs.getString("screen-time-sent-$id", null))
            assertTrue("跳过原因要可见：${FailureNotes.dump()}", FailureNotes.dump().contains("push/screen-time-skip"))
            val tomorrow = date.plusDays(1).atStartOfDay(zone).plusMinutes(22 * 60L).toInstant().toEpochMilli()
            assertEquals(tomorrow, skipped)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ -> ScreenTimeResult.DISABLED }) }
            assertEquals(tomorrow, repo.dispatchScreenTime(after, zone))
            assertEquals(0, server.requestCount)
            assertNull(prefs.getString("screen-time-sent-$id", null))
            assertTrue("采集关闭跳过也要可见：${FailureNotes.dump()}", FailureNotes.dump().contains("push/screen-time-skip"))
        }
    }

    @Test fun unauthorizedStillSendsUsageAccessMissingGap() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val repo = repo(server)
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val unauthorized = ScreenTimeResult(
                enabled = true, sessions = emptyList(), coverageUntil = null,
                gaps = listOf(CoverageGap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, t(0, 0), t(22, 0), "设置 → 使用情况访问 → 声声不息 开启")),
            )
            val after = t(22, 1)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ -> unauthorized }) }
            server.enqueue(MockResponse().setResponseCode(200))
            repo.dispatchScreenTime(after, zone)
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("screen_time", body.getString("type"))
            val gaps = body.getJSONObject("screenTime").getJSONArray("gaps")
            assertEquals(1, gaps.length())
            assertEquals("usage_access_missing", gaps.getJSONObject(0).getString("code"))
            assertEquals(0, body.getJSONObject("screenTime").getJSONArray("apps").length())
            assertTrue(body.getJSONObject("screenTime").isNull("coverageUntil"))
        }
    }

    /** 推送时刻为 0 点（或更早）时当天无数据，直接返回 null 且不查询 reader。 */
    @Test fun midnightPushReturnsNullWithoutQueryingReader() = runBlocking {
        val midnight = t(0, 0)
        val forbidden = ScreenTimeReader { _, _ -> error("0 点推送不得查询 reader") }
        assertNull(loadScreenTimeReport(date, midnight, zone, forbidden))
        assertNull(loadScreenTimeReport(date, midnight - 1, zone, forbidden)) // 0 点前同理：until 不晚于 from
    }

    @Test fun generateFailureDoesNotSkipToTomorrow() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val repo = repo(server)
            val id = repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val after = t(22, 1)
            repo.screenTimeMessage = { error("synthetic generate failure") }
            val before = FailureNotes.dump()
            val failed = runCatching { repo.dispatchScreenTime(after, zone) }
            assertTrue(failed.exceptionOrNull() is IllegalStateException)
            assertNull(prefs.getString("screen-time-sent-$id", null))
            val added = FailureNotes.dump().removePrefix(before).trimStart('\n')
            assertTrue(added.contains("push/screen-time-generate"))
            assertFalse(added.contains("push/screen-time-skip"))
            val next = repo.nextScreenTimeAt(after, zone)!!
            assertEquals(after, next)
            assertTrue(next < date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }

    @Test fun replacingScreenTimeKeepsFollowingReminderSection() {
        val pending = "# 今天\n\n正文\n\n$SCREEN_TIME_MARKER\n## 屏幕时间\n旧屏幕时间\n\n<!-- resound:reminders -->\n## 提醒\n- 15:00 开会\n"
        val sent = replaceScreenTimeSection(pending, "新屏幕时间")
        assertTrue(sent.contains("15:00 开会"))
        assertTrue(sent.contains("<!-- resound:reminders -->"))
        assertTrue(sent.contains("新屏幕时间"))
        assertFalse(sent.contains("旧屏幕时间"))
        val closed = replaceScreenTimeSection(pending, null)
        assertTrue(closed.contains("15:00 开会"))
        assertFalse(closed.contains(SCREEN_TIME_MARKER))
        assertFalse(closed.contains(SCREEN_TIME_END))
    }

    @Test fun reminderDueKeepsReminderInExistingScreenTimeDocument() = runBlocking {
        val dav = Dav()
        try {
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val dir = temp.newFolder()
            val path = MemoryFolder.dailyPath(date)
            val existing = "# 今天\n\n正文\n\n$SCREEN_TIME_MARKER\n## 屏幕时间\n旧屏幕时间\n"
            File(dir, "1.json").writeText(JSONObject().put("templates", JSONObject().put(path, existing)).toString())
            prefs.edit().putString("subscription-1", JSONObject().put("reminderAt", at).toString()).commit()
            val sub = PushSubscription(summaries = false, reminders = true, records = emptySet(),
                trigger = RecordTrigger.Manual, incremental = false, screenTime = true, fullCheckAt = null, backfillDays = null)
            val ch = PushChannel(1, PushKind.MarkdownFolder, "资料", true, Subscriptions.topics(sub), PushFormat.Markdown, "", subscription = sub)
            val folder = mapOf("url" to "https://dav.example.test/dav/", "account" to "test@example.test",
                "password" to "fake-password", "folder" to "Resound-Memory")
            val runtime = SubscriptionRuntime(prefs, dir, { ch }, { ch to folder }, PushTransport(dav.client),
                { _, _, _ -> }, { _, _, _ -> }, { at }, { zone })
            runtime.screenTimeMarkdown = { "新屏幕时间" }
            runtime.source = { _, _ -> PushSnapshot(events = listOf(event()), items = listOf(
                MemoryItemEntity(1, 1, "待办", null, "开会", "2026-11-14 15:00", at, null, null, "Accepted", at - 60_000),
            )) }
            runtime.run(1, manual = false).getOrThrow()
            val daily = dav.files.filterKeys { it.contains("daily-record") }.values.single()
            assertTrue(daily.contains("开会"))
            assertTrue(daily.contains("<!-- resound:reminders -->"))
            assertFalse(daily.contains(SCREEN_TIME_MARKER))
            val reminded = JSONObject(prefs.getString("subscription-1", "{}")!!).optJSONObject("reminded")
            assertTrue(reminded != null && reminded.length() > 0)
            assertTrue(reminded!!.keys().asSequence().any { it.startsWith("item:1:") })
        } finally { dav.close() }
    }

    @Test fun subscriptionDocumentAddsAndRemovesScreenTimeSection() = runBlocking {
        val opened = replaceScreenTimeSection("# 当天\n\n正文\n", "今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分")
        assertTrue(opened.contains("<!-- resound:screen-time -->"))
        assertTrue(opened.endsWith("## 屏幕时间\n今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分\n") || opened.contains("## 屏幕时间\n今日共 3 小时 12 分"))
        val closed = replaceScreenTimeSection(opened, null)
        assertFalse(closed.contains("屏幕时间"))
        assertFalse(closed.contains("resound:screen-time"))
        assertTrue(closed.contains("# 当天"))
        MockWebServer().apply { start() }.use { server ->
            val hook = mapOf("url" to "https://example.com/hook", "signSecret" to "fake-sign")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
            }.build()
            var ch = PushChannel(1, PushKind.Webhook, "测试", true, Subscriptions.topics(openSub), PushFormat.Json, "", subscription = openSub)
            val runtime = SubscriptionRuntime(context.getSharedPreferences(UUID.randomUUID().toString(), 0), temp.newFolder(),
                { ch }, { ch to hook }, PushTransport(client), { _, _, _ -> }, { _, _, _ -> }, { t(10, 0) }, { zone })
            runtime.source = { _, _ -> PushSnapshot(events = listOf(event())) }
            runtime.screenTimeMarkdown = { "今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分" }
            server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1).getOrThrow()
            val first = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getString("markdown")
            assertFalse(first.contains("<!-- resound:screen-time -->"))
            assertFalse(first.contains("## 屏幕时间"))
            assertFalse(first.contains("哔哩哔哩 192 分"))
            ch = ch.copy(subscription = openSub.copy(screenTime = false), topics = Subscriptions.topics(openSub.copy(screenTime = false)))
            server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1).getOrThrow()
            val second = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getString("markdown")
            assertFalse(second.contains("## 屏幕时间"))
            assertFalse(second.contains("resound:screen-time"))
        }
    }

    @Test fun retryDropsScreenTimeAfterSwitchOff() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val outbox = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val repo = repo(server, prefs, outbox)
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val after = t(22, 1)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ -> sampleResult() }) }
            server.enqueue(MockResponse().setResponseCode(503))
            repo.dispatchScreenTime(after, zone)
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(first.contains("哔哩哔哩"))
            assertTrue(first.contains("271.两眼一抹黑，试试也不亏"))
            val job = outbox.all.keys.single() as String
            assertTrue(outbox.getString(job, "")!!.contains("哔哩哔哩"))
            repo.screenTimeMessage = { null }
            val before = FailureNotes.dump()
            assertFalse(repo.retry(job, 0))
            assertEquals(1, server.requestCount)
            assertFalse(outbox.contains(job))
            val added = FailureNotes.dump().removePrefix(before)
            assertTrue(added.contains("push/screen-time-stale-dropped"))
            assertFalse(added.contains("哔哩哔哩"))
            assertFalse(added.contains("271.两眼一抹黑"))
        }
    }

    @Test fun retrySendsRegeneratedScreenTime() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val outbox = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val repo = repo(server, prefs, outbox)
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val after = t(22, 1)
            val staleTitle = "过期节目标题-不应发出"
            repo.screenTimeMessage = {
                buildScreenTimeMessage(date, after, zone, ScreenTimeReader { _, _ -> sampleResult() })!!
                    .let { it.copy(screenTime = it.screenTime!!.put("staleTitle", staleTitle)) }
            }
            server.enqueue(MockResponse().setResponseCode(503))
            repo.dispatchScreenTime(after, zone)
            server.takeRequest(2, TimeUnit.SECONDS)
            val job = outbox.all.keys.single() as String
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ ->
                sampleResult().copy(devicePlayback = emptyList())
            }) }
            server.enqueue(MockResponse().setResponseCode(200))
            assertFalse(repo.retry(job, 0))
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            assertFalse(second.contains(staleTitle))
            assertFalse(second.contains("271.两眼一抹黑，试试也不亏"))
            assertTrue(second.contains("哔哩哔哩"))
            assertFalse(outbox.contains(job))
        }
    }

    @Test fun historicalDocumentSyncDropsStaleScreenTimeSection() = runBlocking {
        val dav = Dav()
        try {
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val dir = temp.newFolder()
            val yesterday = date.minusDays(1)
            val path = MemoryFolder.dailyPath(yesterday)
            val stale = "# $yesterday\n\n正文\n\n$SCREEN_TIME_MARKER\n## 屏幕时间\n哔哩哔哩 192 分\n小宇宙《271.两眼一抹黑，试试也不亏》\n$SCREEN_TIME_END\n"
            File(dir, "1.json").writeText(JSONObject().put("templates", JSONObject().put(path, stale)).toString())
            prefs.edit().putString("subscription-1", JSONObject().put("summaryAt", at).toString()).commit()
            val sub = PushSubscription(summaries = true, reminders = false, records = emptySet(),
                trigger = RecordTrigger.Manual, incremental = false, screenTime = true, fullCheckAt = null, backfillDays = null)
            val ch = PushChannel(1, PushKind.MarkdownFolder, "资料", true, Subscriptions.topics(sub), PushFormat.Markdown, "", subscription = sub)
            val folder = mapOf("url" to "https://dav.example.test/dav/", "account" to "test@example.test",
                "password" to "fake-password", "folder" to "Resound-Memory")
            val asked = mutableListOf<LocalDate>()
            val runtime = SubscriptionRuntime(prefs, dir, { ch }, { ch to folder }, PushTransport(dav.client),
                { _, _, _ -> }, { _, _, _ -> }, { at }, { zone })
            runtime.screenTimeMarkdown = { d -> asked += d; null }
            runtime.source = { _, _ -> PushSnapshot(reviews = listOf(
                ReviewEntity(1, "daily", date.toString(), date.toString(), "摘要", "[]", at, "test"))) }
            runtime.run(1, manual = false).getOrThrow()
            val uploaded = dav.files.values.joinToString("\n")
            assertTrue("应实际上传文档：$uploaded", dav.files.isNotEmpty())
            assertFalse(uploaded.contains("哔哩哔哩"))
            assertFalse(uploaded.contains("271.两眼一抹黑"))
            assertFalse(uploaded.contains(SCREEN_TIME_MARKER))
            // 卡 200：屏幕时间已并入“一天的线索”，每日文档不再单独取屏幕时间正文
            assertTrue(asked.isEmpty())
        } finally { dav.close() }
    }

    @Test fun historicalReportQueryStopsAtNextMidnight() = runBlocking {
        val yesterday = date.minusDays(1)
        val captured = mutableListOf<Pair<Long, Long>>()
        val reader = ScreenTimeReader { from, until ->
            captured += from to until
            ScreenTimeResult.DISABLED
        }
        assertNull(loadScreenTimeReport(yesterday, at, zone, reader))
        val from = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = date.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(listOf(from to until), captured)
    }

    @Test fun generateFailureWorkerReturnsRetry() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        try {
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val repo = AndroidPushChannels(prefs, context.getSharedPreferences(UUID.randomUUID().toString(), 0),
                object : PushSecrets {
                    val map = HashMap<String, String>()
                    override fun read(ref: String) = map.getValue(ref)
                    override fun write(ref: String, value: String) { map[ref] = value }
                    override fun remove(ref: String) { map.remove(ref) }
                }, PushTransport(), {})
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 0), PushFormat.Json, false).getOrThrow()
            repo.screenTimeMessage = { error("synthetic generate failure") }
            AppGraph.push = repo
            val result = TestListenableWorkerBuilder<ScreenTimePushWork>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Retry)
        } finally { WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    @Test fun unavailableScreenTimeWorkerPlansTomorrow() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        try {
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val repo = AndroidPushChannels(prefs, context.getSharedPreferences(UUID.randomUUID().toString(), 0),
                object : PushSecrets {
                    val map = HashMap<String, String>()
                    override fun read(ref: String) = map.getValue(ref)
                    override fun write(ref: String, value: String) { map[ref] = value }
                    override fun remove(ref: String) { map.remove(ref) }
                }, PushTransport(), {})
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 0), PushFormat.Json, false).getOrThrow()
            repo.screenTimeMessage = { null }
            AppGraph.push = repo
            val result = TestListenableWorkerBuilder<ScreenTimePushWork>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("push-screen-time").get()
            assertTrue(infos.isNotEmpty())
            assertTrue(infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED })
        } finally { WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    @Test fun oldSubscriptionJsonDefaultsScreenTimeOffAt2200() {
        val s = PushSubscription(screenTime = true, screenTimeAt = 21 * 60)
        assertEquals(s, Subscriptions.read(Subscriptions.json(s)))
        assertTrue(PushTopic.ScreenTime in Subscriptions.topics(s))
        // 真实旧格式字节：屏幕时间字段加入之前的订阅 JSON（键序与编码与旧版一致），读回应为「未订阅」+ 默认 22 点。
        val legacy = """{"summaries":true,"reminders":false,"records":["Timeline"],"lines":"Off","trigger":{"type":"schedule","times":[1320],"weekdays":[]},"incremental":false,"fullCheckAt":null,"layout":"ByCategory","rangeDays":null,"excludeMedia":true,"backfillDays":null,"lineContext":true,"audio":"Off","audioWithText":true,"audioWifiOnly":true,"footprint":false,"footprintAt":1320}"""
        val read = Subscriptions.read(JSONObject(legacy))
        assertFalse(read.screenTime)
        assertEquals(22 * 60, read.screenTimeAt)
        val old = Subscriptions.json(PushSubscription()).apply { remove("screenTime"); remove("screenTimeAt") }
        val parsed = Subscriptions.read(old)
        assertFalse(parsed.screenTime)
        assertEquals(22 * 60, parsed.screenTimeAt)
    }

    private val openSub = PushSubscription(summaries = false, records = setOf(RecordKind.Timeline),
        incremental = false, screenTime = true, fullCheckAt = null, backfillDays = null)

    private fun event() = MemoryEventEntity(1, t(10, 0), t(10, 1), "工作", "讨论", "摘要", "[\"要点\"]", "[\"我\"]",
        null, null, null, true, false, t(10, 0), "test")

    private fun repo(
        server: MockWebServer,
        prefs: android.content.SharedPreferences = context.getSharedPreferences("push", Context.MODE_PRIVATE),
        outbox: android.content.SharedPreferences = context.getSharedPreferences("push-retries", Context.MODE_PRIVATE),
    ): AndroidPushChannels {
        val secrets = object : PushSecrets {
            val map = HashMap<String, String>()
            override fun read(ref: String) = map.getValue(ref)
            override fun write(ref: String, value: String) { map[ref] = value }
            override fun remove(ref: String) { map.remove(ref) }
        }
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
        }.build())
        return AndroidPushChannels(prefs, outbox, secrets, transport, {})
    }

    private class Dav {
        val files = Collections.synchronizedMap(mutableMapOf<String, String>())
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when (request.method) {
                        "MKCOL" -> MockResponse().setResponseCode(201)
                        "HEAD" -> MockResponse().setResponseCode(if (files.containsKey(path)) 200 else 404)
                        "PUT" -> {
                            files[path] = request.body.clone().readUtf8()
                            MockResponse().setResponseCode(201)
                        }
                        "DELETE" -> {
                            files.remove(path)
                            MockResponse().setResponseCode(204)
                        }
                        "MOVE" -> {
                            val target = HttpUrl.Builder().scheme("https").host("unused.test").build()
                                .resolve(request.getHeader("Destination")!!)!!.encodedPath
                            files[target] = files.remove(path) ?: ""
                            MockResponse().setResponseCode(201)
                        }
                        else -> MockResponse().setResponseCode(200).setBody("{}")
                    }
                }
            }
            start()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        fun close() { server.shutdown() }
    }
}
