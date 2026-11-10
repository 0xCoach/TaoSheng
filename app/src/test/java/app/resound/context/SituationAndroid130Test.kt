package app.resound.context

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import app.resound.backup.BackupSettings
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.LlmClient
import app.resound.envsound.EnvSoundRow
import app.resound.location.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SituationAndroid130Test {
    @Test fun settingDefaultsOffPersistsAndTravelsWithBackup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("digest", 0)
        prefs.edit().clear().commit()
        val client = LlmClient()
        val settings = AndroidDigestSettings(context, client)
        val folder = File(context.cacheDir, "situation-settings").apply { mkdirs() }
        try {
            assertFalse(settings.includeSituation.value)
            settings.setIncludeSituation(true)
            assertTrue(settings.includeSituation.value)
            assertTrue(AndroidDigestSettings(context, client).includeSituation.value)
            BackupSettings.capture(context, folder) { _, _ -> "" }
            settings.setIncludeSituation(false)
            BackupSettings.apply(context, folder) { _, _, _ -> }
            assertTrue(AndroidDigestSettings(context, client).includeSituation.value)
        } finally { folder.deleteRecursively(); prefs.edit().clear().commit() }
    }

    @Test fun roomLookupUsesOnlyCacheUpdatesAfterEditsAndMeasuresEventCardQueries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val day = LocalDate.of(2026, 11, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val minute = 60_000L
        val lat = 30.0; val lng = 120.0
        try {
            db.withTransaction {
                repeat(288) { i ->
                    val start = day + i * 5 * minute
                    db.location().insert(LocationPoint(at = start, lat = lat, lng = lng, accuracy = 10f, provider = "test"))
                    db.capture().segment(SegmentEntity(i + 1L, start, start + minute, "mic", "/synthetic-$i", 0, "Done"))
                    db.envSound().insert(EnvSoundRow(i + 1L, start, start + minute, "", "Keep", null, "Keyboard", "", "", false, false))
                }
                db.places().put(PlaceRow(Places.cell(lat, lng), "", "缓存地址", "系统", 1))
            }
            val repository = LocationRepository.create(context, db, scope)
            repository.settings.setEnabled(true)
            val lookup = LocalSituationLookup.create(db, repository)
            val from = day + 60 * minute
            val to = from + 10 * minute
            val coldStart = System.nanoTime()
            assertEquals("缓存地址", lookup.placeDuring(from, to)?.name)
            assertEquals(listOf("键盘声"), lookup.envDuring(from, to))
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
            val timings = (0 until 100).map {
                val started = System.nanoTime()
                lookup.placeDuring(from, to)
                lookup.envDuring(from, to)
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            println("130 performance: 288 points + 288 sounds, cold pair=${coldMs}ms, warm pair median=${timings[50]}ms p95=${timings[94]}ms max=${timings.last()}ms")
            repository.places.setManual(lat, lng, "手动公司", "手动地址", home = true)
            withTimeout(3000) {
                while (lookup.placeDuring(from, to)?.name != "手动公司") delay(10)
            }
            assertTrue(lookup.placeDuring(from, to)!!.home)
            // 缓存命中后权限被撤销也必须立刻隐藏。
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            assertNull(lookup.placeDuring(from, to))
            assertEquals(listOf("键盘声"), lookup.envDuring(from, to))
            db.envSound().wrong(13)
            db.envSound().wrong(14)
            withTimeout(3000) {
                while (lookup.envDuring(from, to).isNotEmpty()) delay(10)
            }
        } finally { scope.cancel(); db.close() }
    }
}
