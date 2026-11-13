package app.resound.capture

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class CaptureStats177Test {
    @Test fun allReadFlowsMatchOldRepositoryAcrossDstBoundary() = runBlocking {
        val previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val date = LocalDate.of(2026, 11, 1)
            val a = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); val b = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dao = db.capture(); val repo = RoomCaptureStatsRepository(RuntimeEnvironment.getApplication(), dao, app.resound.platform.SystemZone)
            val text = StringBuilder(); fun save(key: String, value: Any?) { text.appendLine("$key=$value") }
            save("empty", repo.dayStats(date).first())
            dao.segment(SegmentEntity(1, a - 1000, a + 1000, "mic", "/synthetic/1", 10))
            dao.segment(SegmentEntity(2, b - 1000, b + 1000, "mic", "/synthetic/2", 20))
            dao.heartbeat(HeartbeatEntity(at = a + 1000, intervalStart = a - 1000, running = true, session = "test"))
            dao.heartbeat(HeartbeatEntity(at = b + 1000, intervalStart = b - 1000, running = false, session = "test"))
            dao.event(EventEntity(at = a + 100, type = "Killed", detail = "合成原因", batteryPercent = 80, charging = false, gapStart = a - 100, gapEnd = a + 100))
            dao.event(EventEntity(at = a + 200, type = "Recovered", detail = "system-sticky", batteryPercent = null, charging = null))
            dao.event(EventEntity(at = a + 300, type = "FutureUnknown", detail = null, batteryPercent = null, charging = null))
            dao.event(EventEntity(at = b + 500, type = "Killed", detail = null, batteryPercent = null, charging = null, gapStart = b - 500, gapEnd = b + 500))
            repeat(4) { i ->
                dao.battery(BatteryEntity(at = a + i * 600_000, percent = 80 - i, charging = false))
                dao.appPower(AppPowerEntity(at = a + i * 600_000L, elapsedMs = i * 600_000L, session = "test", cpuMs = i * 1000L,
                    txBytes = i * 1_000_000L, rxBytes = i * 1_000_000L, micMs = i * 600_000L, charging = false, chargeEpoch = 0, capacityMah = 4880.0))
            }
            save("segments", repo.segments(date).first()); save("events", repo.watchdogEvents(date).first()); save("stats", repo.dayStats(date).first())
            val fixture = File("src/test/resources/repo177/RoomCaptureStatsRepository/queries.txt")
            assertEquals(fixture.readText(), text.toString())
        } finally { db.close(); TimeZone.setDefault(previous) }
    }
}
