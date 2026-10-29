package app.resound.transcribe.local

import android.os.Bundle
import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import java.io.File
import java.io.IOException

internal class MissingLocalAudioException : IOException()

/** 路径检查与解码属于同一个 audio 阶段；测试可注入解码器，不加载原生识别模型。 */
internal suspend fun readLocalAudio(path: String?, filesDir: File, cacheDir: File,
    decode: suspend (File) -> ShortArray): ShortArray {
    val audio = File(requireNotNull(path)).canonicalFile
    require(audio.path.startsWith(filesDir.canonicalPath + "/") || audio.path.startsWith(cacheDir.canonicalPath + "/"))
    if (!audio.exists()) throw MissingLocalAudioException()
    return decode(audio)
}

/** IPC 只允许阶段、类名和缺失标记，不传异常 message、cause、路径或音频内容。 */
internal object LocalAsrFailure {
    fun write(data: Bundle, phase: String, error: Throwable) {
        data.putString("phase", phase)
        data.putString("exceptionClass", error.javaClass.name)
        data.putBoolean("missingAudio", phase == "audio" && error is MissingLocalAudioException)
    }
    fun exception(data: Bundle): AsrException {
        val type = when (data.getString("phase")) {
            "audio" -> AsrError.Audio
            "speakers" -> AsrError.Speakers
            else -> AsrError.LocalEngine
        }
        return AsrException(type, detail = if (type == AsrError.Audio && data.getBoolean("missingAudio"))
            "录音文件已不存在" else type.human)
    }
    fun diagnostic(data: Bundle): String {
        val phase = data.getString("phase").takeIf { it in setOf("audio", "speakers", "asr") } ?: "asr"
        val name = data.getString("exceptionClass")?.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,160}")) } ?: "Unknown"
        return "本机转写 phase=$phase errorType=${exception(data).type.name} exceptionClass=$name"
    }
}
