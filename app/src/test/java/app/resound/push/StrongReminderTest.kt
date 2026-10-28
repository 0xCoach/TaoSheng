package app.resound.push

import app.resound.digest.ManualTodoPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StrongReminderTest {
    @Test fun itemIdFromReminderKey() {
        assertEquals(183L, StrongReminders.itemId("item:183:1793184480000:1"))
        assertEquals(7L, StrongReminders.itemId("item:7:1793184480000"))
        // 提前提醒、头天晚上的汇总不是强提醒
        assertNull(StrongReminders.itemId("lead:183:1793184480000"))
        assertNull(StrongReminders.itemId("evening:2026-10-29"))
    }

    @Test fun selfAddedDraftsDefaultToStrong() {
        val drafts = ManualTodoPrompt.parse("""{"items":[{"category":"待办","kind":"我要做","content":"填报工单","dueText":"2026-10-28 18:50","who":null}]}""",
            ZoneId.of("Asia/Shanghai"))
        assertTrue(drafts.single().strongReminder)
    }
}
