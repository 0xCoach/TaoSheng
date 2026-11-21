package app.resound.digest

import android.content.Context
import android.content.SharedPreferences
import app.resound.net.JsonStreamSupport

internal class AndroidLlmAdaptiveStore(private val prefs: SharedPreferences, private val prefix: String = "") : LlmAdaptiveStore {
    override fun read(key: String) = prefs.getString(prefix + key, null)
    override fun write(key: String, value: String) { check(prefs.edit().putString(prefix + key, value).commit()) }
}
class AndroidJsonStreamSupport(context: Context) : JsonStreamSupport {
    private val prefs = context.getSharedPreferences("llm-stream-support", Context.MODE_PRIVATE)
    override fun nonStreaming(preset: LlmPreset) = prefs.getBoolean(llmModelKey(preset), false)
    override fun rememberNonStreaming(preset: LlmPreset) { check(prefs.edit().putBoolean(llmModelKey(preset), true).commit()) }
}
