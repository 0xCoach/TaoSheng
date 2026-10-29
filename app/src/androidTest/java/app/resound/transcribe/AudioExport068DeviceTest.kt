package app.resound.transcribe

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.data.SegmentEntity
import app.resound.data.SegmentWithLines
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** 仅由负责人在设备上运行：真实 OGG + AAC 解码、合并、AAC 轨与时长验收。 */
@RunWith(AndroidJUnit4::class)
class AudioExport068DeviceTest {
    @Test fun mixedOggAndM4aProduceReadableAacTrackWithHalfSecondGap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "export-device-${System.nanoTime()}").apply { mkdirs() }
        try {
            val ogg = File(root, "input.ogg")
            context.assets.open("asr-test.ogg").use { input -> ogg.outputStream().use { input.copyTo(it) } }
            val pcm = File(root, "input.pcm").apply { writeBytes(ByteArray(64000)) }
            val m4a = File(root, "input.m4a")
            ExportAacEncoder(m4a).use { it.append(pcm); it.finish() }
            val reader = AsrAudioReader(root)
            val sources = listOf(ogg, m4a).mapIndexed { index, file ->
                reader.decodeForExport(file, pcm)
                val duration = pcm.length() * 1000 / 32000
                ExportSource(SegmentWithLines(SegmentEntity(index + 1L, index * 10000L, index * 10000L + duration, "mic", file.path, file.length()), emptyList()), emptyList())
            }
            val exporter = LocalAudioExporter(File(root, "exports"), { sources }, reader::decodeForExport)
            val result = exporter.export(ExportRequest(listOf(2, 1), "混合音频")).toList().last() as ExportProgress.Done
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.files.first().path)
                val track = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.single()
                assertEquals("audio/mp4a-latm", track.getString(MediaFormat.KEY_MIME))
                assertEquals(16000, track.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, track.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                val expected = sources.sumOf { it.row.segment.endAt - it.row.segment.startAt } + 500
                assertEquals(expected.toDouble(), track.getLong(MediaFormat.KEY_DURATION) / 1000.0, 200.0)
                extractor.selectTrack(0)
                assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(65536), 0) > 0)
            } finally { extractor.release() }
        } finally { root.deleteRecursively() }
    }
}
