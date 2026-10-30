package app.resound.transcribe

import org.junit.Assert.*
import org.junit.Test

class SegmentTimings073Test {
    @Test fun sumsMeasurementsAndDoesNotInventCloudInferenceBreakdown() {
        var clock = 100L
        val timing = SegmentTimings(7, TranscribeSource.Doubao, 3000) { clock }
        timing.add(AsrPart.EnvLoad, 10); timing.add(AsrPart.EnvLoad, 2)
        timing.add(AsrPart.Upload, 50); timing.add(AsrPart.Wait, 200)
        timing.outcome = "Done"; clock = 500
        val row = timing.line()
        assertTrue(row.contains("环境加载=12ms"))
        assertTrue(row.contains("上传=50ms")); assertTrue(row.contains("等结果=200ms"))
        assertTrue(row.contains("分人=未提供")); assertTrue(row.contains("识别=未提供"))
        assertTrue(row.contains("总计=400ms")); assertTrue(row.contains("状态=Done"))
    }
}
