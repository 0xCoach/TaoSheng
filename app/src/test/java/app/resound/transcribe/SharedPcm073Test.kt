package app.resound.transcribe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedPcm073Test {
    @get:Rule val temp = TemporaryFolder()
    @Test fun decodeOnceReuseExactSamplesThenDelete() = runBlocking {
        val source = temp.newFile()
        var decodes = 0
        val files = SharedPcmFiles(temp.root) { _, target -> decodes++; target.writeBytes(byteArrayOf(0, -128, -1, 127, 0, 0)) }
        val pcm = files.prepare(source)
        assertArrayEquals(shortArrayOf(-32768, 32767, 0), pcm)
        assertArrayEquals(pcm, files.read(source) { error("不得再次解码") })
        assertArrayEquals(pcm, files.prepare(source))
        assertEquals(1, decodes)
        val shared = files.find(source)!!
        files.release(source)
        assertFalse(shared.exists()); assertNull(files.find(source)); assertTrue(source.exists())
    }
    @Test fun failedDecodeLeavesNoPartialFile() = runBlocking {
        val source = temp.newFile()
        val files = SharedPcmFiles(temp.root) { _, target -> target.writeBytes(byteArrayOf(1)); error("模拟失败") }
        assertTrue(runCatching { files.prepare(source) }.isFailure)
        assertNull(files.find(source))
        assertEquals(listOf(source), temp.root.listFiles()!!.toList())
    }
}
