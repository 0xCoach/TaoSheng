package app.resound.push

import android.app.Application
import app.resound.context.*
import app.resound.platform.ZoneHistory
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
class DayThreadPush200Test {
    @get:Rule val temp=TemporaryFolder()
    private val today=LocalDate.of(2026,11,17)
    private val zones=ZoneHistory(emptyList(),"Asia/Shanghai")
    private val sub=PushSubscription(records=setOf(RecordKind.Timeline))
    private val channel=PushChannel(1,PushKind.MarkdownFolder,"测试",true,emptySet(),PushFormat.Markdown,"",subscription=sub)
    private fun state()=PushSyncState(temp.newFolder().resolve("state.json"))
    private val source: suspend (LocalDate,PushSnapshot,PushSubscription,ZoneHistory,Boolean)->DayThreadSource = { date,_,s,h,_ ->
        val spans=h.zonesOn(date.toKotlinLocalDate())
        DayThreadSource(ThreadInput(date.toKotlinLocalDate(),spans.first().from,spans.last().until,0,location=s.footprint,screen=s.screenTime),emptySet())
    }
    @Test fun webhookFinalizationSendsSevenOffsetJsonMessagesOnlyOnce() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        val previous = app.resound.platform.HistoricalZones.history
        try {
            app.resound.platform.HistoricalZones.history = { zones }
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
            }.build()
            val ch = channel.copy(kind = PushKind.Webhook, format = PushFormat.Text)
            val at = today.atTime(4,0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val runtime = SubscriptionRuntime(context.getSharedPreferences(java.util.UUID.randomUUID().toString(),0),temp.newFolder(),
                { ch }, { ch to mapOf("url" to "https://example.test/hook") }, PushTransport(client), { _,_,_-> }, { _,_,_-> }, { at }, { java.time.ZoneId.of("Asia/Shanghai") })
            runtime.source = { _,_-> PushSnapshot() }; runtime.dayThreadSource = source
            repeat(7) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204)) }
            runtime.run(1,manual=false,dayFinal=true).getOrThrow()
            assertEquals(7,server.requestCount)
            repeat(7) {
                val payload=JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("day_context",payload.getString("type"));assertTrue(LocalDate.parse(payload.getString("date")) < today)
            }
            runtime.run(1,manual=false,dayFinal=true).getOrThrow()
            assertEquals(7,server.requestCount)
        } finally { app.resound.platform.HistoricalZones.history = previous; server.shutdown() }
    }
    @Test fun dawnOnlyPastDaysAndIdenticalContentNotAcknowledgedAgain()= runBlocking {
        val state=state();state.json.put("dayThreadInitialized",true)
        assertTrue(prepareDayThreads(mutableMapOf(),state,channel,PushSnapshot(),today,3,zones,source).isEmpty())
        val out=prepareDayThreads(mutableMapOf(),state,channel,PushSnapshot(),today,4,zones,source)
        assertEquals(listOf(today.minusDays(1),today.minusDays(2),today.minusDays(3)),out.map { it.date })
        acknowledgeDay(state,out.first(),100);acknowledgeDay(state,out.first(),200)
        assertEquals(100,state.json.getJSONObject("dayThreads").getJSONObject(out.first().date.toString()).getLong("writtenAt"))
    }
    @Test fun threeDayCorrectionAndArchiveDoesNotReadExpiredApps()= runBlocking {
        val state=state();state.json.put("dayThreadInitialized",true)
        val oldDate=today.minusDays(4);val thread=DayThread(oldDate.toString(),DayOverview(0,60000,null,null,0),listOf(ThreadEntry(ThreadKind.App,1,60001,"📱 微信 1 分")))
        state.json.put("dayThreads",JSONObject().put(oldDate.toString(),JSONObject().put("thread",Json.encodeToString(thread)).put("segments",org.json.JSONArray())))
        var archived=false
        val prepared=prepareDayThreads(mutableMapOf(),state,channel,PushSnapshot(),today,4,zones) { d,raw,s,h,frozen -> if(d==oldDate) {assertTrue(frozen);archived=true};source(d,raw,s,h,frozen) }
        assertTrue(archived)
        assertFalse(DayThreadPolicy.mutable(oldDate.toKotlinLocalDate(),today.toKotlinLocalDate()))
        assertTrue(DayThreadPolicy.mutable(today.minusDays(3).toKotlinLocalDate(),today.toKotlinLocalDate()))
        assertTrue(prepared.last().thread.entries.isEmpty()) // screen subscription off clears archived app data
    }
    @Test fun privacyRemovalRetainsArchivedAppAndPlayback() {
        val old=DayThread("2025-01-01",DayOverview(0,1,null,null,2),listOf(ThreadEntry(ThreadKind.Event,0,1,"删除",listOf(1)),ThreadEntry(ThreadKind.Event,1,2,"保留",listOf(2)),ThreadEntry(ThreadKind.App,1,2,"App"),ThreadEntry(ThreadKind.Playback,1,2,"播放"),ThreadEntry(ThreadKind.Gap,0,1,"缺口"),ThreadEntry(ThreadKind.Gap,5,6,"无关缺口")))
        val result=DayThreads.redact(old,setOf(2))
        assertEquals(listOf("保留","App","播放","无关缺口"),result.entries.map { it.text });assertEquals(1,result.overview.events)
    }
    @Test fun firstUseBackfillsSevenDaysAndChatExcluded() {
        assertEquals(7,DayThreadPolicy.dates(today.toKotlinLocalDate(),4,true).size)
        assertTrue(DayThreadPolicy.eligible(PushKind.Notion,sub));assertTrue(DayThreadPolicy.eligible(PushKind.Webhook,sub))
        assertFalse(DayThreadPolicy.eligible(PushKind.WeCom,sub));assertFalse(DayThreadPolicy.eligible(PushKind.MarkdownFolder,PushSubscription()))
    }
    @Test fun sectionPreservedAndLegacyScreenRemoved() {
        val section="$DAY_THREAD_MARKER\n## 一天的线索\n已定稿\n$DAY_THREAD_END"
        val body="# 今日\n新事件\n$SCREEN_TIME_MARKER\n旧屏幕时间\n$SCREEN_TIME_END\n<!-- resound:reminders -->\n提醒"
        val result=replaceDayThread(body,section)
        assertEquals(section,dayThreadSection(result));assertFalse(result.contains("旧屏幕时间"));assertTrue(result.contains("新事件"));assertTrue(result.contains("提醒"))
        assertEquals(result,replaceDayThread(result,section))
    }
    @Test fun dayContextTimesHaveOffsetAndNoZoneCity() {
        val thread=DayThread("2026-11-16",DayOverview(0,null,null,null,1),listOf(ThreadEntry(ThreadKind.Event,0,60000,"事",listOf(1))))
        val payload=dayThreadPayload(thread,zones)
        assertEquals("day_context",payload.getString("type"))
        assertEquals("1970-01-01T08:00:00+08:00",payload.getJSONArray("entries").getJSONObject(0).getString("start"))
        assertFalse(payload.toString().contains("Shanghai"))
    }
    @Test fun failedUploadDoesNotAdvanceFingerprint()= runBlocking {
        val state=state()
        val result=prepareDayThreads(mutableMapOf(),state,channel,PushSnapshot(),today,4,zones,source)
        assertEquals(7,result.size)
        assertFalse(state.json.has("dayThreads"))
        acknowledgeDay(state,result.first(),123)
        assertEquals(1,state.json.getJSONObject("dayThreads").length())
    }
}
