package app.resound.backup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import app.resound.devices.AndroidPairedDeviceStore
import app.resound.devices.android.AndroidHubRepository
import app.resound.data.CaptureDatabase
import app.resound.data.open
import app.resound.data.readSqlBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PreferenceBackup190Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private fun prefs(name: String) = context.getSharedPreferences(name, 0)
    @Before fun setup() {
        (PreferenceBackupPolicy.backedUp + PreferenceBackupPolicy.excluded.keys).forEach { prefs(it).edit().clear().commit() }
        root = File(context.cacheDir, "prefs190-${UUID.randomUUID()}").apply { mkdirs() }
        listOf("backup-restore", "backup-restore-done", "restore-journal", "backup-restore-failed").forEach {
            File(context.noBackupFilesDir, it).deleteRecursively()
        }
        context.deleteDatabase("capture.db")
    }
    @After fun cleanup() { root.deleteRecursively(); context.deleteDatabase("capture.db") }

    @Test fun everyPreferenceFileInProductionSourceHasExplicitClassification() {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile }
        val names = mutableSetOf<String>()
        val literal = Regex("getSharedPreferences\\(\\s*\"([^\"]+)\"")
        val constant = Regex("getSharedPreferences\\(\\s*(\\w+)")
        val secret = Regex("SecretStore\\(\\s*\\w+,\\s*([^,]+),")
        for (path in listOf("app/src/main/java", "shared/src/androidMain", "devices/src/androidMain")) {
            File(repo, path).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val source = file.readText()
                literal.findAll(source).forEach { names += it.groupValues[1] }
                secret.findAll(source).forEach { use ->
                    Regex("\"([^\"]+)\"").findAll(use.groupValues[1]).forEach { names += it.groupValues[1] }
                }
                constant.findAll(source).forEach { use ->
                    val declaration = Regex("(?:const\\s+)?val\\s+${use.groupValues[1]}\\s*=\\s*\"([^\"]+)\"")
                    declaration.find(source)?.let { names += it.groupValues[1] }
                }
            }
        }
        assertTrue("扫描不应意外退化成空集合", names.size >= 55)
        assertEquals(emptySet<String>(), PreferenceBackupPolicy.backedUp intersect PreferenceBackupPolicy.excluded.keys)
        assertEquals("新增设置文件必须分类", emptySet<String>(), names - PreferenceBackupPolicy.backedUp - PreferenceBackupPolicy.excluded.keys)
        assertTrue(PreferenceBackupPolicy.excluded.values.all { it.isNotBlank() })
        assertEquals(PreferenceBackupPolicy.secretPrefs, BackupSettings.stores.keys)
        assertEquals(BackupSettings.prefs.map { "$it.xml" }.toSet(), BackupArchive.safePrefs)
    }

    @Test fun newlyIncludedChoicesAndBackupPassphraseRoundTrip() {
        prefs("quick_prefs").edit().putInt("pauseMinutes", 37).putString("topicKind", "Training").commit()
        prefs("device-ui").edit().putBoolean("lightOffWarned", true).commit()
        prefs("onboarding").edit().putBoolean("dismissed_Backup", true).putLong("count_wizard_done", 9).commit()
        prefs("backup-encryption-secrets").edit().putString("passphrase", "fixture-device-ciphertext").commit()
        BackupSettings.capture(context, root) { store, key ->
            assertEquals("backup-encryption-secrets", store); assertEquals("passphrase", key); "fixture-passphrase"
        }
        val exported = JSONObject(File(root, "secrets.json").readText())
        assertEquals("fixture-passphrase", exported.getJSONObject("backup-encryption-secrets").getString("passphrase"))
        for (name in listOf("quick_prefs", "device-ui", "onboarding")) prefs(name).edit().clear().commit()
        val restored = mutableMapOf<Pair<String, String>, String>()
        BackupSettings.apply(context, root) { store, key, value -> restored[store to key] = value }
        assertEquals(37, prefs("quick_prefs").getInt("pauseMinutes", 0))
        assertTrue(prefs("device-ui").getBoolean("lightOffWarned", false))
        // 引导提示也属于本机引导状态，沿用卡132整体不备份的约定。
        assertFalse(prefs("onboarding").getBoolean("dismissed_Backup", false))
        assertFalse(prefs("onboarding").contains("count_wizard_done"))
        assertEquals("fixture-passphrase", restored["backup-encryption-secrets" to "passphrase"])
    }

    @Test fun appPlaybackAndPlaybackDigestSwitchesSurviveBackupRoundTrip() {
        prefs("digest").edit().putBoolean("includeAppPlayback", false).commit()
        prefs("transcribe").edit().putBoolean("includePlaybackDigest", true).putBoolean("skipPlayback", false).commit()
        BackupSettings.capture(context, root)
        prefs("digest").edit().clear().commit()
        prefs("transcribe").edit().clear().commit()
        BackupSettings.apply(context, root)
        assertFalse(prefs("digest").getBoolean("includeAppPlayback", true))
        assertTrue(prefs("transcribe").getBoolean("includePlaybackDigest", false))
        assertFalse(prefs("transcribe").getBoolean("skipPlayback", true))
    }

    @Test fun playbackTitleSwitchSurvivesBackupRoundTrip() {
        prefs("playback-title").edit()
            .putBoolean("playbackTitleEnabled", true)
            .putLong("playbackTitleEnabledAt", 42)
            .commit()
        BackupSettings.capture(context, root)
        prefs("playback-title").edit().clear().commit()
        BackupSettings.apply(context, root)
        assertTrue(prefs("playback-title").getBoolean("playbackTitleEnabled", false))
        assertEquals(42L, prefs("playback-title").getLong("playbackTitleEnabledAt", 0))
    }

    @Test fun brokenStructuredSettingDoesNotLeakItsValueInDiagnostics() {
        prefs("folder-imports").edit().putString("folders", "private-folder-content").commit()
        val failure = runCatching { BackupSettings.capture(context, root) }.exceptionOrNull()
        assertTrue(failure is BackupFailure)
        assertFalse(failure!!.message.orEmpty().contains("private-folder-content"))
        assertFalse(app.resound.diagnostics.FailureNotes.dump().contains("private-folder-content"))
        assertFalse(File(root, "settings.json").exists())
    }

    @Test fun oldDaySettingsIgnoreRuntimeFilesAndMixedRuntimeKeys() {
        prefs("capture").edit().putBoolean("enabled", true).putLong("heartbeat", 999).commit()
        prefs("push").edit().putString("channels", "[]").putString("footprint-sent-8", "old-day").commit()
        prefs("digest").edit().putString("digestMode", "Timed").putLong("timedSince", 999).commit()
        prefs("folder-imports").edit().putString("folders", "[{\"id\":\"uri\",\"name\":\"目录\",\"kind\":\"Training\",\"enabled\":true,\"baseline\":true,\"lastScanAt\":999}]").commit()
        prefs("devices").edit().putString("paired", "[{\"address\":\"AA:BB:CC:DD:EE:FF\",\"cardId\":\"card\",\"name\":\"设备\",\"clockOffset\":999,\"gapStart\":999}]").commit()
        AndroidHubRepository(prefs("device-drivers")).store("test", "device").apply {
            putString("progress", "42"); putBytes("token", byteArrayOf(1, 2))
        }
        BackupSettings.capture(context, root)
        val settingsFile = File(root, "settings.json")
        val settings = JSONObject(settingsFile.readText())
        assertFalse(settings.has("pipeline-schedule")); assertFalse(settings.has("setup"))
        assertFalse(settings.getJSONObject("capture").has("heartbeat"))
        assertFalse(settings.getJSONObject("push").has("footprint-sent-8"))
        assertFalse(settings.getJSONObject("digest").has("timedSince"))
        // 校时偏移是解释设备旧录音、补传所需的数据，不能当成可丢弃缓存。
        assertTrue(settings.getJSONObject("devices").getJSONObject("paired").getString("value").contains("clockOffset"))
        assertFalse(settings.getJSONObject("folder-imports").getJSONObject("folders").getString("value").contains("lastScanAt"))
        // 模拟旧日卷仍夹带运行字段：可读取，但不得恢复到本机。
        fun item(value: Any) = JSONObject().put("type", if (value is Boolean) "boolean" else "long").put("value", value)
        settings.getJSONObject("capture").put("heartbeat", item(999L))
        settings.put("setup", JSONObject().put("autostart", item(true)))
        settings.put("pipeline-schedule", JSONObject().put("lastAttempt", item(999L)))
        settingsFile.writeText(settings.toString())
        prefs("capture").edit().putLong("heartbeat", 77).commit()
        prefs("setup").edit().putBoolean("autostart", false).commit()
        prefs("pipeline-schedule").edit().putLong("lastAttempt", 77).commit()
        BackupSettings.apply(context, root)
        assertTrue(prefs("capture").getBoolean("enabled", false))
        assertEquals(77L, prefs("capture").getLong("heartbeat", 0))
        assertFalse(prefs("setup").getBoolean("autostart", true))
        assertEquals(77L, prefs("pipeline-schedule").getLong("lastAttempt", 0))

        // 用独立偏好空间模拟另一台手机：必要的设备数据可用，本机运行状态不被源包覆盖。
        val peer = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("peer-190-$name", mode)
        }
        val names = PreferenceBackupPolicy.backedUp + PreferenceBackupPolicy.excluded.keys
        names.forEach { peer.getSharedPreferences(it, 0).edit().clear().commit() }
        try {
            peer.getSharedPreferences("capture", 0).edit().putLong("heartbeat", 88).commit()
            BackupSettings.apply(peer, root)
            val paired = AndroidPairedDeviceStore(peer.getSharedPreferences("devices", 0)).read().single()
            assertEquals(999L, paired.clockOffset); assertEquals(999L, paired.gapStart)
            val restored = AndroidHubRepository(peer.getSharedPreferences("device-drivers", 0)).store("test", "device")
            assertEquals("42", restored.getString("progress"))
            assertArrayEquals(byteArrayOf(1, 2), restored.getBytes("token"))
            assertEquals(88L, peer.getSharedPreferences("capture", 0).getLong("heartbeat", 0))
        } finally { names.forEach { peer.getSharedPreferences(it, 0).edit().clear().commit() } }
    }

    private fun database(): CaptureDatabase = CaptureDatabase.open(context).also {
        it.readSqlBlocking("PRAGMA user_version") { row -> check(row.step()); row.getLong(0) }
    }
    private fun withDatabase(block: (CaptureDatabase) -> Unit) {
        val db = database()
        try { block(db) } finally { db.close() }
    }
    private fun apply(verified: File) {
        BackupRestore.stage(context, verified)
        BackupRestore.applyPending(context)
        assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
        assertNull(prefs("backup").getString("error", null))
    }

    @Test fun oldFullZipWithoutAddedFilesRestoresAndKeepsAbsentNewChoices() {
        val stage = File(root, "old").apply { mkdirs() }
        withDatabase { db ->
            val snapshot = File(stage, BackupArchive.DATABASE).apply { parentFile!!.mkdirs() }
            BackupArchive.snapshot(db, snapshot)
        }
        File(stage, "shared_prefs/capture.xml").apply { parentFile!!.mkdirs() }.writeText(
            "<map><boolean name=\"enabled\" value=\"false\"/><long name=\"heartbeat\" value=\"999\"/><string name=\"quietRules\">[]</string></map>"
        )
        val zip = File(root, "old.zip")
        BackupArchive.pack(stage, zip, 1, "old", CaptureDatabase.VERSION)
        val verified = File(root, "verified-old")
        BackupArchive.verify(zip, verified, CaptureDatabase.VERSION)
        prefs("capture").edit().putBoolean("enabled", true).putLong("heartbeat", 77).commit()
        prefs("quick_prefs").edit().putInt("pauseMinutes", 23).commit()
        apply(verified)
        assertFalse(prefs("capture").getBoolean("enabled", true))
        assertEquals(77L, prefs("capture").getLong("heartbeat", 0))
        assertEquals(23, prefs("quick_prefs").getInt("pauseMinutes", 0))
    }

    @Test fun fullArchiveAddsPortableFilesAndNewXmlAndRestoresThem() {
        prefs("quick_prefs").edit().putInt("pauseMinutes", 37).commit()
        prefs("capture").edit().putLong("heartbeat", 999).commit()
        val stage = File(root, "full").apply { mkdirs() }
        val zip = File(root, "new.zip")
        withDatabase { BackupArchive.create(context, it, stage, zip, 1) }
        ZipFile(zip).use { archive ->
            listOf("settings.json", "secrets.json", "shared_prefs/quick_prefs.xml", "shared_prefs/capture.xml", "manifest.json").forEach {
                assertNotNull(it, archive.getEntry(it))
            }
            assertFalse(archive.getInputStream(archive.getEntry("shared_prefs/capture.xml")).bufferedReader().readText().contains("heartbeat"))
        }
        val verified = File(root, "verified-new")
        BackupArchive.verify(zip, verified, CaptureDatabase.VERSION)
        prefs("quick_prefs").edit().putInt("pauseMinutes", 1).commit()
        prefs("capture").edit().putLong("heartbeat", 77).commit()
        apply(verified)
        assertEquals(37, prefs("quick_prefs").getInt("pauseMinutes", 0))
        assertEquals(77L, prefs("capture").getLong("heartbeat", 0))
    }
}
