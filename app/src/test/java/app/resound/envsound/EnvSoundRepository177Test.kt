package app.resound.envsound

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import app.resound.data.*
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EnvSoundRepository177Test {
    @Test fun queriesAtomicSettingsAndCorrectionsMatchOldRepository() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "env177.wav").apply { writeBytes(byteArrayOf(1)) }
        val date = LocalDate(2026, 11, 1)
        val at = java.time.LocalDate.parse(date.toString()).atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val real = context.getSharedPreferences("env177", 0)
        real.edit().clear().putString("skip", "invalid-old").putBoolean("tags", false).commit()
        var commits = 0; var fail = false; var downloads = 0; var queued = 0
        val prefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val edit = real.edit()
                // 调用链必须返回同一个计数代理，保持旧单次多键 commit 可观察。
                return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "commit" -> { commits++; if (fail) false else edit.commit() }
                        else -> method.invoke(edit, *(args ?: emptyArray())).let { if (it is SharedPreferences.Editor) proxy else it }
                    }
                } as SharedPreferences.Editor
            }
        }
        try {
            val r = LocalEnvSoundRepository(db, prefs, scope, MutableStateFlow(EnvModelState.Ready), { downloads++ },
                { error("此夹具不触发推理") }, { queued++ }, now = { at + 90_000_000L }, zoneProvider = ZoneProvider { "America/New_York" }, io = kotlinx.coroutines.Dispatchers.IO)
            val out = mutableListOf("initial=${r.settings.value}/${r.model.value}")
            r.downloadModel(); out += "disabledDownload=$downloads"
            val settings = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.MarkMedia, tags = true)
            r.update(settings)
            withTimeout(5000) { while (downloads != 1) delay(5) }
            out += "stored=${real.all.toSortedMap()}/commits=$commits/state=${r.settings.value}"
            fail = true; out += "failure=" + runCatching { r.update(settings.copy(tags = false)) }.isFailure
            out += "afterFailure=${real.all.toSortedMap()}/state=${r.settings.value}"; fail = false
            db.capture().segment(SegmentEntity(1, at + 3_590_000, at + 7_210_000, "mic", file.path, 1, "SkippedEnv"))
            db.envSound().insert(EnvSoundRow(1, at + 3_590_000, at + 7_210_000, file.path, "Skippable", "合成原因", "Rain,missing", "Knock", "{}", true, false, elapsedMs = 7))
            db.memory().insertEvent(MemoryEventEntity(1, at + 3_590_000, at + 7_210_000, "其他", "合成", "摘要", "[]", "[]", null, null, null, false, false, at, "fake"))
            out += "flagged=${r.flagged(date).first()}"
            out += "events=${r.eventSounds(date).first()}/filter=${r.eventsWith(EnvTag.Rain, SpecialSound.Knock).first()}"
            out += "hourly=${r.hourly(date).first()}/stats=${r.stats(3650).first()}"
            out += "presentation=${r.presentationSnapshot(at, at + 90_000_000)}/one=${r.eventSound(1)}/absent=${r.eventSound(9)}"
            out += "audio=${r.audio(1) != null}/${r.audio(9)}"
            out += "restore=${r.restore(1).isSuccess}/status=${db.transcribe().segment(1).status}/queued=$queued"
            r.reportWrong(1)
            withTimeout(5000) { while (queued != 2) delay(5) }
            out += "correction=${r.flagged(date).first()}/stats=${r.stats(3650).first()}"
            out += "missing=${r.restore(9).exceptionOrNull()?.message}"
            file.delete(); out += "expired=${r.restore(1).exceptionOrNull()?.message}/audio=${r.audio(1)}"
            out += "diagnostics=${r.diagnostics()}"
            val text = out.joinToString("\n", postfix = "\n")
            val target = File("src/test/resources/repo177/LocalEnvSoundRepository/all.txt")
            assertEquals(target.readText(), text)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); file.delete() }
    }
}
