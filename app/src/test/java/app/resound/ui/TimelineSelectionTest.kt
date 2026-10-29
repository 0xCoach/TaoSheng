package app.resound.ui

import androidx.compose.ui.geometry.Rect
import app.resound.ui.today.TimelineSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSelectionTest {
    private val order = (1L..8L).toList()
    private fun rows(s: TimelineSelection, vararg ids: Long) = ids.forEachIndexed { i, id -> s.bounds[id] = Rect(0f, i * 100f, 100f, i * 100f + 90f) }

    @Test fun reachSelectsFromAnchorToTarget() {
        val s = TimelineSelection().apply { start(2) }
        rows(s, 5, 6, 7)
        val target = s.reachTarget(order, 0f, 1000f)
        assertEquals(7L, target)
        s.rangeTo(target!!, order)
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L), s.ids)
    }

    @Test fun skippedRowStaysUnselectedWhenRangingFromNewAnchor() {
        val s = TimelineSelection().apply { start(1) }
        s.rangeTo(3, order)          // 1..3
        s.toggle(5, order)           // 跳过 4，勾上 5：起点移到 5
        s.rangeTo(7, order)          // 选到 7
        assertEquals(setOf(1L, 2L, 3L, 5L, 6L, 7L), s.ids)
    }

    @Test fun noHintWhenRangeAlreadySelected() {
        val s = TimelineSelection().apply { start(1) }
        s.rangeTo(4, order)
        s.toggle(4, order); s.toggle(4, order) // 起点仍是 4
        rows(s, 2, 3, 4)
        assertNull(s.reachTarget(order, 0f, 1000f))
    }
}
