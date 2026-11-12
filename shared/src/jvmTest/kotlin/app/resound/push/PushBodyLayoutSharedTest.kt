package app.resound.push

import org.junit.Assert.*
import org.junit.Test

class PushBodyLayoutSharedTest {
    @Test fun markdownBlocksAndExistingHardBreaksAreNotDamaged() {
        val blocks = "# 标题\n正文  \n已换行<br/>\n\n```md\n一\n二\n```\n\n| 列 |\n| --- |\n| 值 |\n\n- 甲\n  - 子项\n- 乙"
        for (kind in listOf(PushKind.PushPlus, PushKind.DingTalk, PushKind.ServerChan, PushKind.Webhook)) {
            val converted = PushBodyLayout.convert(kind, PushFormat.Markdown, blocks)
            assertTrue(converted.startsWith("# 标题\n正文  \n已换行<br/>\n\n```md\n一\n二\n```"))
            assertTrue(converted.contains("| 列 |\n| --- |\n| 值 |"))
            assertEquals(converted, PushBodyLayout.convert(kind, PushFormat.Markdown, converted))
        }
    }
}
