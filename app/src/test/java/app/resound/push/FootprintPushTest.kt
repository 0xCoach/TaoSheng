package app.resound.push

import android.app.Application
import android.content.Context
import app.resound.location.FootprintReports
import app.resound.location.LocationPoint
import app.resound.location.Place
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class FootprintPushTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 9)
    private val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun p(min: Long, lat: Double, lng: Double) = LocationPoint(at = dayStart + min * 60_000, lat = lat, lng = lng, accuracy = 15f, provider = "fused")

    @Test fun subscriptionKeepsFootprintAndOldJsonDefaultsOff() {
        val s = PushSubscription(footprint = true, footprintAt = 21 * 60)
        assertEquals(s, Subscriptions.read(Subscriptions.json(s)))
        assertTrue(PushTopic.Footprint in Subscriptions.topics(s))
        val old = Subscriptions.json(PushSubscription()).apply { remove("footprint"); remove("footprintAt") }
        assertFalse(Subscriptions.read(old).footprint)
    }

    @Test fun reportListsHomeAndNamedStays() {
        val pts = (0L..480L step 5).map { p(it, 30.2408, 119.9199) } + (540L..660L step 5).map { p(it, 30.2600, 119.9500) }
        val r = FootprintReports.render(date, zone, pts, dayStart + 700 * 60_000L, { _, _ -> false },
            { la, _ -> if (la > 30.25) Place("西溪印象城", "{{ADDRESS_e33ma0jm}}", "系统") else Place("山水苑", "", "系统") })!!
        assertTrue(r.title.contains("11月9日"))
        assertTrue(r.body.startsWith("在家 8 小时"))
        assertTrue(r.body.contains("00:00–08:00 家 · 山水苑（8 小时）"))
        assertTrue(r.body.contains("09:00–11:00 西溪印象城（2 小时）\n  {{ADDRESS_e33ma0jm}}"))
        assertEquals(2, r.json.getJSONArray("stays").length())
        assertEquals(pts.size, r.json.getJSONArray("points").length())
        assertNull(FootprintReports.render(date, zone, emptyList(), dayStart, { _, _ -> false }, { _, _ -> null }))
    }

    @Test fun dispatchSendsOncePerDayAfterChosenTime() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            context.getSharedPreferences("push", Context.MODE_PRIVATE).edit().clear().commit()
            val secrets = object : PushSecrets {
                val map = HashMap<String, String>()
                override fun read(ref: String) = map.getValue(ref)
                override fun write(ref: String, value: String) { map[ref] = value }
                override fun remove(ref: String) { map.remove(ref) }
            }
            val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
            }.build())
            val repo = AndroidPushChannels(context.getSharedPreferences("push", Context.MODE_PRIVATE),
                context.getSharedPreferences("push-retries", Context.MODE_PRIVATE), secrets, transport, {})
            val id = repo.saveWithSubscription(null, PushKind.Webhook, "足迹", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, footprint = true, footprintAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            repo.footprintMessage = { d -> PushMessage("今日足迹", "在家 8 小时", PushTopic.Footprint, null, 1, footprint = JSONObject().put("date", d.toString())) }
            val before = dayStart + 21 * 3_600_000L
            assertEquals(dayStart + 22 * 3_600_000L, repo.dispatchFootprint(before, zone))   // 没到点，不推
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(200))
            val after = dayStart + 22 * 3_600_000L + 60_000
            val next = repo.dispatchFootprint(after, zone)
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("footprint", body.getString("type"))
            assertEquals(date.toString(), body.getJSONObject("footprint").getString("date"))
            assertEquals(dayStart + 46 * 3_600_000L, next)                                       // 下一次是明天 22 点
            repo.dispatchFootprint(after + 600_000, zone)
            assertEquals(1, server.requestCount)                                                 // 同一天不重复
        } finally { server.shutdown() }
    }
}
