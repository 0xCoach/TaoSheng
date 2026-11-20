package app.resound.digest

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 存取只保存模型能力和计数，不含密钥、原话、响应；平台负责本机持久化。 */
interface LlmAdaptiveStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}
fun llmModelKey(preset: LlmPreset): String = "${preset.id.length}:${preset.id}${preset.model}"
