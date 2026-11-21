package app.resound.qa

import org.junit.Assert.*
import org.junit.Test

class EvidenceGuardTest {
    @Test fun validMarkersStayAndInvalidOnesAreDropped() {
        val text = "约好了下周交稿 [#2]，上次也提过 [#1]，另外 [#9] 和 [#0] 不存在 [#x]"
        val out = EvidenceGuard.clean(text, 3)
        assertEquals("约好了下周交稿 [#2]，上次也提过 [#1]，另外  和  不存在 [#x]", out.text)
        assertEquals(2, out.dropped)
        assertTrue(EvidenceGuard.hasCitation(out.text, 3))
        assertFalse(EvidenceGuard.hasCitation(EvidenceGuard.clean("只有错引用 [#5]", 2).text, 2))
    }

    @Test fun strengthGradesEvidence() {
        assertEquals(0, EvidenceGuard.strength(0, 0, 0))
        assertEquals(1, EvidenceGuard.strength(1, 0, 0))
        assertEquals(2, EvidenceGuard.strength(2, 0, 0))
        assertEquals(2, EvidenceGuard.strength(1, 1, 0))
        assertEquals(3, EvidenceGuard.strength(4, 0, 0))
        assertEquals(3, EvidenceGuard.strength(1, 0, 1))
        assertEquals(3, EvidenceGuard.strength(2, 2, 0))
    }
}
