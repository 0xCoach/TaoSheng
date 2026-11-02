package app.resound.envsound

import app.resound.data.MemoryEventEntity
import org.junit.Assert.*
import org.junit.Test

class EnvMergeConsistencyTest {
    @Test fun longEventDoesNotLeakFutureSoundsIntoNestedShortEvent() {
        fun event(id: Long, from: Long, to: Long) = MemoryEventEntity(id, from, to, "其他", "测试", "", "[]", "[]", null, null, null, false, createdAt = 0, model = "test")
        val rows = listOf(
            EnvSoundRow(1, 0, 2, "", "Keep", null, "Rain", "", "", false, false),
            EnvSoundRow(2, 4, 6, "", "Keep", null, "Music", "", "", false, false))
        val result = mergeSounds(listOf(event(3, 6, 8), event(1, 0, 10), event(2, 1, 3)), rows)
        assertEquals(setOf(EnvTag.Rain, EnvTag.Music), result.getValue(1).tags)
        assertEquals(setOf(EnvTag.Rain), result.getValue(2).tags)
        assertFalse(3L in result)
    }
}
