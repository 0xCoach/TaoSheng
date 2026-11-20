package app.resound.backup

import android.app.Application
import android.content.Context
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Backup212Test {
    @Test fun bothConfigurationsSecretsHistoryAndTokenRowsRoundTripWithOldDefaults() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir,"backup212").apply { deleteRecursively(); mkdirs() }
        (PreferenceBackupPolicy.backedUp + PreferenceBackupPolicy.excluded.keys).forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        val prefs = context.getSharedPreferences("transcribe",0)
        prefs.edit().putString("customApi","Qwen").putString("endpoint","https://open.invalid/asr")
            .putString("model","open").putString("endpointMode","Auto").putBoolean("customPrompt",false)
            .putString("qwenEndpoint","https://qwen.invalid/asr").putString("qwenModel","qwen")
            .putString("qwenEndpointMode","Full").putBoolean("qwenPrompt",true)
            .putString("customSpeakerFormat","Diarized").putString("customEncryptedKey","opaque-open")
            .putString("qwenEncryptedKey","opaque-qwen").commit()
        val before = prefs.all
        val history = context.getSharedPreferences("search-history",0)
        history.edit().putString("items","[\"涛声\"]").commit()
        try {
            val stage = File(root,"settings").apply { mkdirs() }
            BackupSettings.capture(context,stage) { store,key -> assertEquals("transcribe",store); "plain-$key" }
            assertFalse(File(stage,"settings.json").readText().contains("opaque-"))
            prefs.edit().clear().commit(); history.edit().clear().commit()
            val secrets = mutableMapOf<String,String>()
            BackupSettings.apply(context,stage) { store,key,value -> assertEquals("transcribe",store); secrets[key]=value }
            assertEquals(before.filterKeys { !it.endsWith("EncryptedKey") },prefs.all)
            assertEquals(setOf("customEncryptedKey","qwenEncryptedKey"),secrets.keys)
            assertEquals("[\"涛声\"]",history.getString("items",null))
            val db = CaptureDatabase.open(context,File(root,"source.db").path)
            val snapshot = File(root,"snapshot.db")
            try {
                db.transcribe().insertUsage(AsrUsageEntity("2026-11-20",3000,105,83))
                BackupArchive.snapshot(db,snapshot)
            } finally { db.close() }
            val (version,files) = DayVolumeRows.write(snapshot,File(root,"rows"))
            val target = File(root,"target.db")
            DayVolumeRows.rebuild(context,target,version,files.getValue("state"),files.filterKeys { it!="state" }.values.toList())
            val restored = CaptureDatabase.open(context,target.path)
            try { assertEquals(AsrUsageEntity("2026-11-20",3000,105,83),restored.transcribe().observeUsage().first().single()) }
            finally { restored.close() }
            val day = files.getValue("2026-11-20")
            day.writeText(day.readLines().joinToString("\n",postfix="\n") { line ->
                val row=JSONObject(line); row.getJSONObject("values").remove("inputTokens"); row.getJSONObject("values").remove("outputTokens"); row.toString()
            })
            val oldTarget=File(root,"old.db")
            DayVolumeRows.rebuild(context,oldTarget,43,files.getValue("state"),listOf(day))
            val old=CaptureDatabase.open(context,oldTarget.path)
            try { assertEquals(AsrUsageEntity("2026-11-20",3000),old.transcribe().observeUsage().first().single()) }
            finally { old.close() }
        } finally { root.deleteRecursively() }
    }
}
