package app.resound.transcribe.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** PCM 为 16 kHz 单声道；边界为采样点，时间戳保存 Float 位模式，不损失精度。 */
@Serializable
data class LocalResumeKey(val segment: String, val samples: Int, val fingerprint: String,
    val recognizer: String, val language: String, val separate: Boolean, val speakerCount: Int,
    val hotwords: String, val speakerModel: String, val speakersEnabled: Boolean, val format: Int = 1)
@Serializable
data class ResumeTurn(val start: Int, val end: Int, val speaker: Int?)
@Serializable
data class ResumeChunk(val start: Int, val end: Int, val speaker: Int?, val overlap: Int, val groupStart: Int)
@Serializable
data class ResumeDecoded(val text: String, val tokens: List<String>, val timestampBits: List<Int>,
    val language: String, val tag: String?)
@Serializable
data class LocalResumeProgress(val key: LocalResumeKey, val turns: List<ResumeTurn>, val speakerDecision: String,
    val chunks: List<ResumeChunk>, val decoded: List<ResumeDecoded> = emptyList()) {
    fun append(value: ResumeDecoded): LocalResumeProgress {
        check(decoded.size < chunks.size)
        return copy(decoded = decoded + value)
    }
    fun matches(expected: LocalResumeKey): Boolean = key == expected && decoded.size <= chunks.size &&
        turns.all { it.start >= 0 && it.end > it.start && it.end <= key.samples } &&
        chunks.all { it.start >= 0 && it.end > it.start && it.end <= key.samples && it.overlap >= 0 }
}
object LocalResumeCodec {
    fun encode(value: LocalResumeProgress): String = Json.encodeToString(value)
    fun decode(value: String): LocalResumeProgress = Json.decodeFromString(value)
}

/** 非失败重试计数；由平台注入持久化与电源状态。 */
@Serializable
data class LocalInterruptionState(val count: Int = 0, val active: Boolean = false) {
    fun interrupted() = LocalInterruptionState((count + 1).coerceAtMost(1_000_000))
    fun deferred(interactive: Boolean, charging: Boolean) = count >= 2 && !interactive && !charging
}
object LocalInterruptionCodec {
    fun encode(value: LocalInterruptionState): String = Json.encodeToString(value)
    fun decode(value: String): LocalInterruptionState = Json.decodeFromString(value)
}
