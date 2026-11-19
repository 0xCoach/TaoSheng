package app.resound.night

import org.junit.Assert.*
import org.junit.Test

/** 卡 210：缓冲滑动时同一次鼾声中断不能被记成多条 */
class Night210BreakTest {
    /** 照 NightSoundProcessor：每 2 秒进 4 格、只留最近 120 秒、每次全扫、按起点去重 */
    private fun replay(points: List<SnoreLevel>): List<NightSpan> {
        val buffer=ArrayDeque<SnoreLevel>();val known=linkedMapOf<Long,NightSpan>()
        points.chunked(4).forEach { window->
            buffer.addAll(window)
            while(buffer.first().at<window.first().at-120_000)buffer.removeFirst()
            SnoreBreaks.find(buffer.toList()).forEach { known.putIfAbsent(it.start,it) }
        }
        return known.values.toList()
    }
    private fun night(quietSnore: (Int)->Boolean): List<SnoreLevel> {
        val out=mutableListOf<SnoreLevel>();var at=0L
        fun add(n: Int,db: Int,snore: (Int)->Boolean) = repeat(n) { out+=SnoreLevel(at,db,snore(it));at+=500 }
        add(120,12) { true }                 // 打鼾 60 秒
        add(80,1) { quietSnore(it/4) }       // 安静 40 秒，有的 2 秒窗口模型仍给了鼾声分
        add(3,25) { false }                  // 猛地一声 1.5 秒
        add(200,12) { true }                 // 鼾声回来
        return out
    }
    @Test fun oneRealBreakIsRecordedOnceWhileBufferSlides() {
        val windows=listOf(1,2,5,7,9,12,15,17)
        val spans=replay(night { it in windows })
        assertEquals(listOf(NightSpan(60_000,101_500)),spans)
        assertEquals(listOf(NightSpan(60_000,101_500)),replay(night { false }))
    }
    @Test fun duplicatesKeepEarliestStartPerBurst() {
        val rows=listOf(1L to NightSpan(60_000,101_500),2L to NightSpan(60_500,101_500),3L to NightSpan(76_500,101_500),4L to NightSpan(300_000,330_000))
        assertEquals(listOf(2L,3L),SnoreBreaks.duplicates(rows).sorted())
        assertTrue(SnoreBreaks.duplicates(rows.take(1)+rows.last()).isEmpty())
    }
}
