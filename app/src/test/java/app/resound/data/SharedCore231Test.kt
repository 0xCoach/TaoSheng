package app.resound.data

import android.app.Application
import android.content.Context
import app.resound.platform.AndroidDispatchers
import app.resound.platform.AndroidEntitlements
import app.resound.platform.AndroidLogger
import app.resound.platform.LocalFileStore
import app.resound.platform.PlatformServices
import app.resound.platform.PrefsKeyValueStore
import app.resound.platform.ProFeature
import app.resound.platform.SystemNow
import app.resound.platform.SystemZone
import app.resound.runtime.createCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SharedCore231Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "shared-core-231.db"
    private val database = lazy { CaptureDatabase.open(context, name) }

    @After fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
        context.deleteDatabase(name)
    }

    private fun assertSchemaObjects(type: String, vararg names: String) = runBlocking {
        val actual = database.value.readSql("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)) {
            buildSet { while (it.step()) add(it.getText(0)) }
        }
        assertEquals(emptySet<String>(), names.toSet() - actual)
    }

    @Test fun installsFullTextTablesAndTriggers() {
        assertSchemaObjects("table", "lines_fts", "events_fts", "items_fts")
        assertSchemaObjects("trigger", "search_line_insert", "search_event_update", "search_item_delete")
    }

    @Test fun installsImportRecordTriggers() {
        assertSchemaObjects("trigger", "import_segment_deleted", "folder_import_deleted")
    }

    @Test fun installsThingTriggers() {
        assertSchemaObjects("trigger", "things_line_deleted", "things_event_hidden", "things_event_deleted")
    }

    @Test fun installsPendingDigestTrigger() {
        assertSchemaObjects("trigger", "digest_pending_source_deleted")
    }

    @Test fun androidEnablesEveryAdvancedFeature() {
        for (feature in ProFeature.entries) assertTrue(feature.name, AndroidEntitlements.has(feature))
    }

    @Test fun coreKeepsPlatformAndReusesDatabaseLazily() {
        val platform = PlatformServices(
            now = SystemNow,
            zone = SystemZone,
            logger = AndroidLogger,
            keyValueStore = PrefsKeyValueStore(context.getSharedPreferences("platform", Context.MODE_PRIVATE)),
            files = LocalFileStore(AndroidDispatchers),
            dispatchers = AndroidDispatchers,
            entitlements = AndroidEntitlements,
        )
        val core = createCoreRuntime(platform, database)
        assertSame(platform, core.platform)
        assertFalse(database.isInitialized())
        assertEquals(context.getDatabasePath(name).absolutePath, core.database.filePath)
        assertSame(database.value, core.database)
    }
}
