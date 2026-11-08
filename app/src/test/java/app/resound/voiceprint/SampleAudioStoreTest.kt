package app.resound.voiceprint

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class SampleAudioStoreTest {
    @Test fun clearPersistsAcrossStoreRecreationAndNewSamplesRemainPlayable() {
        val dir = Files.createTempDirectory("sample-store-test").toFile()
        try {
            val store = SampleAudioStore(dir)
            store.save("local-1", ShortArray(16000) { 1000 })
            assertEquals(32044L, store.bytes.value)
            assertNotNull(store.temp(ShortArray(16000)))
            assertEquals(64088L, store.bytes.value)
            store.clear()
            val reopened = SampleAudioStore(dir)
            assertEquals(0L, reopened.bytes.value)
            assertFalse(reopened.fallbackAllowed())
            assertNull(reopened.temp(ShortArray(16000)))
            reopened.save("local-2", ShortArray(16000))
            assertNotNull(reopened.file("local-2"))
            assertNull(reopened.file("local-1"))
            assertEquals(32044L, reopened.bytes.value)
        } finally { dir.deleteRecursively() }
    }
}
