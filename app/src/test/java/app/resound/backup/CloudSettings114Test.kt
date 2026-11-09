package app.resound.backup

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class CloudSettings114Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("backup", 0)
    private val values = mutableMapOf<String, String>()
    private val secrets = object : CloudSecrets {
        override fun has(key: String) = values.containsKey(key)
        override fun read(key: String) = values[key].orEmpty()
        override fun write(key: String, value: String) {
            values[key] = value; context.getSharedPreferences("backup-secrets", 0).edit().putString(key, "ciphertext").commit()
        }
        override fun remove(key: String) { values.remove(key) }
    }
    private val settings get() = CloudSettings(prefs, secrets)
    private val s3 = S3Settings("minio", "http://storage.local:9000", "us-east-1", "backup-bucket", "备份/", true)
    @Before fun setup() { (BackupSettings.prefs + BackupSettings.stores.keys).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }; values.clear() }
    @Test fun legacyMigratesWithoutWritingAndSwitchesKeepEachCredential() {
        prefs.edit().putString("account", "old@example.com").commit(); secrets.write("password", "old-password")
        assertEquals(CloudKind.Nutstore, settings.kind); assertEquals("old-password", settings.password())
        assertFalse(prefs.contains("cloud"))
        settings.save(CloudKind.S3, "access", "s3-secret", null, s3)
        settings.save(CloudKind.WebDav, "dav-user", "dav-password", "https://dav.example.com/folder/", null)
        settings.save(CloudKind.Nutstore, "old@example.com", null, null, null)
        assertEquals("old-password", settings.password())
        settings.save(CloudKind.S3, "access", null, null, s3)
        assertEquals("s3-secret", settings.password()); assertEquals("access", settings.account)
        assertEquals("dav-password", values["webDavPassword"])
        assertFalse(prefs.all.values.any { it.toString().contains("s3-secret") })
    }
    @Test fun backupRestoresSelectionAddressesAndAllSecrets() {
        settings.save(CloudKind.Nutstore, "old@example.com", "nutstore-secret", null, null)
        settings.save(CloudKind.WebDav, "dav-user", "dav-secret", "https://dav.example.com/dir/", null)
        settings.save(CloudKind.S3, "access", "s3-secret", null, s3)
        val directory = File(context.cacheDir, "cloud114").apply { mkdirs() }
        try {
            BackupSettings.capture(context, directory) { store, key -> assertEquals("backup-secrets", store); values.getValue(key) }
            prefs.edit().clear().commit(); values.clear()
            BackupSettings.apply(context, directory) { _, key, value -> secrets.write(key, value) }
            assertEquals(CloudKind.S3, settings.kind); assertEquals(s3, settings.s3)
            assertEquals("https://dav.example.com/dir/", settings.webDavUrl)
            assertEquals("s3-secret", settings.password()); assertEquals(3, values.size)
            assertEquals("nutstore-secret", values["password"]); assertEquals("dav-secret", values["webDavPassword"])
        } finally { directory.deleteRecursively() }
    }
    @Test fun restoringOldSettingsSelectsNutstoreAndKeepsMissingFields() {
        prefs.edit().putString("account", "legacy@example.com").commit()
        val directory = File(context.cacheDir, "legacy114").apply { mkdirs() }
        try {
            BackupSettings.capture(context, directory) { _, _ -> "" }
            settings.save(CloudKind.S3, "access", "secret", null, s3)
            BackupSettings.apply(context, directory) { _, _, _ -> }
            assertEquals(CloudKind.Nutstore, settings.kind); assertEquals("legacy@example.com", settings.account)
            assertEquals(s3, settings.s3)
        } finally { directory.deleteRecursively() }
    }
    @Test fun addressPolicyAndSecretReuseRejectUnsafeConfiguration() {
        listOf("http://10.0.0.2/dav", "http://172.16.0.2/", "http://192.168.1.2/", "http://nas.local/", "https://example.com/").forEach { CloudAddress.parse(it) }
        listOf("http://172.32.0.2/", "http://example.com/", "http://10.1.2.999/", "http://10.1.example.2.3/", "https://user:pass@example.com/", "https://example.com/?key=x").forEach {
            assertTrue(it, runCatching { CloudAddress.parse(it) }.isFailure)
        }
        assertTrue(runCatching { settings.save(CloudKind.S3, "access", null, null, s3) }.isFailure)
        assertEquals("custom", CloudPresets.all.last().id); assertEquals(9, CloudPresets.all.size)
    }
}
