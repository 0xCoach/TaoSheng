package app.resound.devices.android

import android.app.Application
import android.content.Context
import app.resound.backup.BackupArchive
import app.resound.backup.BackupSettings
import app.resound.devices.LinkedDevice
import app.resound.devices.driver.*
import app.resound.devices.hub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class AndroidDevicePortsTest {
    @Test fun negotiatedMtuAndTooLongWriteNeverSplits() = runBlocking {
        val writes = mutableListOf<ByteArray>()
        val fake = object : LinkedDevice {
            override var mtu = 0
            override suspend fun read(service: String, characteristic: String) = byteArrayOf(1)
            override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) { writes += bytes }
            override fun notifications(service: String, characteristic: String) = emptyFlow<ByteArray>()
            override suspend fun close() {}
        }
        val link = AndroidGattLink(fake)
        assertEquals(20, link.maxWriteLength(true)); assertEquals(20, link.maxWriteLength(false))
        fake.mtu = 247
        assertEquals(244, link.maxWriteLength(true)); assertEquals(244, link.maxWriteLength(false))
        link.write("s", "c", ByteArray(244), false)
        try { link.write("s", "c", ByteArray(245)); fail("必须拒绝") }
        catch (e: DeviceException) { assertEquals(ErrorCode.WriteTooLong, e.code) }
        assertEquals(1, writes.size); assertEquals(244, writes.single().size)
    }
    @Test fun repositoryIdentityNamespacesAndSettingsBackupRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("device-drivers", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = AndroidHubRepository(prefs)
        val identity = AndroidDevicePlatform.legacyIdentity("AA:BB:CC:DD:EE:FF")
        assertEquals("android:CardDevice:AA:BB:CC:DD:EE:FF", identity.id.value)
        assertEquals(IdScope.PlatformLocal, identity.scope)
        val selected = DriverSelection("handle", "test", identity.id)
        repo.save(selected)
        repo.store("test", "id:${identity.id.value}").apply { putString("progress", "42"); putBytes("token", byteArrayOf(1, 2)) }
        repo.store("test:a", "b").putString("same", "one")
        repo.store("test", "a:b").putString("same", "two")
        repo.setDisabled("test", "id:${identity.id.value}", DisableReason.FaultLimit)
        val dir = File(context.cacheDir, "hub-backup").apply { mkdirs() }
        BackupSettings.capture(context, dir) { _, _ -> "" }
        assertTrue(BackupArchive.allowed("shared_prefs/device-drivers.xml"))
        prefs.edit().clear().commit()
        BackupSettings.apply(context, dir) { _, _, _ -> }
        assertEquals(listOf(selected), repo.selections())
        assertEquals("42", repo.store("test", "id:${identity.id.value}").getString("progress"))
        assertArrayEquals(byteArrayOf(1, 2), repo.store("test", "id:${identity.id.value}").getBytes("token"))
        assertEquals(DisableReason.FaultLimit, repo.disabled("test", "id:${identity.id.value}"))
        assertEquals("one", repo.store("test:a", "b").getString("same"))
        assertEquals("two", repo.store("test", "a:b").getString("same"))
        repo.forget("handle"); assertTrue(repo.selections().isEmpty())
        assertTrue(repo.store("test", "id:${identity.id.value}").keys().isEmpty())
    }
    @Test fun scanStartBudgetWaitsAfterFiveAndRecoversAtThirtySeconds() {
        var now = 0L
        val budget = ScanStartBudget(object : ClockPort {
            override fun nowEpochMillis() = now
            override fun monotonicMillis() = now
            override fun zoneOffsetMinutes() = 0
        })
        repeat(5) { assertEquals(0L, budget.waitMillis()); budget.started() }
        assertEquals(30_000L, budget.waitMillis()); now = 29_999; assertEquals(1L, budget.waitMillis())
        now = 30_000; assertEquals(0L, budget.waitMillis())
    }
}
