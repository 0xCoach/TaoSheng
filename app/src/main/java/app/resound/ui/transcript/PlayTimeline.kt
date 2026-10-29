package app.resound.ui.transcript

import app.resound.digest.MemoryEvent
import app.resound.transcribe.TranscriptLine
import java.time.LocalDate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 几段录音连着播时的“总进度”：每段从它在本次播放里的第一句开始、到最后一句结束，段与段之间的空隙不算。
 * [elapsed] 把“第几段的某个时刻”换成总进度；[locate] 反过来，把拖到的总进度换成“哪一段、哪个时刻”。
 */
internal class PlayTimeline(lines: List<TranscriptLine>) {
    data class Span(val segmentId: Long, val from: Long, val to: Long)
    val spans: List<Span> = lines.groupBy { it.segmentId }.map { (id, ls) ->
        val from = ls.minOf { it.startAt }
        Span(id, from, maxOf(ls.maxOf { it.endAt }, from + 1))
    }.sortedBy { it.from }
    val total: Long = spans.sumOf { it.to - it.from }.coerceAtLeast(1)

    fun elapsed(segmentId: Long, at: Long): Long {
        var before = 0L
        for (s in spans) {
            if (s.segmentId == segmentId) return before + (at - s.from).coerceIn(0, s.to - s.from)
            before += s.to - s.from
        }
        return 0
    }

    /** 总进度 → (录音段, 绝对时刻) */
    fun locate(elapsed: Long): Pair<Long, Long> {
        var left = elapsed.coerceIn(0, total)
        for (s in spans) {
            val len = s.to - s.from
            if (left <= len) return s.segmentId to s.from + left
            left -= len
        }
        val last = spans.last(); return last.segmentId to last.to
    }
}

/** 从哪里开始播的：播放卡片上“回到这件事 / 回到原话”用 */
sealed interface PlaySource {
    data class Event(val event: MemoryEvent) : PlaySource
    data class Transcript(val date: LocalDate) : PlaySource
}

/** 页面提供播放来源；原话列表点句子播放时带上 */
val LocalPlaySource = androidx.compose.runtime.compositionLocalOf<PlaySource?> { null }

/** 播放卡片要求“回到来源”时设置，由外壳和原话页处理 */
object PlayerNav {
    var openEvent by androidx.compose.runtime.mutableStateOf<MemoryEvent?>(null)
    /** 回到原话页：哪天、滚到哪段录音 */
    var transcriptFocus by androidx.compose.runtime.mutableStateOf<Pair<LocalDate, Long>?>(null)
}
