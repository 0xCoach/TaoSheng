package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 兼容旧库中 0、1、2 三种整理标记。用户放行只更新标记。 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PlaybackHeldDayTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private val at = 1_700_000_000_000L
    private fun atDate() = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.UTC).date

    private class Settings : TranscribeSettings {
        override val hasKey = MutableStateFlow(false)
        override val intervalMinutes = MutableStateFlow(30)
        override val wifiOnly = MutableStateFlow(true)
        override fun saveKey(key: String) { hasKey.value = true }
        override fun clearKey() { hasKey.value = false }
        override fun setIntervalMinutes(minutes: Int) { intervalMinutes.value = minutes }
        override fun setWifiOnly(value: Boolean) { wifiOnly.value = value }
        override suspend fun test() = Result.success("合成服务")
    }

    @Before fun setup() {
        context.deleteDatabase("playback-held-day-191a.db")
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun close() = runBlocking {
        scope.coroutineContext[ kotlinx.coroutines.Job ]!!.cancelAndJoin(); db.close()
    }

    private suspend fun insertSegment(id: Long, status: String, playbackDigest: Int) {
        db.capture().segment(SegmentEntity(id, at + id * 1000, at + id * 1000 + 500, "mic", "synthetic-$id", 1, status, playbackDigest = playbackDigest))
    }

    @Test fun dayRequiresApprovalOnlyForHeldSegments() = runBlocking {
        insertSegment(1, "Done", 1)
        insertSegment(2, "Done", 0)
        insertSegment(3, "Done", 2)
        val repo = TranscriptStoreRepository(db.transcribe(), Settings(), scope, ZoneProvider { "UTC" }, NowMillis { at }, Dispatchers.IO,
            flowOf<Long>(), flowOf(emptyList()), { "2026-11-15" }, null to null, { _, _ -> },
            { false }, { null }, { error("放行整理不触发转写") }, { })
        val day = repo.day(atDate()).first().sortedBy { it.segmentId }
        assertEquals(
            listOf(PlaybackDigestReview.AwaitingApproval, PlaybackDigestReview.NotRequired, PlaybackDigestReview.NotRequired),
            day.map { it.playbackDigestReview },
        )

        repo.applyPlaybackAction(1, PlaybackTranscriptAction.IncludeInNextDigest)
        withTimeout(5000) {
            while (db.transcribe().segment(1).playbackDigest != 2) kotlinx.coroutines.delay(10)
        }
        assertEquals(PlaybackDigestReview.NotRequired, repo.day(atDate()).first().single { it.segmentId == 1L }.playbackDigestReview)
    }
}
