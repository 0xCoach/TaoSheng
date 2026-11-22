package app.resound.night
import android.content.Context
import app.resound.audio.SegmentEncoder
import app.resound.platform.FileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class NightShare(private val context: Context,private val owner: AndroidNight) {
    suspend fun export(nightId: Long): Pair<FileRef,Boolean>? = withContext(Dispatchers.IO) {
        val detail=owner.store.detail(nightId) ?: return@withContext null
        val eligible=detail.events.filter {it.kind==NightEventKind.Snore && it.feedback!=Feedback.No && it.audioAvailable && it.clipId!=null && it.id !in detail.vetoed}
        val confirmed=eligible.filter {it.feedback==Feedback.Yes};val candidates=confirmed.ifEmpty {eligible}
        var bestPcm: ShortArray?=null;var bestEnergy=-1.0
        for(event in candidates) {
            val clip=owner.store.dao.clip(event.clipId!!) ?: continue;val path=clip.path ?: continue
            val pcm=owner.files.read(path);if(pcm.isEmpty())continue
            val energy=pcm.toList().chunked(8000).map { block->block.sumOf {it.toDouble()*it}/block.size }
            val start=NightExports.loudestStart(energy,8000).coerceAtMost((pcm.size-15*16_000).coerceAtLeast(0))
            val selected=pcm.copyOfRange(start,minOf(pcm.size,start+15*16_000));val level=selected.sumOf {it.toDouble()*it}/selected.size
            if(level>bestEnergy) {bestEnergy=level;bestPcm=selected}
        }
        val pcm=bestPcm ?: return@withContext null
        cleanup()
        val target=File(File(context.cacheDir,"night-share").apply {mkdirs()},"night-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.m4a")
        FileRef(aac(pcm,target).path) to confirmed.isEmpty()
    }
    /**
     * 卡 220：导出夜里任意一段还没删的录音（整段，转成 m4a）。不改片段的保留期限；录音已删返回 null。
     * [name] 是给人看的文件名（不含扩展名），例如“鼾声 11月21日 02点14分”。
     */
    suspend fun exportClip(clipId: Long,name: String): FileRef? = withContext(Dispatchers.IO) {
        val ref=owner.store.audio(clipId) ?: return@withContext null
        val path=owner.store.dao.clip(clipId)?.path ?: return@withContext null
        val pcm=owner.files.read(path).takeIf {it.isNotEmpty()} ?: return@withContext null
        check(File(ref.id).isFile)
        cleanup()
        val safe=name.replace(Regex("[^\\p{L}\\p{N} _-]"),"").trim().take(40).ifEmpty {"夜里的声音"}
        // 每次一个子目录：文件名可读又不会互相覆盖
        val dir=File(File(context.cacheDir,"night-share"),java.util.UUID.randomUUID().toString()).apply {mkdirs()}
        FileRef(aac(pcm,File(dir,"$safe.m4a")).path)
    }
    private fun aac(pcm: ShortArray,target: File): File {
        val raw=File(target.path+".pcm")
        try {
            raw.outputStream().buffered().use {out->pcm.forEach {out.write(it.toInt() and 255);out.write((it.toInt() ushr 8) and 255)}}
            SegmentEncoder.encodeAac(raw,target);check(target.length()>0)
            val extractor=android.media.MediaExtractor()
            try {
                extractor.setDataSource(target.path)
                check(extractor.trackCount==1)
                val format=extractor.getTrackFormat(0)
                check(format.getString(android.media.MediaFormat.KEY_MIME)==android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
                val duration=format.getLong(android.media.MediaFormat.KEY_DURATION)
                check(kotlin.math.abs(duration-pcm.size*1_000_000L/16_000)<250_000) { "AAC 时长不符" }
                extractor.selectTrack(0)
                check(extractor.readSampleData(java.nio.ByteBuffer.allocate(64*1024),0)>0) { "AAC 无可播放帧" }
            } finally {extractor.release()}
            return target
        } catch(e: Exception) {target.delete();throw e} finally {raw.delete()}
    }
    /** 分享出去的副本留一天；单段导出放在子目录里，一并清掉 */
    fun cleanup() {
        val root=File(context.cacheDir,"night-share");val old=System.currentTimeMillis()-86_400_000
        root.walkBottomUp().filter {it!=root}.forEach {if(it.isFile && it.lastModified()<=old || it.isDirectory && it.listFiles().isNullOrEmpty() && it.lastModified()<=old)it.delete()}
    }
}
