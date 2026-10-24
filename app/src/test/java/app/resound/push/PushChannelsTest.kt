package app.resound.push

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.backup.BackupArchive
import app.resound.backup.BackupRestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PushChannelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val message = PushMessage("回响 · 今日回顾", "## 今日\n**完成任务**", PushTopic.DailyReview, "resound://review?date=2026-10-23", 1234)
    private val configs = mapOf(
        PushKind.PushPlus to mapOf("token" to "fake-token-123", "topic" to "team"),
        PushKind.WeCom to mapOf("webhook" to "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fake-key-123"),
        PushKind.ServerChan to mapOf("sendKey" to "SCTfake-key-123"),
        PushKind.Webhook to mapOf("url" to "https://example.com/hook", "authorization" to "Bearer fake-auth-123"),
    )
    // 保留并检查生产请求的 HTTPS 地址，再仅在测试拦截器中路由到本地假服务器。
    private class FakeServer : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            requests += original
            chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath +
                (original.url.encodedQuery?.let { "?$it" } ?: ""))).build())
        }.build())
        fun reply(body: String = "", code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
        fun take() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        override fun close() { server.shutdown() }
    }

    @Test fun pushPlusBuildsAllTemplatesAndGroup() = runBlocking {
        FakeServer().use { fake ->
            PushFormat.entries.forEach { format ->
                fake.reply("{\"code\":200}")
                assertTrue(fake.transport.send(PushKind.PushPlus, format, configs.getValue(PushKind.PushPlus), message).isSuccess)
                val request = fake.take()
                assertEquals("POST", request.method)
                assertEquals("https://www.pushplus.plus/send", fake.requests.last().url.toString())
                val json = JSONObject(request.body.readUtf8())
                assertEquals("fake-token-123", json.getString("token"))
                assertEquals("team", json.getString("topic"))
                assertEquals(listOf("txt", "markdown", "json")[format.ordinal], json.getString("template"))
                when (format) {
                    PushFormat.Text -> assertEquals("今日\n完成任务", json.getString("content"))
                    PushFormat.Markdown -> assertEquals(message.body, json.getString("content"))
                    PushFormat.Json -> assertEquals(message.deepLink, JSONObject(json.getString("content")).getString("deepLink"))
                }
            }
        }
    }

    @Test fun weComUsesMarkdownOrTextAndRejectsOtherDestinations() = runBlocking {
        FakeServer().use { fake ->
            PushFormat.entries.forEach { format ->
                fake.reply("{\"errcode\":0}")
                assertTrue(fake.transport.send(PushKind.WeCom, format, configs.getValue(PushKind.WeCom), message).isSuccess)
                val request = fake.take()
                assertEquals(configs.getValue(PushKind.WeCom).getValue("webhook"), fake.requests.last().url.toString())
                val json = JSONObject(request.body.readUtf8())
                val type = if (format != PushFormat.Text) "markdown" else "text"
                assertEquals(type, json.getString("msgtype"))
                assertTrue(json.getJSONObject(type).getString("content").contains(message.title))
                assertEquals(format != PushFormat.Text, json.getJSONObject(type).getString("content").contains("**"))
            }
            listOf("http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", "https://qyapi.weixin.qq.com.evil.test/cgi-bin/webhook/send?key=x",
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x&extra=y").forEach { url ->
                assertTrue(fake.transport.send(PushKind.WeCom, PushFormat.Text, mapOf("webhook" to url), message).isFailure)
            }
            assertEquals(3, fake.requests.size)
            // JSON 不受支持时，带回顾的消息也必须真正按 Markdown 渲染。
            val review = app.resound.review.DailyReview(java.time.LocalDate.parse("2026-10-23"), "简述", emptyList(), 1)
            val reviewMessage = message.copy(review = review)
            fake.reply("{\"errcode\":0}")
            fake.transport.send(PushKind.WeCom, PushFormat.Json, configs.getValue(PushKind.WeCom), reviewMessage).getOrThrow()
            assertTrue(JSONObject(fake.take().body.readUtf8()).getJSONObject("markdown").getString("content").contains("**"))
            fake.reply("{\"code\":0}")
            fake.transport.send(PushKind.ServerChan, PushFormat.Json, configs.getValue(PushKind.ServerChan), reviewMessage).getOrThrow()
            assertTrue(java.net.URLDecoder.decode(fake.take().body.readUtf8(), "UTF-8").contains("**"))
        }
    }

    @Test fun serverChanBuildsKeyPathAndFormMarkdown() = runBlocking {
        FakeServer().use { fake ->
            fake.reply("{\"code\":0}")
            assertTrue(fake.transport.send(PushKind.ServerChan, PushFormat.Markdown, configs.getValue(PushKind.ServerChan), message).isSuccess)
            assertEquals("https://sctapi.ftqq.com/SCTfake-key-123.send", fake.requests.single().url.toString())
            val request = fake.take()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            val form = request.body.readUtf8().split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals(message.title, form["title"])
            assertEquals(message.body, form["desp"])
        }
    }

    @Test fun webhookBuildsEnvelopeAndAuthorizationAndRequiresHttps() = runBlocking {
        FakeServer().use { fake ->
            PushFormat.entries.forEach { format ->
                fake.reply(code = 204)
                assertTrue(fake.transport.send(PushKind.Webhook, format, configs.getValue(PushKind.Webhook), message).isSuccess)
                val request = fake.take()
                assertEquals("https://example.com/hook", fake.requests.last().url.toString())
                assertEquals("Bearer fake-auth-123", request.getHeader("Authorization"))
                val json = JSONObject(request.body.readUtf8())
                assertEquals(setOf("title", "body", "topic", "deepLink", "at", "type", "version", "app"), json.keys().asSequence().toSet())
                assertEquals(message.title, json.getString("title"))
                assertEquals("DailyReview", json.getString("topic"))
                assertEquals(message.deepLink, json.getString("deepLink"))
                assertEquals(1234L, json.getLong("at"))
                assertEquals(if (format == PushFormat.Text) "今日\n完成任务" else message.body, json.getString("body"))
            }
            assertTrue(fake.transport.send(PushKind.Webhook, PushFormat.Text, mapOf("url" to "http://example.com"), message).isFailure)
        }
    }

    @Test fun checksVendorCodesRedirectsMalformedAndOversizedResponses() = runBlocking {
        FakeServer().use { fake ->
            configs.keys.forEach { kind ->
                fake.reply("{\"code\":500,\"errcode\":40013,\"errmsg\":\"fake-token-123 https://secret.test/path\"}", if (kind == PushKind.Webhook) 500 else 200)
                val error = fake.transport.send(kind, PushFormat.Text, configs.getValue(kind), message).exceptionOrNull()!!
                assertFalse(error.toString().contains("fake-token")); assertFalse(error.toString().contains("https://"))
                fake.take()
            }
            listOf("{}", "{\"code\":\"200\"}", "invalid", "x".repeat(65537)).forEach { body ->
                fake.reply(body)
                assertTrue(fake.transport.send(PushKind.PushPlus, PushFormat.Text, configs.getValue(PushKind.PushPlus), message).isFailure)
                fake.take()
            }
            fake.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", fake.server.url("/should-not-send")))
            assertTrue(fake.transport.send(PushKind.Webhook, PushFormat.Text, configs.getValue(PushKind.Webhook), message).isFailure)
            fake.take()
            assertEquals(9, fake.server.requestCount)
        }
    }

    private class FakeSecrets : PushSecrets {
        val items = mutableMapOf<String, String>()
        override fun read(ref: String) = items[ref].orEmpty()
        override fun write(ref: String, value: String) { items[ref] = value }
        override fun remove(ref: String) { items.remove(ref) }
    }
    private fun repository(fake: FakeServer, secrets: PushSecrets = FakeSecrets(), enqueue: (String) -> Unit = {}) = AndroidPushChannels(
        context.getSharedPreferences("push", Context.MODE_PRIVATE), context.getSharedPreferences("push-retries", Context.MODE_PRIVATE), secrets, fake.transport, enqueue)
    private suspend fun AndroidPushChannels.add(kind: PushKind = PushKind.PushPlus) = save(null, kind, "测试通道", configs.getValue(kind),
        setOf(PushTopic.DailyReview), PushFormat.Markdown).getOrThrow()

    @Test fun failureQueuesConnectedExponentialWorkAndStopsAfterThreeRetries() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        FakeServer().use { fake ->
            val queued = mutableListOf<String>()
            val repository = repository(fake, enqueue = { queued += it; PushRetryScheduler(context).enqueue(it) })
            repository.add()
            repeat(4) { fake.reply("{\"code\":500}") }
            repository.dispatch(message)
            val job = queued.single()
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("push-$job").get().single()
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
            val spec = PushRetryScheduler.request(job).workSpec
            assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
            assertEquals(30000L, spec.backoffDelayDuration)
            assertEquals(30000L, spec.initialDelay)
            assertEquals(setOf("job"), spec.input.keyValueMap.keys)
            assertTrue(repository.retry(job, 0)); assertTrue(repository.retry(job, 1)); assertFalse(repository.retry(job, 2))
            assertFalse(repository.retry(job, 3))
            assertEquals(4, repository.logs().first().size)
            assertEquals(4, fake.server.requestCount)
            assertTrue(context.getSharedPreferences("push-retries", Context.MODE_PRIVATE).all.isEmpty())
            WorkManager.getInstance(context).cancelAllWorkByTag("push-retry").result.get()
            Unit
        }
    }

    @Test fun secretsStayOutOfConfigSummaryLogsAndBackupAndBlankEditRetainsThem() = runBlocking {
        FakeServer().use { fake ->
            val secrets = FakeSecrets()
            val repository = repository(fake, secrets)
            val id = repository.add()
            repository.save(id, PushKind.PushPlus, "测试通道", mapOf("token" to ""), setOf(PushTopic.DailyReview), PushFormat.Text).getOrThrow()
            assertEquals(listOf("fake-token-123"), secrets.items.values.toList())
            // 预填旧日志，验证环形上限；只真正发送一次。
            val oldLogs = org.json.JSONArray()
            repeat(100) { i -> oldLogs.put(JSONObject().put("id", 100 - i).put("channelId", id).put("channelName", "旧通道")
                .put("at", 1).put("topic", "DailyReview").put("title", "旧消息").put("ok", true).put("error", JSONObject.NULL)) }
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().putString("logs", oldLogs.toString()).commit()
            fake.reply("{\"code\":500,\"msg\":\"fake-token-123\"}")
            repository.dispatch(message.copy(title = "fake-token-123 https://example.com/private"))
            assertEquals(100, repository.logs(1000).first().size)
            assertFalse(prefs.all.toString().contains("fake-token-123"))
            assertFalse(repository.channels.first().toString().contains("fake-token-123"))
            assertFalse(repository.logs().first().toString().contains("https://"))
            assertTrue(BackupArchive.allowed("shared_prefs/push.xml"))
            assertFalse(BackupArchive.allowed("shared_prefs/push-secrets.xml"))
            assertFalse(BackupArchive.allowed("shared_prefs/push-retries.xml"))
            // 负责人定（卡 037）：恢复不清手机上已填的密钥；待重试消息仍清掉
            context.getSharedPreferences("push-secrets", Context.MODE_PRIVATE).edit().putString("ref", "encrypted").commit()
            val stage = File(context.noBackupFilesDir, "push-restore-${UUID.randomUUID()}").apply { mkdirs() }
            File(context.applicationInfo.dataDir, "shared_prefs/push.xml").copyTo(
                File(stage, "shared_prefs/push.xml").apply { parentFile!!.mkdirs() })
            BackupRestore.stage(context, stage)
            BackupRestore.applyPending(context)
            assertEquals("encrypted", context.getSharedPreferences("push-secrets", Context.MODE_PRIVATE).getString("ref", null))
            assertTrue(context.getSharedPreferences("push-retries", Context.MODE_PRIVATE).all.isEmpty())
            secrets.items.clear()
            val restored = repository(fake, secrets)
            assertTrue(restored.channels.first().single().summary.contains("未填写"))
            val error = restored.test(id).exceptionOrNull()!!
            assertTrue(error.message!!.contains("重填密钥"))
            assertEquals(1, fake.server.requestCount)
        }
    }

    @Test fun notifierFiltersTopicsIsolatesFailureAndTestsDisabledChannel() = runBlocking {
        FakeServer().use { fake ->
            val repository = repository(fake)
            repository.add()
            val webhook = repository.add(PushKind.Webhook)
            val disabled = repository.add(PushKind.ServerChan)
            repository.setEnabled(disabled, false)
            val local = object : Notifier {
                var count = 0
                override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) { count++; throw IllegalStateException("本地通知权限关闭") }
            }
            val notifier = ChannelNotifier(local, repository)
            runCatching { notifier.send("仅本地", "内容", null, null) }
            runCatching { notifier.send("无匹配", "内容", null, PushTopic.NewEvent) }
            assertEquals(0, fake.server.requestCount)
            fake.reply("{\"code\":500}"); fake.reply("{\"code\":500}")
            runCatching { notifier.send(message.title, message.body, message.deepLink, message.topic) }
            val logs = repository.logs().first()
            assertEquals(2, logs.size)
            assertTrue(logs.single { it.channelId == webhook }.ok)
            assertFalse(logs.single { it.channelId != webhook }.ok)
            assertEquals(3, local.count)
            fake.reply("{\"code\":0}")
            assertTrue(repository.test(disabled).isSuccess)
            assertEquals("回响 · 测试推送", repository.logs().first().first().title)
            assertEquals(3, fake.server.requestCount)
            repository.delete(disabled)
            assertEquals(2, repository.channels.first().size)
        }
    }
}
