package app.resound.platform

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PlatformPorts150Test {
    @get:Rule val temporary = TemporaryFolder()

    private fun prefs(name: String) = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun missingValuesUseCallerDefaults() {
        val store = PrefsKeyValueStore(prefs("platform-defaults"))
        assertNull(store.getString("missing"))
        assertEquals(42L, store.getLong("missing", 42L))
        assertTrue(store.getBoolean("missing", true))
    }

    @Test fun writesRoundTripAcrossAdaptersAndNullRemovesString() {
        val prefs = prefs("platform-values")
        val writer = PrefsKeyValueStore(prefs)
        writer.putString("text", "涛声")
        writer.putLong("time", 123L)
        writer.putBoolean("enabled", false)
        val reader = PrefsKeyValueStore(prefs)
        assertEquals("涛声", reader.getString("text"))
        assertEquals(123L, reader.getLong("time", 0L))
        assertFalse(reader.getBoolean("enabled", true))
        writer.putString("text", null)
        assertNull(reader.getString("text"))
        assertFalse(prefs.contains("text"))
    }

    @Test fun localFileRoundTripsAndDeletes() = runBlocking {
        val store = LocalFileStore(AndroidDispatchers)
        val ref = FileRef(temporary.root.resolve("audio.bin").absolutePath)
        assertFalse(store.exists(ref))
        val bytes = byteArrayOf(0, 1, -1, 127)
        store.writeBytes(ref, bytes)
        assertTrue(store.exists(ref))
        assertEquals(4L, store.size(ref))
        assertArrayEquals(bytes, store.readBytes(ref))
        assertTrue(store.delete(ref))
        assertFalse(store.exists(ref))
        assertFalse(store.delete(ref))
    }
}
