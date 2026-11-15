package app.resound.push

import android.app.Application
import android.content.Context
import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class FootprintRetry190Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 15)
    private val now = day.atStartOfDay(zone).plusHours(22).toInstant().toEpochMilli()
    private val prefs get() = context.getSharedPreferences("push", 0)
    private fun repo(transport: PushTransport = PushTransport(), enqueue: (String) -> Unit = {}): AndroidPushChannels =
        AndroidPushChannels(prefs, context.getSharedPreferences("push-retries", 0), object : PushSecrets {
            override fun read(ref: String) = ""
            override fun write(ref: String, value: String) { }
            override fun remove(ref: String) { }
        }, transport, enqueue)
    private suspend fun add(repo: AndroidPushChannels, name: String): Long = repo.saveWithSubscription(
        null, PushKind.Webhook, name, mapOf("url" to "https://example.com/hook/$name"),
        PushSubscription(summaries = false, footprint = true, footprintAt = 22 * 60), PushFormat.Json, false,
    ).getOrThrow()

    @Test fun nullGenerationRetriesPerChannelAcrossRestartAndStopsAtThree() = runBlocking {
        prefs.edit().clear().commit()
        var repo = repo()
        val first = add(repo, "one"); val second = add(repo, "two")
        var builds = 0
        repo.footprintMessage = { builds++; null }
        assertEquals(now + FootprintRetry.RETRY_MILLIS, repo.dispatchFootprint(now, zone))
        assertNull(prefs.getString("footprint-sent-$first", null))
        repo.dispatchFootprint(now + 1, zone)
        assertEquals(1, builds) // 没到重试时间不忙循环。
        repo = repo() // 重启后仍按同日、同通道计数。
        repo.footprintMessage = { builds++; null }
        repo.dispatchFootprint(now + FootprintRetry.RETRY_MILLIS, zone)
        assertNull(prefs.getString("footprint-sent-$second", null))
        val next = repo.dispatchFootprint(now + 2 * FootprintRetry.RETRY_MILLIS, zone)
        for (id in listOf(first, second)) {
            assertEquals(day.toString(), prefs.getString("footprint-sent-$id", null))
            assertEquals(3, prefs.getInt("footprint-attempts-$id", 0))
        }
        assertEquals(day.plusDays(1).atStartOfDay(zone).plusHours(22).toInstant().toEpochMilli(), next)
        repo.dispatchFootprint(now + 3 * FootprintRetry.RETRY_MILLIS, zone)
        assertEquals(3, builds)
        repo.dispatchFootprint(now + 86_400_000, zone)
        assertEquals(4, builds)
        assertTrue(FailureNotes.dump().contains("三次失败，今天已处理"))
    }

    @Test fun generationRecoversAndSendingFailureUsesExistingOutbox() = runBlocking {
        prefs.edit().clear().commit()
        context.getSharedPreferences("push-retries", 0).edit().clear().commit()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(500))
            val jobs = mutableListOf<String>()
            val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
            }.build())
            val repo = repo(transport, jobs::add)
            val id = add(repo, "recover")
            var builds = 0
            repo.footprintMessage = {
                if (++builds == 1) error("private server response")
                PushMessage("足迹", "内容", PushTopic.Footprint, null, now)
            }
            repo.dispatchFootprint(now, zone)
            assertNull(prefs.getString("footprint-sent-$id", null))
            repo.dispatchFootprint(now + FootprintRetry.RETRY_MILLIS, zone)
            assertEquals(day.toString(), prefs.getString("footprint-sent-$id", null))
            assertEquals(1, jobs.size)
            assertEquals(1, context.getSharedPreferences("push-retries", 0).all.size)
            repo.dispatchFootprint(now + 2 * FootprintRetry.RETRY_MILLIS, zone)
            assertEquals(2, builds); assertEquals(1, server.requestCount)
            assertFalse(FailureNotes.dump().contains("private server response"))
        }
    }
}
