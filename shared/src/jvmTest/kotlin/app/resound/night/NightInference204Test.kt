package app.resound.night

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NightInference204Test {
    private data class Sample(val window: NightWindow, val snore: Float, val cough: Float?, val levels: List<Int>)
    private fun sample(at: Long, above: String, rhythm: String, modulation: String, snore: String, cough: String?, levels: List<Int>) =
        Sample(NightWindow(at,ShortArray(0),false,0.0,0,NightFeatures(rhythm.toDouble(),modulation.toDouble(),above.toInt())),
            snore.toFloat(),cough?.toFloat(),levels)

    private suspend fun replay(samples: List<Sample>, gated: Boolean): Pair<List<Pair<NightEventKind,NightSpan>>,Int> {
        val detector = NightDetector()
        val events = mutableListOf<Pair<NightEventKind,NightSpan>>()
        val fine = ArrayDeque<SnoreLevel>()
        val breaks = mutableSetOf<Long>()
        val byTime = samples.associateBy { it.window.start }
        var skipped = 0
        fun process(window: NightWindow, infer: Boolean): Boolean {
            val row = byTime.getValue(window.start)
            if (!infer) {
                skipped++
                // 203 的窗口未保存咳嗽分数。仅在这些窗口全部执行模型时才可证明咳嗽不变。
                assertNotNull(row.cough)
            }
            val snore = if (infer) row.snore else 0f
            val cough = if (infer) row.cough ?: 0f else 0f
            row.levels.forEachIndexed { i, db ->
                fine.addLast(SnoreLevel(window.start+i*500,db,snore>=NightTuning.SNORE && detector.currentSnore!=null))
            }
            while (fine.isNotEmpty() && fine.first().at < window.start-120_000) fine.removeFirst()
            for (span in SnoreBreaks.find(fine.toList())) if (breaks.add(span.start)) events += NightEventKind.SnoreBreak to span
            events += detector.accept(window.start,mapOf("Snoring" to snore,"Cough" to cough),window.features)
            return detector.detecting || snore>=NightTuning.SNORE || cough>=NightTuning.COUGH
        }
        val gate = NightInferenceGate { w,infer -> process(w,infer) }
        for (row in samples) if (gated) gate.offer(row.window) else process(row.window,true)
        gate.flush()
        events += detector.finish()
        return events to skipped
    }

    @Test fun sameEventsOnContinuousBackgroundSnoreAndBreakRecordings() = runTest {
        val root=File("../docs/任务卡")
        for (file in listOf("202-证据/continuous-windows.csv","203-证据/break-windows.csv","203-证据/psg-windows.csv")) {
            val continuous=file.startsWith("202")
            val rows=File(root,file).readLines().drop(1).map { it.split(',') }
            val streams=rows.groupBy { if (continuous) it.take(3) else it.take(1) }
            var skipped=0
            val counts=mutableMapOf<NightEventKind,Int>()
            for ((key,stream) in streams) {
                val samples=stream.map { r -> if(continuous)
                    sample((r[4].toDouble()*1000).toLong(),r[9],r[7],r[8],r[5],r[6],emptyList())
                    else sample(r[1].toLong(),r[5],r[3],r[4],r[2],null,r[6].split(';').map(String::toInt)) }
                val baseline=replay(samples,false).first
                val result=replay(samples,true)
                assertEquals(key.toString(),baseline,result.first)
                baseline.forEach { (kind,_) -> counts[kind]=(counts[kind] ?: 0)+1 }
                skipped+=result.second
            }
            println("204-gate $file ${rows.size} windows $skipped skipped $counts")
            if(continuous) assertTrue(skipped>0)
        }
    }
}
