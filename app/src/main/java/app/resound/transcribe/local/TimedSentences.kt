package app.resound.transcribe.local

import app.resound.transcribe.AsrSentence
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult

/** 使用 CTC token 时间戳；没有时间戳时只保留整片段，不伪造逐句时间。 */
class TimedSentences {
    private data class UnitText(val text: String, val start: Long, val end: Long, val tag: String?)
    private val units=mutableListOf<UnitText>()
    private val coarse=mutableListOf<AsrSentence>()
    private var previousEnd=0L
    var timedChunks=0; private set
    fun append(result: OfflineRecognizerResult, chunk: PauseChunks.Chunk, tag: String?) = append(result, chunk, { tag }, tag)
    /** 卡 222：一块里可能有几个人说话，每个字按自己的时间查说话人（tagAt 的参数是整段录音里的毫秒） */
    fun append(result: OfflineRecognizerResult, chunk: PauseChunks.Chunk, tagAt: (Long) -> String?, coarseTag: String?) {
        val base=chunk.start/16L;val end=chunk.end/16L
        if(result.tokens.isEmpty() || result.tokens.size!=result.timestamps.size || result.timestamps.any{!it.isFinite()}) {
            if(result.text.any{it.isLetterOrDigit()})coarse+=AsrSentence(base,end,result.text.trim(),coarseTag)
            previousEnd=end;return
        }
        timedChunks++
        val next=result.tokens.flatMapIndexed { i,raw ->
            val text=raw.replace("▁"," ").replace("@@","")
            val at=(base+(result.timestamps[i]*1000).toLong()).coerceIn(base,end)
            val until=if(i+1<result.timestamps.size)minOf(at+400,(base+(result.timestamps[i+1]*1000).toLong()).coerceIn(at,end)) else minOf(end,at+400)
            val tag=tagAt(at)
            text.map { UnitText(it.toString(),at,maxOf(at+1,until).coerceAtMost(end),tag) }
        }.toMutableList()
        if(chunk.overlapSamples>0 && units.isNotEmpty()) {
            val tag=tagAt(base)
            val tailStart=units.indexOfLast { it.start<base-300 || it.tag!=tag }+1
            val oldIndices=(tailStart until units.size).filter { units[it].text.any(Char::isLetterOrDigit) }
            val old=oldIndices.map { units[it] }
            val indices=next.indices.filter { next[it].start<=previousEnd+300 && next[it].text.any(Char::isLetterOrDigit) }
            val a=old.joinToString(""){it.text}.lowercase();val b=indices.joinToString(""){next[it].text}.lowercase()
            var count=minOf(a.length,b.length,80)
            while(count>0 && !a.endsWith(b.take(count)))count--
            // 单字重复只有时间高度重合才去重，避免删掉真实的“对，对”。
            if(count==1 && (old.isEmpty() || indices.isEmpty() || kotlin.math.abs(old.last().start-next[indices.first()].start)>180))count=0
            if(count>0) {
                // 用后一块的完整上下文替换重叠尾部，避免保留前一块在硬边界生成的假句号。
                units.subList(oldIndices[oldIndices.size-count],units.size).clear()
            } else {
                val boundary=base+chunk.overlapSamples/32L
                while(units.lastOrNull()?.let{it.start>=boundary && it.tag==tag}==true)units.removeAt(units.lastIndex)
                while(next.firstOrNull()?.start?.let{it<boundary}==true)next.removeAt(0)
            }
        }
        units+=next;previousEnd=end
    }
    fun result(): List<AsrSentence> {
        val out=mutableListOf<AsrSentence>();val current=mutableListOf<UnitText>()
        fun flush() {
            val useful=current.filter { it.text.isNotBlank() }
            val text=current.joinToString(""){it.text}.trim()
            if(text.any(Char::isLetterOrDigit))out+=AsrSentence(useful.first().start,useful.maxOf{it.end},text,useful.first().tag)
            current.clear()
        }
        for ((index,unit) in units.withIndex()) {
            val last=current.lastOrNull()
            if(last!=null && (last.tag!=unit.tag || unit.start-last.end>=800))flush()
            current+=unit
            val punctuation=unit.text in listOf("。","！","？","!","?","；",";","\n") ||
                unit.text=="." && !(units.getOrNull(index-1)?.text?.all(Char::isDigit)==true && units.getOrNull(index+1)?.text?.all(Char::isDigit)==true)
            if(punctuation)flush()
        }
        flush()
        // 卡 228：零散短行的通用兜底（只动有逐字时间的结果）
        return (ShortLines.merge(out)+coarse).sortedBy { it.startMillis }
    }
}
