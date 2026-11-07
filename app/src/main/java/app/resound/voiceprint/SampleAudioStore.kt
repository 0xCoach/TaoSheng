package app.resound.voiceprint

import java.io.File

/**
 * 声纹样本的声音副本（16k 单声道 wav），供人物页回放。
 * 只保存本功能上线后新增的样本；之前的样本服务端不提供下载，原话样本则回退到原录音截取。
 */
class SampleAudioStore(private val dir: File?) {
    fun file(name: String): File? = dir?.let { File(it, "$name.wav") }?.takeIf { it.isFile }
    fun exists(name: String) = file(name) != null
    fun save(name: String, pcm: ShortArray) {
        val d = dir ?: return
        if (pcm.isEmpty()) return
        d.mkdirs()
        val target = File(d, "$name.wav"); val tmp = File(d, "$name.wav.tmp")
        app.resound.remote.writeSpeechWav(tmp, pcm)
        if (!tmp.renameTo(target)) tmp.delete()
    }
    /** 把已有 wav（如刚上传成功的样本）移入；失败则删除源文件 */
    fun adopt(name: String, source: File) {
        val d = dir ?: run { source.delete(); return }
        d.mkdirs()
        if (!source.renameTo(File(d, "$name.wav"))) source.delete()
    }
    fun delete(name: String) { dir?.let { File(it, "$name.wav").delete() } }
    /** 从原录音临时截取的回放文件，每次覆盖 */
    fun temp(pcm: ShortArray): File? {
        val d = dir ?: return null
        if (pcm.isEmpty()) return null
        d.mkdirs()
        return File(d, "playback.tmp.wav").also { app.resound.remote.writeSpeechWav(it, pcm) }
    }
}
