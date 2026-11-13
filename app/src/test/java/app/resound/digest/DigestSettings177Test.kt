package app.resound.digest

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestSettings177Test {
    @Test fun oldPreferencesClampingWritesAndFailedCommitMatch() {
        val context: Context = RuntimeEnvironment.getApplication()
        val real = context.getSharedPreferences("digest", 0)
        real.edit().clear().putBoolean("includeSituation", true).putInt("pauseMinutes", 999)
            .putInt("maxWaitMinutes", -3).putBoolean("deepThinking", true).commit()
        context.getSharedPreferences("transcribe", 0).edit().putInt("interval", 77).commit()
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
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "digest") prefs else super.getSharedPreferences(name, mode)
        }
        val settings = AndroidDigestSettings(wrapped, LlmClient())
        fun state(s: AndroidDigestSettings) = "${s.includeSituation.value}/${s.pauseMinutes.value}/${s.maxWaitMinutes.value}/${s.intervalMinutes.value}/${s.deepThinking.value}"
        fun stored() = listOf("includeSituation", "pauseMinutes", "maxWaitMinutes", "interval", "deepThinking").associateWith { real.all[it]?.let { v -> "${v::class.simpleName}:$v" } }
        val out = mutableListOf("initial=${state(settings)}")
        var changed = 0; settings.onIntervalChanged = { changed++ }; commits = 0
        settings.setIncludeSituation(false); settings.setPauseMinutes(-9); settings.setMaxWaitMinutes(999)
        settings.setIntervalMinutes(999); settings.setDeepThinking(false)
        out += "new=${state(settings)}/commits=$commits/changed=$changed/stored=${stored()}"
        out += "reload=${state(AndroidDigestSettings(wrapped, LlmClient()))}"
        fail = true
        out += "failure=" + runCatching { settings.setIntervalMinutes(30) }.isFailure
        out += "kept=${state(settings)}/changed=$changed/stored=${stored()}"
        val text = out.joinToString("\n", postfix = "\n")
        val target = File("src/test/resources/repo177/AndroidDigestSettings/preferences.txt")
        assertEquals(target.readText(), text)
    }
}
