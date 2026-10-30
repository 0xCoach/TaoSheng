package app.resound.transcribe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsrFrames073Test {
    @get:Rule val temp = TemporaryFolder()

    @Test fun groupsTwentyMillisecondPagesWithoutChangingBytesOrTail() = runBlocking {
        fun page(granule: Long) = ByteArray(29).apply {
            "OggS".toByteArray().copyInto(this)
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(6, granule)
            this[26] = 1; this[27] = 1; this[28] = 7
        }
        val original = page(0) + (1..25).flatMap { page(it * 960L).toList() }.toByteArray()
        val file = temp.newFile().apply { writeBytes(original) }
        val frames = mutableListOf<ByteArray>()
        val flags = mutableListOf<Boolean>()
        AsrAudioReader.chunks(AsrAudioReader.Prepared(file, "ogg", false)) { bytes, last ->
            frames += bytes; flags += last
        }
        assertEquals(listOf(11 * 29, 10 * 29, 5 * 29), frames.map { it.size })
        assertArrayEquals(original, frames.flatMap { it.toList() }.toByteArray())
        assertEquals(listOf(false, false, true), flags)
    }

    @Test fun truncatedPageFailsAndPcmStillUsesTwoHundredMilliseconds() = runBlocking {
        val file = temp.newFile().apply { writeBytes("OggS".toByteArray()) }
        val error = runCatching {
            AsrAudioReader.chunks(AsrAudioReader.Prepared(file, "ogg", false)) { _, _ -> }
        }.exceptionOrNull() as AsrException
        assertEquals(AsrError.Audio, error.type)
        file.writeBytes(ByteArray(12_802))
        val sizes = mutableListOf<Int>()
        AsrAudioReader.chunks(AsrAudioReader.Prepared(file, "pcm", false)) { bytes, _ -> sizes += bytes.size }
        assertEquals(listOf(6400, 6400, 2), sizes)
    }
}
