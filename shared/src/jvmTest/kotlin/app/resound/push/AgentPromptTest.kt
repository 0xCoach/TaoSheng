package app.resound.push

import org.junit.Assert.*
import org.junit.Test

class AgentPromptTest {
    @Test fun nutstoreShowsOnlyAccountPasswordAndFolderInSentence() {
        val t = requireNotNull(AgentPrompt.text("https://dav.jianguoyun.com/dav/", "me@example.com", "abcd efgh", "Resound-Memory"))
        assertTrue("坚果云根目录的 Resound-Memory 文件夹" in t)
        assertTrue("- 坚果云账号：me@example.com" in t)
        assertTrue("- 应用密码：abcd efgh" in t)
        assertFalse("https://" in t)
        assertTrue("README.md" in t && "只读" in t)
    }

    @Test fun otherWebDavAddsAddressLine() {
        val t = requireNotNull(AgentPrompt.text("https://dav.example.com/remote.php/dav/", "u", "p", "/Obsidian/记忆/"))
        assertTrue("- WebDAV 地址：https://dav.example.com/remote.php/dav/" in t)
        assertTrue("Obsidian/记忆 文件夹" in t)
        assertTrue("- 账号：u" in t)
    }

    @Test fun missingKeysOrS3GivesNothing() {
        assertNull(AgentPrompt.text("https://dav.jianguoyun.com/dav/", "", "p", "Resound-Memory"))
        assertNull(AgentPrompt.text("https://dav.jianguoyun.com/dav/", "u", "", "Resound-Memory"))
        assertNull(AgentPrompt.text("https://s3.example.com", "u", "p", "Resound-Memory", s3 = true))
        assertNull(AgentPrompt.text("https://dav.jianguoyun.com/dav/", "u", "p", " / "))
    }

    @Test fun blankUrlCountsAsNutstore() {
        assertEquals(AgentPrompt.text("https://dav.jianguoyun.com/dav/", "u", "p", "F"), AgentPrompt.text("", "u", "p", "F"))
    }
}
