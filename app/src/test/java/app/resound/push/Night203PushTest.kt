package app.resound.push

import android.app.Application
import app.resound.night.WeeklyReport
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class Night203PushTest {
    @Test fun weeklyWebhookDeduplicatesAndSwitchOffSendsDeletion()=runBlocking {
        val server=MockWebServer().apply {start()};val dir=Files.createTempDirectory("night203-push").toFile()
        try {
            val client=OkHttpClient.Builder().addInterceptor {chain->chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())}.build()
            val ch=PushChannel(1,PushKind.Webhook,"测试",true,emptySet(),PushFormat.Json,"",subscription=PushSubscription(records=setOf(RecordKind.Timeline)))
            val context=RuntimeEnvironment.getApplication()
            val runtime=SubscriptionRuntime(context.getSharedPreferences("night203-push",0),dir,{ch},{ch to mapOf("url" to "https://example.test/hook")},PushTransport(client),{_,_,_->},{_,_,_->},{1794906000000L},{ZoneId.of("UTC")})
            runtime.source={_,_->PushSnapshot()}
            var reports=listOf(WeeklyReport(LocalDate(2026,11,9),3,"规则周报",null,"侧睡试试",false,0))
            runtime.nightWeekSource={reports}
            server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1,manual=false,dayFinal=true).getOrThrow()
            assertEquals("night_week",JSONObject(server.takeRequest().body.readUtf8()).getString("type"))
            runtime.run(1,manual=false,dayFinal=true).getOrThrow();assertEquals(1,server.requestCount)
            reports=emptyList();server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1,manual=false,dayFinal=true).getOrThrow()
            assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("deleted"))
            runtime.run(1,manual=false,dayFinal=true).getOrThrow();assertEquals(2,server.requestCount)
        } finally {server.shutdown();dir.deleteRecursively()}
    }
}
