package app.resound.recordings

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.envsound.EnvSoundRow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordingSources177Test {
    @Test fun loadMetadataNamesSelectionAndEstimateMatchOldRepository() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val root = File(context.cacheDir, "recordings177").apply { mkdirs() }
        val outside = File(context.cacheDir, "outside177.ogg").apply { writeText("outside") }
        val oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = LocalDate.of(2026, 11, 1)
        val at = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            for (id in 1L..6L) {
                val file = if (id == 5L) outside else File(root, "$id.${if (id == 6L) "wav" else if (id == 2L) "OGG" else "ogg"}").apply { writeText("ogg-$id") }
                db.capture().segment(SegmentEntity(id, at + id * 3_600_000, at + id * 3_600_000 + 61_000, "mic", file.path, file.length(), if (id == 2L) "Pending" else if (id == 3L) "SkippedEnv" else "Done"))
            }
            db.memory().insertEvent(MemoryEventEntity(1, at, at + 10_000_000, "会议", "标题/带:非法?符号，", "", "[]", "[]", null, null, null, false, false, at, "fake"))
            db.memory().insertEvent(MemoryEventEntity(2, at + 10_000_000, at + 20_000_000, "媒体", "媒体", "", "[]", "[]", null, null, null, false, false, at, "fake"))
            db.transcribe().insertLines(listOf(
                TranscriptLineEntity(2, 1, at + 3_600_200, at + 3_600_900, "第二句\n换行", "tag", null, 1),
                TranscriptLineEntity(1, 1, at + 3_600_000, at + 3_600_100, "首句", "tag", "我", 1),
                TranscriptLineEntity(4, 4, at + 14_400_000, at + 14_400_500, "媒体原话", null, null, 2)))
            db.envSound().insert(EnvSoundRow(3, at + 10_800_000, at + 10_861_000, File(root, "3.ogg").path, "Media", null, "", "", "{}", true, true))
            val source = RecordingSources(db, root)
            fun render(rows: List<RecordingSource>) = rows.joinToString("\n") { "${it.id}|${it.day}|${it.stem}|${it.durationText}|${it.completed}|${it.speech}|${it.excluded}|${it.text}" }
            val all = source.load()
            val range = RecordingRange(day, day)
            val selected = RecordingZip.select(all, range, RecordingPackOptions(skipMedia = true))
            val out = listOf("all=${render(all)}", "metadata0=${render(source.metadata(false))}", "metadata1=${render(source.metadata(true))}",
                "selected=${selected.map { it.id }}/estimate=${RecordingZip.estimate(selected)}", "name=${RecordingZip.name(range)}",
                "timed=${RecordingZip.name(range.copy(fromTime = LocalTime.of(1, 0), toTime = LocalTime.of(2, 0)))}",
                "reverse=${runCatching { RecordingZip.select(all, RecordingRange(day.plusDays(1), day), RecordingPackOptions()) }.exceptionOrNull()?.message}")
            val text = out.joinToString("\n", postfix = "\n")
            val target = File("src/test/resources/repo177/RecordingSources/all.txt")
            assertEquals(target.readText(), text)
        } finally { db.close(); root.deleteRecursively(); outside.delete(); TimeZone.setDefault(oldZone) }
    }
}
