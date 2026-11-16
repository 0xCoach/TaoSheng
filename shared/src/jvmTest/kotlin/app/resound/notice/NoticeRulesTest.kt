package app.resound.notice

import org.junit.Assert.*
import org.junit.Test

class NoticeRulesTest {
    private val sha = "a".repeat(64)
    private fun file(notices: String, version: Int = 3, kind: String = "notices") =
        """{"kind":"$kind","version":$version,"issuedAt":"2026-11-16T10:00:00Z","notices":[$notices]}""".encodeToByteArray()
    private val qr = """{"id":"group-1","title":"加入涛声用户群","body":"交流使用心得","image":{"file":"group-qr.png","sha256":"$sha","caption":"保存到相册后用微信扫一扫"},"priority":5}"""

    @Test fun parsesNoticeWithQrImage() {
        val f = NoticeRules.parse(file(qr))
        assertEquals("group-qr.png", f.notices[0].image!!.file)
        assertEquals(5, f.notices[0].priority)
    }

    @Test fun rejectsUnsafeOrForeignFiles() {
        assertThrows(Exception::class.java) { NoticeRules.parse(file(qr, kind = "cards")) }
        assertThrows(Exception::class.java) { NoticeRules.parse(file(qr.replace("group-qr.png", "../etc/x.png"))) }
        assertThrows(Exception::class.java) { NoticeRules.parse(file("""{"id":"a","title":"t","link":{"label":"看看","url":"http://x"}}""")) }
        assertThrows(Exception::class.java) { NoticeRules.parse(file("""{"id":"a","title":"t","surprise":1}""")) }
        assertThrows(Exception::class.java) { NoticeRules.parse(file("""{"id":"a","title":"t"},{"id":"a","title":"u"}""")) }
    }

    @Test fun neverRollsBack() {
        val bytes = file(qr); val f = NoticeRules.parse(bytes)
        assertTrue(NoticeRules.accepts(null, null, f, bytes))
        assertTrue(NoticeRules.accepts(2, file(qr, 2), f, bytes))
        assertTrue(NoticeRules.accepts(3, bytes, f, bytes))
        assertFalse("同版本不同内容", NoticeRules.accepts(3, file(qr.replace("交流", "聊聊")), f, bytes))
        assertFalse("旧版本", NoticeRules.accepts(4, null, f, bytes))
    }

    @Test fun filtersByTimeVersionBrandAndDismissal() {
        val f = NoticeRules.parse(file("""
            {"id":"all","title":"全员"},
            {"id":"later","title":"以后","startAt":2000},
            {"id":"old","title":"过期","endAt":500},
            {"id":"v","title":"老版本","maxVersionCode":79},
            {"id":"hw","title":"鸿蒙","brands":["HUAWEI"],"priority":9},
            {"id":"must","title":"必须看","dismissible":false}
        """.trimIndent()))
        val shown = NoticeRules.visible(f, now = 1000, versionCode = 81, brand = "huawei", dismissed = setOf("all", "must"))
        assertEquals(listOf("hw", "must"), shown.map { it.id })
        assertEquals(listOf("all", "must"), NoticeRules.visible(f, 1000, 81, "xiaomi", emptySet()).map { it.id })
    }
}
