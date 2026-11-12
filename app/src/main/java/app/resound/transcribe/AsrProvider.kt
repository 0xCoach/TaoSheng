package app.resound.transcribe

import java.io.File

/** 后续服务商/自建服务实现此接口；地址与资源统一从配置传入。 */
interface AsrProvider {
    suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult
}
data class AsrAudio(val file: File, val format: String, val durationMillis: Long, val separateSpeakers: Boolean = true, val pcmFile: File? = null, val speakerCount: Int? = null)
