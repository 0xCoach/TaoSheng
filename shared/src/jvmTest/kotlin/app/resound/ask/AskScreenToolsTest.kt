package app.resound.ask

import app.resound.ask.core.*
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import app.resound.screentime.CoverageGap
import app.resound.screentime.DevicePlayback
import app.resound.screentime.GapCode
import app.resound.screentime.NamedAppSession
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class AskScreenToolsTest {
    private val zone = TimeZone.of("Asia/Shanghai")
    private val disabledJson =
        """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"屏幕时间未开启，没有 App 使用数据。"}"""

    @Test fun overnightQueryKeepsIntervalAndFieldOrder() = runBlocking {
        val from = AskTime.timestamp("2026-11-13 23:00", false, zone)
        val to = AskTime.timestamp("2026-11-14 01:00", true, zone)
        val wxFrom = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val wxTo = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val biliFrom = AskTime.timestamp("2026-11-14 00:10", false, zone)
        val biliTo = AskTime.timestamp("2026-11-14 00:40", false, zone)
        val covered = AskTime.timestamp("2026-11-14 00:50", false, zone)
        val gapUntil = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val reader = RecordingReader(ScreenTimeResult(
            enabled = true,
            sessions = listOf(
                NamedAppSession("tv.danmaku.bili", "哔哩哔哩", biliFrom, biliTo),
                NamedAppSession("com.tencent.mm", "微信", wxFrom, wxTo),
            ),
            coverageUntil = covered,
            gaps = listOf(CoverageGap("coverage_lag", "screen", from, gapUntil, "等待下一次采集")),
        ))
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources)
        assertEquals(1, reader.calls)
        assertEquals(from, reader.from)
        assertEquals(to, reader.until)
        assertEquals(
            """{"totals":[{"ref":1,"name":"微信","package":"com.tencent.mm","minutes":45},{"ref":2,"name":"哔哩哔哩","package":"tv.danmaku.bili","minutes":30}],"sessions":[{"ref":1,"name":"微信","package":"com.tencent.mm","start":"2026-11-13 23:10","end":"2026-11-13 23:55"},{"ref":2,"name":"哔哩哔哩","package":"tv.danmaku.bili","start":"2026-11-14 00:10","end":"2026-11-14 00:40"}],"devicePlayback":[],"coverageUntil":"2026-11-14 00:50","gaps":[{"code":"coverage_lag","source":"screen","from":"2026-11-13 23:00","until":"2026-11-13 23:10","fix":"等待下一次采集"}],"truncated":false}""",
            out,
        )
        assertEquals(
            listOf(
                AskSource("screen", wxFrom, wxFrom, "微信 使用", "23:10–23:55 微信"),
                AskSource("screen", biliFrom, biliFrom, "哔哩哔哩 使用", "00:10–00:40 哔哩哔哩"),
            ),
            sources,
        )
    }

    @Test fun invalidRangeIsRejectedBeforeReader() {
        var called = 0
        val core = AskScreenToolsCore({ called++; error("不应调用") }, zone)
        assertThrows(AskDateException::class.java) {
            runBlocking { core.execute("""{"from":"2026-11-14 01:00","to":"2026-11-13 23:00"}""", mutableListOf()) }
        }
        assertThrows(AskDateException::class.java) {
            runBlocking { core.execute("""{"from":"2026-11-01 00:00","to":"2026-11-11 23:59"}""", mutableListOf()) }
        }
        assertEquals(0, called)
    }

    @Test fun disabledOrMissingReaderReturnsNoticeOnly() = runBlocking {
        val sources = mutableListOf(AskSource("line", 1, 0, "旧", "原话"))
        assertEquals(disabledJson, AskScreenToolsCore({ null }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources))
        assertEquals(disabledJson, AskScreenToolsCore({ RecordingReader(ScreenTimeResult.DISABLED) }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources))
        assertEquals(1, sources.size)
    }

    @Test fun usageAccessMissingReturnsEmptyListsWithGap() = runBlocking {
        val from = AskTime.timestamp("2026-11-13 23:00", false, zone)
        val to = AskTime.timestamp("2026-11-14 01:00", true, zone)
        val reader = RecordingReader(ScreenTimeResult(
            enabled = true,
            sessions = emptyList(),
            coverageUntil = null,
            gaps = listOf(CoverageGap(GapCode.USAGE_ACCESS_MISSING, "screen", from, to, "设置 → 使用情况访问 → 回响 开启")),
        ))
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", mutableListOf())
        assertEquals(1, reader.calls)
        assertEquals(
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[{"code":"usage_access_missing","source":"screen","from":"2026-11-13 23:00","until":"2026-11-14 01:01","fix":"设置 → 使用情况访问 → 回响 开启"}],"truncated":false}""",
            out,
        )
    }

    @Test fun thousandSessionsTruncatesSessionsButKeepsTotals() = runBlocking {
        val start0 = AskTime.timestamp("2026-11-13 00:00", false, zone)
        val sessions = (0 until 1000).map { i ->
            val start = start0 + i * 60_000L
            NamedAppSession("a.a", "A", start, start + 60_000L)
        }
        val reader = RecordingReader(ScreenTimeResult(true, sessions, null, emptyList()))
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 00:00","to":"2026-11-13 17:00"}""", sources)
        assertTrue(out.length <= 6000)
        assertTrue(out.startsWith("""{"totals":[{"ref":1,"name":"A","package":"a.a","minutes":1000}],"sessions":["""))
        assertTrue(out.endsWith("""],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":true}"""))
        val json = JSONObject(out)
        val kept = json.getJSONArray("sessions")
        assertTrue(kept.length() in 1 until 1000)
        assertEquals(1, json.getJSONArray("totals").length())
        assertEquals(1000, json.getJSONArray("totals").getJSONObject(0).getInt("minutes"))
        assertEquals(kept.length(), sources.size)
        assertEquals(start0, sources.first().id)
        assertEquals(start0 + (kept.length() - 1) * 60_000L, sources.last().id)
    }

    @Test fun engineCitesAppsDuringScreenSource() = runBlocking {
        val start = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val end = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val tools = AskScreenToolsCore({
            ScreenTimeReader { _, _ -> ScreenTimeResult(true, listOf(NamedAppSession("com.tencent.mm", "微信", start, end)), end, emptyList()) }
        }, zone)
        var round = 0
        var result: AskEngineCore.Update? = null
        val updates = mutableListOf<AskEngineCore.Update>()
        engine(TestStream { _, _, _, _ ->
            if (round++ == 0) reply("", listOf(tool("a", "apps_during", """{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""")))
            else reply("晚上在用微信[^1]", emptyList())
        }, JSONArray(AskDefinitions.json()), { name, args, sources ->
            assertEquals("apps_during", name)
            JSONObject(tools.execute(args.toString(), sources))
        }, { _, _ -> }).answer("", emptyList(), "晚上用了什么 App") { result = it; updates += it }
        assertNull(result!!.error)
        assertEquals("screen", result!!.sources.single().kind)
        assertEquals("微信 使用", result!!.sources.single().title)
        assertTrue(updates.any { it.progress == "正在查手机使用记录…" })
    }

    /** 必测 5：devicePlayback 每项带 ref，null 标题写 JSON null，与 sessions 共用出处编号；引擎能引用 playback 出处。 */
    @Test fun devicePlaybackItemsCarryRefAndEngineCitesPlayback() = runBlocking {
        val wxFrom = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val wxTo = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val playFrom = AskTime.timestamp("2026-11-13 23:20", false, zone)
        val playTo = AskTime.timestamp("2026-11-13 23:40", false, zone)
        val musicFrom = AskTime.timestamp("2026-11-13 23:50", false, zone)
        val musicTo = AskTime.timestamp("2026-11-13 23:58", false, zone)
        val covered = AskTime.timestamp("2026-11-14 00:50", false, zone)
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({
            RecordingReader(ScreenTimeResult(
                enabled = true,
                sessions = listOf(NamedAppSession("com.tencent.mm", "微信", wxFrom, wxTo)),
                coverageUntil = covered,
                gaps = emptyList(),
                devicePlayback = listOf(
                    DevicePlayback("app.podcast.cosmos", "小宇宙", "某集", "副标题", playFrom, playTo),
                    DevicePlayback("com.tencent.qqmusic", "QQ音乐", null, null, musicFrom, musicTo),
                ),
            ))
        }, zone).execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources)
        assertEquals(
            """{"totals":[{"ref":1,"name":"微信","package":"com.tencent.mm","minutes":45}],"sessions":[{"ref":1,"name":"微信","package":"com.tencent.mm","start":"2026-11-13 23:10","end":"2026-11-13 23:55"}],"devicePlayback":[{"ref":2,"name":"小宇宙","package":"app.podcast.cosmos","title":"某集","subtitle":"副标题","start":"2026-11-13 23:20","end":"2026-11-13 23:40"},{"ref":3,"name":"QQ音乐","package":"com.tencent.qqmusic","title":null,"subtitle":null,"start":"2026-11-13 23:50","end":"2026-11-13 23:58"}],"coverageUntil":"2026-11-14 00:50","gaps":[],"truncated":false}""",
            out,
        )
        assertEquals(
            listOf(
                AskSource("screen", wxFrom, wxFrom, "微信 使用", "23:10–23:55 微信"),
                AskSource("playback", playFrom, playFrom, "小宇宙 播放", "23:20–23:40 小宇宙《某集》"),
                AskSource("playback", musicFrom, musicFrom, "QQ音乐 播放", "23:50–23:58 QQ音乐《》"),
            ),
            sources,
        )
        var round = 0
        var result: AskEngineCore.Update? = null
        engine(TestStream { _, _, _, _ ->
            if (round++ == 0) reply("", listOf(tool("a", "apps_during", """{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""")))
            else reply("晚上在放小宇宙[^2]", emptyList())
        }, JSONArray(AskDefinitions.json()), { name, args, engineSources ->
            assertEquals("apps_during", name)
            JSONObject(AskScreenToolsCore({
                RecordingReader(ScreenTimeResult(
                    enabled = true,
                    sessions = listOf(NamedAppSession("com.tencent.mm", "微信", wxFrom, wxTo)),
                    coverageUntil = covered,
                    gaps = emptyList(),
                    devicePlayback = listOf(
                        DevicePlayback("app.podcast.cosmos", "小宇宙", "某集", "副标题", playFrom, playTo),
                    ),
                ))
            }, zone).execute(args.toString(), engineSources))
        }, { _, _ -> }).answer("", emptyList(), "晚上放了什么") { result = it }
        assertNull(result!!.error)
        assertEquals("playback", result!!.sources.single().kind)
        assertEquals("小宇宙 播放", result!!.sources.single().title)
    }

    /** 必测 6：明细超预算时截断 devicePlayback，totals 与已放下的 sessions 完整。 */
    @Test fun playbackTruncatesAfterSessionsAndKeepsTotals() = runBlocking {
        val start0 = AskTime.timestamp("2026-11-13 00:00", false, zone)
        val sessions = listOf(NamedAppSession("a.a", "A", start0, start0 + 60_000L))
        val playbacks = (0 until 200).map { i ->
            val start = start0 + i * 60_000L
            DevicePlayback("p.p", "P", "T$i", null, start, start + 60_000L)
        }
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({ RecordingReader(ScreenTimeResult(true, sessions, null, emptyList(), playbacks)) }, zone)
            .execute("""{"from":"2026-11-13 00:00","to":"2026-11-13 17:00"}""", sources)
        assertTrue(out.length <= 6000)
        val json = JSONObject(out)
        assertTrue(json.getBoolean("truncated"))
        assertEquals(1, json.getJSONArray("totals").length())
        assertEquals(1, json.getJSONArray("sessions").length())
        assertEquals(1, json.getJSONArray("totals").getJSONObject(0).getInt("minutes"))
        val kept = json.getJSONArray("devicePlayback")
        assertTrue(kept.length() in 1 until 200)
        assertEquals(1 + kept.length(), sources.size)
        assertEquals("playback", sources.last().kind)
        assertTrue(out.contains(""""truncated":true"""))
    }

    private val privacyJson =
        """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"用户关闭了整理时带上 App 与播放信息"}"""

    @Test fun privacyOffReturnsNoticeWithoutQuerying() = runBlocking {
        var called = 0
        val out = AskScreenToolsCore({ called++; error("不应查库") }, zone, includeAppPlayback = { false })
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", mutableListOf())
        assertEquals(0, called)
        assertEquals(privacyJson, out)
    }

    @Test fun screenOffReturnsNoticeWithoutQuerying() = runBlocking {
        var called = 0
        val out = AskScreenToolsCore({ called++; error("不应查库") }, zone, screenEnabled = { false })
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", mutableListOf())
        assertEquals(0, called)
        assertEquals(disabledJson, out)
    }

    @Test fun titleOffStripsPlaybackFromSerializedResult() = runBlocking {
        val wxFrom = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val wxTo = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val playFrom = AskTime.timestamp("2026-11-13 23:20", false, zone)
        val playTo = AskTime.timestamp("2026-11-13 23:40", false, zone)
        val reader = RecordingReader(ScreenTimeResult(
            enabled = true,
            sessions = listOf(NamedAppSession("com.tencent.mm", "微信", wxFrom, wxTo)),
            coverageUntil = wxTo,
            gaps = emptyList(),
            devicePlayback = listOf(DevicePlayback("app.podcast.cosmos", "小宇宙", "某集", null, playFrom, playTo)),
        ))
        val out = AskScreenToolsCore({ reader }, zone, playbackTitlesEnabled = { false })
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", mutableListOf())
        assertEquals(1, reader.calls)
        assertFalse(out.contains("小宇宙"))
        assertTrue(out.contains(""""devicePlayback":[]"""))
        assertTrue(out.contains("微信"))
    }

    private class RecordingReader(private val result: ScreenTimeResult) : ScreenTimeReader {
        var calls = 0
        var from = 0L
        var until = 0L
        override suspend fun sessions(from: Long, until: Long): ScreenTimeResult {
            calls++; this.from = from; this.until = until
            return result
        }
    }
}
