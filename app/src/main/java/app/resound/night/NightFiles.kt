package app.resound.night

import android.content.Context
import app.resound.audio.SegmentEncoder
import app.resound.transcribe.AsrAudioReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class NightFiles(private val context: Context) {
    private val root=File(context.filesDir,"night")
    fun resolve(path: String): File { require(path.matches(Regex("[0-9]+/[0-9]+-[a-z0-9]+[.](ogg|m4a)")));return File(root,path) }
    suspend fun exists(path: String)=withContext(Dispatchers.IO) { resolve(path).isFile }
    fun path(file: File)=file.relativeTo(root).invariantSeparatorsPath
    suspend fun encode(id: Long,start: Long,pcm: ShortArray): String = withContext(Dispatchers.IO) {
        val dir=File(root,id.toString()).apply { mkdirs() }
        val base=File(dir,"$start-${java.util.UUID.randomUUID().toString().replace("-","")}")
        val raw=File(base.path+".pcm")
        try {
            raw.outputStream().buffered().use { out->pcm.forEach { out.write(it.toInt() and 255);out.write((it.toInt() ushr 8) and 255) } }
            path(SegmentEncoder.encode(raw,base))
        } finally { raw.delete() }
    }
    suspend fun read(path: String): ShortArray = withContext(Dispatchers.IO) { AsrAudioReader(context.cacheDir).readPcm(resolve(path)) }
    fun cleanupPath(path: String)=resolve(path).path
    companion object {
        fun scores(scores: Map<String,Float>): ByteArray { val bytes=Json.encodeToString(scores.toSortedMap().values.toList()).encodeToByteArray();val out=java.io.ByteArrayOutputStream();DeflaterOutputStream(out).use { it.write(bytes) };return out.toByteArray() }
        fun scores(bytes: ByteArray): List<Float> = if(bytes.isEmpty())emptyList() else Json.decodeFromString(InflaterInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() })
        fun envelope(points: ByteArray): ByteArray { val out=java.io.ByteArrayOutputStream();DeflaterOutputStream(out).use { it.write(points) };return out.toByteArray() }
        fun unpack(bytes: ByteArray)=InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
    }
}
