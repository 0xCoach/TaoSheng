package app.resound.privacy

import app.resound.data.testSql
import app.resound.screentime.*
import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.*
import app.resound.voiceprint.VoiceprintFeatureCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RoomPrivacyActionsTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var privacy: RoomPrivacyActions
    private val now = 2_000_000L
    private var discarded = 0
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "privacy-${System.nanoTime()}").apply { mkdirs() }
        privacy = RoomPrivacyActions(db, discardRecording = { discarded++ }, now = { now })
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    private suspend fun segment(id: Long, start: Long, status: String = "Done") {
        val file = File(directory, "$id.ogg").apply { writeText("test") }
        db.capture().segment(SegmentEntity(id, start, start + 1000, "mic", file.path, file.length(), status))
    }
    private fun eventRow(id: Long) = MemoryEventEntity(id, 1, now, "其他", "事件", "摘要", "[]", "[]", null, null, null, false, createdAt = now, model = "test")
    private suspend fun event(id: Long) { db.memory().insertEvent(eventRow(id)) }
    private suspend fun line(id: Long, segment: Long, event: Long?) {
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, segment, now, now + 1, "原话", "0", eventId = event)))
    }
    private fun item(id: Long, event: Long, line: Long?) = MemoryItemEntity(id, event, "Memo", null, "条目", null, null, null, line, "Pending", now)
    private fun count(table: String): Int = db.testSql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun fileRemovalFailureIsPersistedAndRetriedAfterRecreation() = runBlocking {
        val path = File(directory, "blocked.ogg").apply { mkdirs() }
        File(path, "payload").writeText("synthetic audio") // 非空目录确定性模拟无法删除的路径。
        event(1)
        db.capture().segment(SegmentEntity(1, 1, 2, "mic", path.path, 15, "Done"))
        line(1, 1, 1)
        val warnings = mutableListOf<String>()
        val actions = RoomPrivacyActions(db, diagnostic = { warnings += it })
        assertTrue(runCatching { actions.forgetEvent(1) }.exceptionOrNull() is FileCleanupPending)
        assertEquals(listOf("录音文件删除失败"), warnings)
        assertTrue(path.exists()); assertEquals(0, count("segments"))
        assertEquals(listOf(path.path), db.privacy().pendingCleanup().map { it.path })
        assertTrue(File(path, "payload").delete())
        val recreated = RoomPrivacyActions(db)
        assertEquals(ForgetResult(0, 0, 0, 0), recreated.forgetEvent(1))
        assertFalse(path.exists()); assertTrue(db.privacy().pendingCleanup().isEmpty())
    }

    @Test fun recentKeepsCrossingEventAndDeletesSourceItemsAndPendingAudio() = runBlocking {
        event(1); segment(1, now - 600_001); segment(2, now - 600_000); segment(3, now - 1, "Pending")
        line(1, 1, 1); line(2, 2, 1)
        db.memory().insertItems(listOf(item(1, 1, 1), item(2, 1, 2), item(3, 1, null)))
        assertEquals(ForgetResult(2, 1, 0, 1), privacy.deleteRecent(10))
        assertEquals(1, discarded)
        assertEquals(listOf(1L), db.transcribe().observeSegments().first().map { it.id })
        assertEquals(listOf(1L), db.voiceprint().lines(1).map { it.id })
        assertEquals(1, count("memory_events")); assertEquals(2, count("memory_items"))
        assertTrue(File(directory, "1.ogg").exists()); assertFalse(File(directory, "2.ogg").exists())
        assertFalse(File(directory, "3.ogg").exists()); assertTrue(db.transcribe().candidates(now).isEmpty())
    }
    @Test fun rangeDeletesOnlyInsideAndKeepsRecordingWhenRangeIsPast() = runBlocking {
        segment(1, 100_000); segment(2, 200_000); segment(3, 300_000)
        assertEquals(ForgetPreview(1, 0), privacy.previewRange(150_000, 250_000).copy(audioMillis = 0))
        assertEquals(1, privacy.deleteRange(150_000, 250_000).segments)
        assertEquals(0, discarded)
        assertEquals(listOf(1L, 3L), db.transcribe().observeSegments().first().map { it.id }.sorted())
    }
    @Test fun recentDeletesWholeEventAndItsUnlinkedItems() = runBlocking {
        event(1); segment(1, now - 1); line(1, 1, 1)
        db.memory().insertItems(listOf(item(1, 1, 1), item(2, 1, null)))
        assertEquals(ForgetResult(1, 1, 1, 2), privacy.deleteRecent(10))
        assertEquals(0, count("memory_events")); assertEquals(0, count("memory_items"))
    }
    @Test fun sequentialBatchDeletionWorksBeyondFiveEvents() = runBlocking {
        for (id in 1L..20L) {
            event(id); segment(id, now - id); line(id, id, id)
            db.memory().insertItems(listOf(item(id, id, id)))
        }
        for (id in 1L..20L) {
            assertEquals(ForgetResult(1, 1, 1, 1), privacy.forgetEvent(id))
            if (id == 6L) assertEquals(14, count("memory_events"))
        }
        assertEquals(0, count("memory_events")); assertEquals(0, count("memory_items"))
        assertEquals(0, count("transcript_lines")); assertEquals(0, count("segments"))
    }
    @Test fun forgetEventRemovesAudioSamplesExclusionsAndFeaturesButKeepsEnrollment() = runBlocking {
        event(1); segment(1, 1); line(1, 1, 1)
        db.memory().insertItems(listOf(item(1, 1, 1)))
        db.voiceprint().saveSample(VoiceprintSampleEntity(source = "line", sourceLineId = 1, segmentId = 1, groupKey = "tag:0", vector = byteArrayOf(1), durationMillis = 1000, createdAt = now))
        db.voiceprint().saveSample(VoiceprintSampleEntity(source = "enroll", sourceLineId = null, segmentId = null, groupKey = null, vector = byteArrayOf(1), durationMillis = 1000, createdAt = now))
        db.voiceprint().exclude(VoiceprintExclusionEntity(1, "tag:0", now))
        val cache = VoiceprintFeatureCache(File(directory, "features"))
        val key = cache.key(db.transcribe().segment(1), db.voiceprint().lines(1))
        cache.write(key, VoiceprintFeatureCache.Feature(null, 1000))
        val actions = RoomPrivacyActions(db, removeFeatures = { segment, lines ->
            assertEquals(0, count("segments")) // 文件清理发生在事务提交之后。
            cache.remove(cache.key(segment, lines))
        })
        assertEquals(ForgetResult(1, 1, 1, 1), actions.forgetEvent(1))
        assertEquals(0, count("transcript_lines")); assertEquals(0, count("voiceprint_exclusions"))
        assertEquals("enroll", db.voiceprint().allSamples().single().source)
        assertNull(cache.read(key)); assertFalse(File(directory, "1.ogg").exists())
        assertEquals(ForgetResult(0, 0, 0, 0), actions.forgetEvent(1))
        // 同一录音分属两件事时，不顺带抹掉另一件事的文字。
        event(2); event(3); segment(2, 2); line(2, 2, 2); line(3, 2, 3)
        db.memory().insertItems(listOf(item(2, 2, 2), item(3, 3, 3)))
        assertEquals(ForgetResult(0, 1, 1, 1), privacy.forgetEvent(2))
        assertEquals(3L, db.voiceprint().lines(2).single().id)
        assertEquals(1, count("memory_events")); assertEquals(1, count("memory_items"))
        assertFalse(File(directory, "2.ogg").exists())
    }
    @Test fun forgetSegmentRemovesEmptyEventsAndPreservesOtherEventLines() = runBlocking {
        event(1); event(2); segment(1, 1); segment(2, 2)
        line(1, 1, 1); line(2, 1, 2); line(3, 2, 2)
        db.memory().insertItems(listOf(item(1, 1, null), item(2, 2, 2), item(3, 2, 3)))
        assertEquals(ForgetResult(1, 2, 1, 2), privacy.forgetSegment(1))
        assertEquals(1, count("memory_events")); assertEquals(1, count("memory_items"))
        assertEquals(3L, db.voiceprint().lines(2).single().id)
    }
    @Test fun deletedQueuedAndInflightSegmentsCannotBeTranscribedOrRecreatedByOldResults() = runBlocking {
        segment(1, 1, "Pending"); segment(2, 2, "Pending")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val runner = TranscribeRunner(db.transcribe(), object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                calls++; beforeAudio(); entered.complete(Unit); release.await()
                return AsrResult(emptyList(), 1000, null)
            }
        }, { "test" }, { _, _ -> })
        val work = async { runner.run() }
        entered.await()
        privacy.forgetSegment(1); privacy.forgetSegment(2)
        release.complete(Unit); work.await()
        assertEquals(1, calls); assertEquals(0, count("segments")); assertEquals(0, count("transcript_lines"))
        db.transcribe().complete(SegmentEntity(1, 1, 2, "mic", "missing", 0), listOf(TranscriptLineEntity(1, 1, 1, 2, "旧结果", null)))
        db.memory().saveDigest(listOf(eventRow(1) to listOf(1)), { listOf(item(1, it.single(), 1)) }, listOf(1), now)
        assertEquals(0, count("memory_events")); assertEquals(0, count("memory_items"))
    }

    /** 按时间遗忘同时删屏幕事件、下限提到区间终点；随后的拉取起点不早于该终点。 */
    @Test fun deleteRangeRemovesScreenEventsAndRaisesFloorSoPullStartsAfterIt() = runBlocking {
        db.screenTime().insertEvents(listOf(
            ScreenEventRow(at = now - 30_000, pkg = "a", cls = "", type = 1),
            ScreenEventRow(at = now - 90_000, pkg = "a", cls = "", type = 1)))
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, now)
        privacy.deleteRange(now - 60_000, now)
        assertEquals(1, count("screen_events"))
        assertEquals(now - 90_000, db.screenTime().events(0, now).single().at)
        assertEquals(now, db.screenTime().state(SCREEN_SOURCE)?.floor)
        var sinceFrom = 0L
        val puller = ScreenTimePuller(
            ScreenTimeSettings(readBoolean = { _, _ -> true }, persist = { _, _, _ -> true },
                readLong = { _, def -> def }, now = { now }),
            object : ScreenEventSource {
                override fun permitted() = true
                override suspend fun since(from: Long, until: Long): List<RawScreenEvent> { sinceFrom = from; return emptyList() }
                override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
            }, db.screenTime(), now = { now }, denied = { it is SecurityException })
        puller.pull()
        assertTrue(sinceFrom >= now)
    }

    /** 必测 8：区间内的播放行删除，跨左边界的行截断到 from。 */
    @Test fun deleteRangeRemovesInsidePlaybackAndTruncatesLeftCrossing() = runBlocking {
        val from = now - 60_000
        db.screenTime().insertPlayback(PlaybackRow(sessionKey = "in", pkg = "a", name = "a", title = "内",
            subtitle = null, fromAt = from + 1_000, untilAt = now - 1_000, lastSeenAt = now - 1_000))
        db.screenTime().insertPlayback(PlaybackRow(sessionKey = "left", pkg = "b", name = "b", title = "跨左",
            subtitle = null, fromAt = now - 90_000, untilAt = null, lastSeenAt = now - 10_000))
        privacy.deleteRange(from, now)
        val left = db.screenTime().playbacksOverlapping(0, now + 1).single()
        assertEquals("left", left.sessionKey)
        assertEquals(now - 90_000, left.fromAt)
        assertEquals(from, left.untilAt)
        assertEquals(from, left.lastSeenAt)
    }
    private suspend fun seedPlaybackPrivacy() {
        event(10)
        for ((id, at) in listOf(10L to 10_000L, 20L to 20_000L, 30L to 30_000L)) {
            segment(id, at)
            if (id != 20L) line(id, id, 10)
            db.screenTime().insertEvents(listOf(ScreenEventRow(at = at + 100, pkg = "test", cls = "", type = 1)))
            db.screenTime().insertPlayback(PlaybackRow(sessionKey = "$id", pkg = "test", name = "test",
                title = "test", subtitle = null, fromAt = at, untilAt = at + 1000, lastSeenAt = at + 1000))
            for (source in listOf(SCREEN_SOURCE, PLAYBACK_SOURCE))
                db.screenTime().recordGap(ScreenCoverageGapRow(source = source, fromAt = at, untilAt = at + 1000, code = "test"))
        }
    }

    private suspend fun assertPlaybackPrivacy(remaining: List<Long>) {
        assertEquals(remaining, db.screenTime().playbacksOverlapping(0, now).map { it.fromAt })
        assertEquals(remaining.map { it + 100 }, db.screenTime().events(0, now).map { it.at })
        for (source in listOf(SCREEN_SOURCE, PLAYBACK_SOURCE))
            assertEquals(remaining, db.screenTime().gaps(source, 0, now).filter { it.code != GapCode.PRIVACY_DELETED }.map { it.fromAt })
    }

    @Test fun eventDeletionClearsOnlyItsSegmentsPlaybackAndGaps() = runBlocking {
        seedPlaybackPrivacy()
        privacy.forgetEvent(10)
        assertPlaybackPrivacy(listOf(20_000L))
    }

    @Test fun segmentDeletionClearsPlaybackAndGaps() = runBlocking {
        seedPlaybackPrivacy()
        privacy.forgetSegment(20)
        assertPlaybackPrivacy(listOf(10_000L, 30_000L))
    }

    @Test fun batchDeletionPreservesPlaybackBetweenSegments() = runBlocking {
        seedPlaybackPrivacy()
        privacy.deleteSegments(listOf(10, 30, 10))
        assertPlaybackPrivacy(listOf(20_000L))
    }

    @Test fun rangeWithoutAudioClearsPlaybackAndBothGapSources() = runBlocking {
        seedPlaybackPrivacy()
        db.testSql.execSQL("DELETE FROM transcript_lines")
        db.testSql.execSQL("DELETE FROM segments")
        privacy.deleteRange(20_000, 21_000)
        assertPlaybackPrivacy(listOf(10_000L, 30_000L))
    }

    private fun queriesAt(clock: Long): ScreenTimeQueries {
        var lastPull = clock
        val settings = ScreenTimeSettings(
            readBoolean = { _, _ -> true },
            persist = { _, longs, _ -> longs[ScreenTimeSettings.LAST_PULL_KEY]?.let { lastPull = it }; true },
            readLong = { _, _ -> lastPull },
            now = { clock },
        )
        val source = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long) = emptyList<RawScreenEvent>()
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        return ScreenTimeQueries(
            db.screenTime(), ScreenTimePuller(settings, source, db.screenTime(), now = { clock }, denied = { false }),
            settings, { true }, { true }, emptySet(), { clock }, PlaybackQueries(db.screenTime()),
        )
    }

    /** 真实删除入口之后走真实查询：被删时段不得再出现使用段。 */
    @Test fun deleteRangeThenQueryDoesNotReplayForgottenWindow() = runBlocking {
        val t = 1_000_000_000_000L
        val actions = RoomPrivacyActions(db, now = { t })
        db.screenTime().insertEvents(listOf(
            ScreenEventRow(at = t, pkg = "private.app", cls = "Main", type = 1),
            ScreenEventRow(at = t + 180_000, pkg = "other.app", cls = "Main", type = 1),
        ))
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, t + 240_000)
        actions.deleteRange(t + 60_000, t + 120_000)
        val forgotten = queriesAt(t + 240_000).sessions(t + 60_000, t + 120_000)
        assertTrue(forgotten.sessions.isEmpty())
        val around = queriesAt(t + 240_000).sessions(t, t + 240_000)
        assertTrue(around.sessions.none { it.from < t + 120_000 && it.until > t + 60_000 })
    }

    /** 尚无拉取状态行时删除也必须留下下限。 */
    @Test fun deleteWithoutPriorPullStateStillLeavesFloor() = runBlocking {
        assertNull(db.screenTime().state(SCREEN_SOURCE))
        privacy.deleteRange(1_000, 2_000)
        assertEquals(2_000L, db.screenTime().state(SCREEN_SOURCE)?.floor)
    }

    /** 在途 since 回调里删除后，提交不得写回已删事件。 */
    @Test fun inFlightPullAfterDeleteDoesNotRewriteForgottenEvents() = runBlocking {
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, 100)
        val source = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> {
                privacy.deleteRange(1_000, 2_000)
                return listOf(RawScreenEvent(1_500, "private.app", "Main", 1))
            }
            override suspend fun appNames(pkgs: Set<String>) = mapOf("private.app" to "私")
        }
        val settings = ScreenTimeSettings(
            readBoolean = { _, _ -> true }, persist = { _, _, _ -> true },
            readLong = { _, def -> def }, now = { now },
        )
        val result = ScreenTimePuller(settings, source, db.screenTime(), now = { now }, denied = { false }).pull()
        assertEquals(PullResult.Pulled(0), result)
        assertEquals(0, db.screenTime().events(0, now + 1).size)
        assertEquals(2_000L, db.screenTime().state(SCREEN_SOURCE)?.floor)
        assertTrue(db.screenTime().names().isEmpty())
    }

    /** 全部删除把下限停在操作时刻，之后新事件仍能采到，名称表清空。 */
    @Test fun deleteAllStopsFloorAtNowAndLaterEventsCanBePulled() = runBlocking {
        db.screenTime().insertEvents(listOf(ScreenEventRow(at = 100, pkg = "old", cls = "", type = 1)))
        db.screenTime().upsertNames(listOf(ScreenAppNameRow("old", "旧应用", 100)))
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, 200)
        privacy.deleteAll()
        assertEquals(0, count("screen_events"))
        assertEquals(0, count("screen_app_names"))
        assertEquals(now, db.screenTime().state(SCREEN_SOURCE)?.floor)
        assertTrue(db.screenTime().state(SCREEN_SOURCE)!!.floor < Long.MAX_VALUE)
        val later = now + 1_000
        val source = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long) = listOf(RawScreenEvent(later, "new.app", "Main", 1))
            override suspend fun appNames(pkgs: Set<String>) = mapOf("new.app" to "新应用")
        }
        val clock = now + 31_000
        val settings = ScreenTimeSettings(
            readBoolean = { _, _ -> true }, persist = { _, _, _ -> true },
            readLong = { _, def -> def }, now = { clock },
        )
        assertEquals(PullResult.Pulled(1), ScreenTimePuller(settings, source, db.screenTime(), now = { clock }, denied = { false }).pull())
        assertEquals(listOf(later), db.screenTime().events(0, clock + 1).map { it.at })
        assertEquals("新应用", db.screenTime().names().single().name)
    }

    /** 部分删除清掉已无事件引用的名称，仍被引用的保留。 */
    @Test fun partialDeleteDropsUnreferencedNamesOnly() = runBlocking {
        db.screenTime().insertEvents(listOf(
            ScreenEventRow(at = 100, pkg = "gone", cls = "", type = 1),
            ScreenEventRow(at = 300, pkg = "kept", cls = "", type = 1),
        ))
        db.screenTime().upsertNames(listOf(ScreenAppNameRow("gone", "已删", 1), ScreenAppNameRow("kept", "仍在", 1)))
        privacy.deleteRange(50, 200)
        assertEquals(listOf("kept"), db.screenTime().names().map { it.pkg })
    }

    /** 没有关联录音的事件删除后，明确区间内的屏幕使用段、事件行和播放行都要清掉。 */
    @Test fun forgetEventWithoutAudioClearsScreenSessionsEventsAndPlayback() = runBlocking {
        val from = 10_000_000L
        val until = 10_120_000L
        val id = db.memory().insertEvent(MemoryEventEntity(
            startAt = from, endAt = until, scene = "其他", title = "测试", summary = "摘要",
            pointsJson = "[]", peopleJson = "[]", place = null, emotion = null, intensity = null,
            important = false, createdAt = now, model = "test",
        ))
        assertTrue(db.privacy().eventSegments(id).isEmpty())
        db.screenTime().insertEvents(listOf(
            ScreenEventRow(at = from + 1_000, pkg = "talk.app", cls = "Main", type = 1),
            ScreenEventRow(at = from + 60_000, pkg = "talk.app", cls = "Main", type = 1),
        ))
        db.screenTime().upsertNames(listOf(ScreenAppNameRow("talk.app", "谈话", from)))
        db.screenTime().insertPlayback(PlaybackRow(
            sessionKey = "talk", pkg = "talk.app", name = "谈话", title = "节目",
            subtitle = null, fromAt = from + 10_000, untilAt = from + 20_000, lastSeenAt = from + 20_000,
        ))
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, until)
        val actions = RoomPrivacyActions(db, now = { until })
        assertEquals(1, actions.forgetEvent(id).events)
        assertNull(db.memory().event(id))
        val result = queriesAt(until + 31_000).sessions(from, until)
        assertTrue(result.sessions.isEmpty())
        assertTrue(db.screenTime().events(from, until).isEmpty())
        assertTrue(db.screenTime().playbacksOverlapping(from, until).isEmpty())
    }

    /** 两次交错删除后，前一次区间里没被第二次覆盖的那截仍受缺口保护，不能被更早的前台事件重放回来。 */
    @Test fun overlappingRangeDeletesKeepProtectionOnUncoveredPrefix() = runBlocking {
        val ten = 1_000_000_000_000L
        val tenThirty = ten + 1_800_000
        val eleven = ten + 3_600_000
        val twelve = ten + 7_200_000
        val nineFifty = ten - 600_000
        db.screenTime().insertEvents(listOf(
            ScreenEventRow(at = nineFifty, pkg = "front.app", cls = "Main", type = 1),
        ))
        db.screenTime().advanceCoveredUntil(SCREEN_SOURCE, twelve)
        val actions = RoomPrivacyActions(db, now = { twelve })
        actions.deleteRange(ten, eleven)
        actions.deleteRange(tenThirty, twelve)
        val around = queriesAt(twelve).sessions(nineFifty, twelve)
        assertTrue(around.sessions.none { it.from < eleven && it.until > ten })
        assertTrue(around.sessions.none { it.from < twelve && it.until > tenThirty })
        assertTrue(queriesAt(twelve).sessions(ten, twelve).sessions.isEmpty())
    }

}
