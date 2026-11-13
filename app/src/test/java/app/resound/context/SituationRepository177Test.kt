package app.resound.context

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.location.LocationPoint
import app.resound.location.Place
import app.resound.envsound.EnvSoundRow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SituationRepository177Test {
    @Test fun dayClippingAndCacheInvalidationMatchOldRepository() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val zone = ZoneId.of("America/New_York")
            val a = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val b = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli()
            listOf(a, a + 3_600_000, b, b + 3_600_000).forEach { db.location().insert(LocationPoint(at = it, lat = 30.0, lng = 120.0, accuracy = 10f, provider = "synthetic")) }
            db.capture().segment(app.resound.data.SegmentEntity(1, b - 60_000, b + 120_000, "mic", "synthetic", 1))
            db.envSound().insert(EnvSoundRow(1, b - 60_000, b + 120_000, "synthetic", "Keep", null, "Keyboard", "", "", false, false))
            var revision = 0L; var reads = 0; var title = "旧书房"
            val lookup = LocalSituationLookup({ true }, { from, to -> reads++; db.location().betweenNow(from, to) },
                { _, _ -> Place(title, "合成地址", "手动", true) }, db.envSound()::between,
                placeRevision = { revision }, soundRevision = { revision }, zone = { zone }, io = kotlinx.coroutines.Dispatchers.IO, pointBounds = { a to b + 3_600_000 })
            val out = StringBuilder(); fun save(key: String, value: Any?) { out.appendLine("$key=$value") }
            save("stay", lookup.placeDuring(a, a + 1_800_000)); save("cached", lookup.placeDuring(a, a + 600_000)); save("reads", reads)
            save("home", lookup.staysAt("家", Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)); save("sound", lookup.envDuring(b - 60_000, b + 120_000, 3))
            title = "新书房"; revision++
            save("changed", lookup.staysAt("新", a, b + 600_000)); save("reads.afterChange", reads)
            save("empty", lookup.placeDuring(a, a)); save("zeroLimit", lookup.envDuring(a, b, 0))
            val fixture = File("src/test/resources/repo177/LocalSituationLookup/queries.txt")
            assertEquals(fixture.readText(), out.toString())
        } finally { db.close() }
    }
}
