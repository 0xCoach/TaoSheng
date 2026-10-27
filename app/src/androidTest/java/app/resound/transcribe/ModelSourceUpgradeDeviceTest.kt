package app.resound.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.ResoundApp
import app.resound.backup.BackupArchive
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.LocalAsrConnection
import app.resound.transcribe.local.LocalBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 同签名测试包：升级前导出标准备份，升级后仅核对数据与模型，不更改业务配置。 */
@RunWith(AndroidJUnit4::class)
class ModelSourceUpgradeDeviceTest {
    /** 正式 APK 冒烟：直接调用本地引擎，不切换用户的转写来源或后端设置。 */
    @Test fun releaseLocalRecognition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.resound")
        val audio = File.createTempFile("release-smoke-", ".ogg", context.cacheDir)
        val report = JSONObject()
        try {
            context.assets.open("asr-test.ogg").use { input -> audio.outputStream().use { input.copyTo(it) } }
            withTimeout(90_000) {
                for (backend in listOf(LocalBackend.Cpu, LocalBackend.Npu)) {
                    assertTrue(LocalModels.ready(context, LocalModels.spec(backend)))
                    val start = android.os.SystemClock.elapsedRealtime()
                    val result = LocalAsrConnection.get(context).recognize(AsrAudio(audio, "ogg", 3007), backend) { }
                    assertTrue(result.sentences.isNotEmpty())
                    report.put(backend.name, JSONObject().put("sentences", result.sentences.size)
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start))
                }
            }
            File(context.getExternalFilesDir(null), "modelscope-upgrade-check/release-smoke.json")
                .writeText(report.toString(2))
        } finally { audio.delete() }
    }

    @Test fun snapshotAndCheck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.resound")
        val phase = InstrumentationRegistry.getArguments().getString("phase")!!
        check(phase in listOf("before", "after"))
        val app = context.applicationContext as ResoundApp
        val export = File(context.getExternalFilesDir(null), "modelscope-upgrade-check").apply { mkdirs() }
        val dbFile = File(context.cacheDir, "modelscope-health-${System.currentTimeMillis()}.db")
        BackupArchive.snapshot(app.database, dbFile)
        // FTS4 的完整性检查会使用临时写操作，仅在隔离副本上以读写方式执行。
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        db.rawQuery("PRAGMA quick_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        val counts = JSONObject()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { tables ->
            while (tables.moveToNext()) {
                val name = tables.getString(0)
                db.rawQuery("SELECT count(*) FROM \"${name.replace("\"", "\"\"")}\"", null).use {
                    it.moveToFirst(); counts.put(name, it.getLong(0))
                }
            }
        }
        db.close()
        dbFile.delete()
        val models = JSONObject()
        for (spec in listOf(LocalModels.cpu, LocalModels.npu, LocalModels.speakers)) {
            val file = File(LocalModels.directory(context, spec), spec.modelName)
            models.put(spec.id, JSONObject().put("ready", LocalModels.ready(context, spec))
                .put("bytes", file.length()).put("modified", file.lastModified()))
        }
        val report = JSONObject().put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("tables", counts).put("models", models)
            .put("captureEnabled", context.getSharedPreferences("capture", 0).getBoolean("enabled", false))
        if (phase == "before") {
            val staging = File(context.cacheDir, "modelscope-backup-${System.currentTimeMillis()}").apply { mkdirs() }
            try { BackupArchive.create(context, app.database, staging, File(export, "before.zip"), System.currentTimeMillis()) }
            finally { staging.deleteRecursively() }
        } else {
            val before = JSONObject(File(export, "before.json").readText())
            assertEquals(before.getJSONObject("models").toString(), models.toString())
            for (name in before.getJSONObject("tables").keys()) {
                assertTrue("Table lost rows: $name", counts.getLong(name) >= before.getJSONObject("tables").getLong(name))
            }
            assertEquals(before.getBoolean("captureEnabled"), report.getBoolean("captureEnabled"))
        }
        File(export, "$phase.json").writeText(report.toString(2))
    }
}
