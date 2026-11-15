package app.resound.digest

import app.resound.net.llmOptionsJson
import org.json.JSONObject
internal fun applyLlmOptions(preset: LlmPreset, body: JSONObject, thinking: Boolean = false) {
    val result = JSONObject(llmOptionsJson(preset, body.toString(), thinking))
    body.keys().asSequence().toList().forEach { body.remove(it) }
    result.keys().forEach { body.put(it, result.get(it)) }
}
