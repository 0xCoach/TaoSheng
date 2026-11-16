package app.resound.capture

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.transcribe.PlaybackDigestSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SegmentFilesPlaybackDigestTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val samples = ShortArray(CaptureTuning.MIN_SPEECH_SAMPLES)

    @Test fun producerPlaybackFileRoundTripSetsInitialDigest() = runBlocking {
        context.deleteDatabase("segment-playback-digest.db")
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val spool = File(context.filesDir, "segment-spool")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files1 = SegmentFiles(context, db.capture(), scope1)
        files1.awaitReady()
        scope1.coroutineContext[Job]!!.cancelAndJoin()
        val produced = listOf(
            record(files1, 1_700_000_000_000L, playbackSamples = samples.size.toLong(), include = false),
            record(files1, 1_700_000_010_000L, playbackSamples = samples.size.toLong(), include = true),
            record(files1, 1_700_000_020_000L, playbackSamples = 0, include = true),
        )
        assertEquals(listOf("1000:true:false", "1000:true:true", "0:true:true"), produced.map { it.second })
        produced.forEach { (base, bytes) ->
            val onDisk = File(spool, "$base.playback")
            assertTrue(onDisk.isFile)
            assertEquals(bytes, onDisk.readText())
        }
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files2 = SegmentFiles(context, db.capture(), scope2)
        try {
            files2.awaitReady()
            val rows = withTimeout(8_000) {
                var found = emptyList<app.resound.data.SegmentEntity>()
                while (found.size < 3) {
                    found = db.transcribe().allAudio().sortedBy { it.startAt }
                    if (found.size < 3) delay(20)
                }
                found
            }
            assertEquals(listOf(1, 2, 0), rows.map { it.playbackDigest })
            assertEquals(listOf("SkippedPlayback", "SkippedPlayback", "Pending"), rows.map { it.status })
            assertEquals(listOf(1000L, 1000L, 0L), rows.map { it.playbackMillis })
            produced.forEachIndexed { i, (_, bytes) ->
                val parsed = PlaybackDigestSettings.parseFile(bytes)
                assertEquals(parsed.first, rows[i].playbackMillis)
                assertEquals(PlaybackDigestSettings.initial(1000, parsed.first, parsed.third), rows[i].playbackDigest)
            }
        } finally {
            scope2.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }

    private fun record(
        files: SegmentFiles,
        epoch: Long,
        playbackSamples: Long,
        include: Boolean,
    ): Pair<String, String> {
        context.getSharedPreferences("transcribe", 0).edit()
            .putBoolean("skipPlayback", true)
            .putBoolean(PlaybackDigestSettings.KEY, include)
            .commit()
        val sink = files.sink(epoch)
        sink.begin(0)
        sink.write(samples)
        sink.playback(playbackSamples)
        sink.confirmed()
        val spool = File(context.filesDir, "segment-spool")
        val recording = spool.listFiles()!!.single { it.extension == "recording" && it.name.startsWith("${epoch}_") }
        val base = recording.nameWithoutExtension
        val playback = File(spool, "$base.playback")
        assertTrue("producer 应写出 .playback", playback.isFile)
        val bytes = playback.readText()
        plantOgg(recording)
        sink.end(samples.size.toLong(), true)
        return base to bytes
    }

    private fun plantOgg(recording: File) {
        val start = recording.nameWithoutExtension.substringBefore('_').toLong()
        val local = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        val folder = File(context.filesDir, "segments/${local.toLocalDate()}").apply { mkdirs() }
        val base = File(folder, "${local.format(DateTimeFormatter.ofPattern("HH-mm-ss-SSS"))}_${recording.nameWithoutExtension}")
        File(base.path + ".ogg").writeBytes(byteArrayOf(1, 2, 3, 4))
    }
}
