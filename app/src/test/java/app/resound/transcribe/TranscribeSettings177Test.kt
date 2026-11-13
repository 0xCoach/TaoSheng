package app.resound.transcribe

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import app.resound.transcribe.local.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class TranscribeSettings177Test {
    @Test fun preferencesDefaultsAtomicCustomAndFailureMatchOldImplementation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val real = context.getSharedPreferences("transcribe", 0)
        val local = context.getSharedPreferences("local-asr", 0)
        real.edit().clear().putString("source", "bad").putString("backend", "bad").putString("localLanguage", "bad")
            .putString("endpointMode", "bad").putInt("interval", 999).commit()
        local.edit().clear().putString("actual", "旧状态").putString("npuCheck", "pass:synthetic").commit()
        var fail = false; var commits = 0
        val prefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val edit = real.edit()
                return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit") { commits++; if (fail) false else edit.commit() }
                    else method.invoke(edit, *(args ?: emptyArray())).let { if (it is SharedPreferences.Editor) proxy else it }
                } as SharedPreferences.Editor
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "transcribe") prefs else super.getSharedPreferences(name, mode)
        }
        val s = AndroidTranscribeSettings(wrapped)
        fun state() = "${s.source.value}/${s.backend.value}/${s.language.value}/${s.endpointMode.value}/${s.customPrompt.value}/${s.intervalMinutes.value}/${s.realtime.value}/${s.wifiOnly.value}/${s.localStatus.value}/${s.ready.value}/${s.hasKey.value}"
        val out = mutableListOf("initial=${state()}/npu=${s.npuCheck("synthetic")}/${s.npuCheck("other")}")
        var changed = 0; s.onChanged = { changed++ }
        s.setSource(TranscribeSource.Local); out += "localDefault=${s.realtime.value}/${s.credential()}"
        s.setSource(TranscribeSource.Doubao); out += "cloudDefault=${s.realtime.value}"
        s.setRealtime(false); s.setSource(TranscribeSource.Local); out += "explicit=${s.realtime.value}"
        s.setBackend(LocalBackend.Cpu); s.setLanguage(LocalAsrLanguage.Chinese); s.setLanguage(LocalAsrLanguage.Chinese)
        shadowOf(Looper.getMainLooper()).idle()
        out += "local=${state()}/npu=${s.npuCheck("synthetic")}/revisionChanged=${s.modelRevision.value > 0}"
        s.recordNpuCheck("new", false); out += "npuRecorded=${s.npuCheck("new")}/${local.getString("npuCheck", null)}"
        s.setLocalStatus("合成状态"); s.setCustomPrompt(true); s.setIntervalMinutes(60); s.setWifiOnly(false)
        s.setSource(TranscribeSource.Custom); commits = 0
        s.saveCustomAll(" https://example.invalid/asr?group=1 ", " synthetic-model ", "", CustomEndpointMode.Full)
        out += "custom=${state()}/endpoint=${s.customEndpoint}/model=${s.customModel}/commits=$commits"
        out += "stored=${real.all.toSortedMap()}"
        out += "invalid=${runCatching { s.setIntervalMinutes(-1) }.exceptionOrNull()?.message}"
        val before = changed; fail = true
        out += "wifiFailure=${runCatching { s.setWifiOnly(true) }.isFailure}/${s.wifiOnly.value}/unchanged=${before == changed}"
        // 旧 saveCustom 忽略 commit 返回值；保留这一既有行为，不在搬家时顺手修正。
        s.saveCustom("https://example.invalid/new", "new-model", CustomEndpointMode.Auto)
        out += "customFailedCommit=${s.customEndpointMode}/${s.customEndpoint}/${s.customModel}"
        fail = false
        val again = AndroidTranscribeSettings(wrapped)
        out += "reload=${again.source.value}/${again.backend.value}/${again.language.value}/${again.endpointMode.value}/${again.customPrompt.value}/${again.intervalMinutes.value}/${again.realtime.value}/${again.wifiOnly.value}"
        val text = out.joinToString("\n", postfix = "\n")
        val target = File("src/test/resources/repo177/AndroidTranscribeSettings/preferences.txt")
        assertEquals(target.readText(), text)
    }
}
