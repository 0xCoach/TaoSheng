package app.resound.voiceprint

import android.util.AtomicFile
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import java.io.*
import java.security.MessageDigest

/** 样本之外的可重建缓存；不增表。调用方在 gate 内读写，先落缓存再提交处理时间。 */
internal class VoiceprintFeatureCache(private val directory: File?) {
    data class Feature(val vector: FloatArray?, val durationMillis: Long)
    private val memory = mutableMapOf<String, Feature>() // 仅供内存数据库使用。

    fun key(segment: SegmentEntity, lines: List<TranscriptLineEntity>): String {
        val identity = buildString {
            append("campplus-v1|").append(segment.id).append('|').append(segment.path)
            append('|').append(segment.startAt).append('|').append(segment.endAt)
            lines.forEach { append('|').append(it.id).append(':').append(it.startAt).append(':')
                .append(it.endAt).append(':').append(VoiceprintAudio.groupKey(it)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun read(key: String): Feature? {
        if (directory == null) return memory[key]
        return try {
            DataInputStream(AtomicFile(File(directory, key)).openRead()).use { input ->
                require(input.readInt() == 1)
                val duration = input.readLong().also { require(it >= 0) }
                val vector = if (input.readBoolean()) FloatArray(192) { input.readFloat() } else null
                if (vector != null) VoiceprintMath.unit(vector)
                require(input.read() == -1)
                Feature(vector, duration)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }

    fun write(key: String, feature: Feature) {
        if (directory == null) { memory[key] = feature; return }
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, key))
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(1); output.writeLong(feature.durationMillis)
            output.writeBoolean(feature.vector != null)
            feature.vector?.forEach { output.writeFloat(it) }
            output.flush()
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun remove(key: String) {
        memory.remove(key)
        directory?.let { dir ->
            // AtomicFile 的正常文件与中断写入残留一起清掉。
            for (suffix in listOf("", ".bak", ".new")) {
                val file = File(dir, key + suffix)
                check(file.delete() || !file.exists())
            }
        }
    }

    fun clear() {
        memory.clear()
        directory?.listFiles()?.forEach { check(it.delete() || !it.exists()) }
    }
}
