/*
 * [INPUT]: SDK 目录 JSON、实机与旧版文件名、少量及多页目录。
 * [OUTPUT]: 目录校验、日期解析、未知日期归组、筛选与页码收缩的回归。
 * [POS]: SDK 目录边界及展示规则的离线测试，不依赖录音卡或当前日期。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [32])
class RecorderFileListTest {
    private fun file(name: String) = RecorderFile(name, 80, 2)

    @Test fun malformedDirectoryCannotBeMistakenForSuccessfulEmptyListing() {
        assertTrue(recorderDirectoryChunk(JSONObject("""{"finish":1,"data":[]}""")).isEmpty())
        assertTrue(runCatching { recorderDirectoryChunk(JSONObject("""{"finish":1}""")) }.isFailure)
        assertTrue(runCatching { recorderDirectoryChunk(JSONObject("""{"finish":1,"data":[{"name":"x","size":-1,"time":2}]}""")) }.isFailure)
        val files = recorderDirectoryChunk(JSONObject("""{"finish":0,"data":[{"name":"x","size":80,"time":2}]}"""))
        assertEquals(listOf(file("x")), files)
    }

    @Test fun realAndLegacyNamesShareTimeWhileInvalidDatesStayUnknown() {
        assertEquals(recorderStart("note20261108-221812.opus"), recorderStart("note 2026-11-08_22-18-12.opus"))
        assertNotNull(recorderStart("call 2026-11-07_18-53-37.opus"))
        assertNull(recorderStart("note 2026-02-30_22-18-12.opus"))
        assertNull(recorderStart("unknown.opus"))
    }

    @Test fun smallDirectoryShowsAllNewestFirstWithWeekdayAndUnknownLast() {
        val rows = recorderDatedFiles(listOf(file("unknown.opus"), file("call 2026-11-07_18-53-37.opus"),
            file("note 2026-11-08_22-18-12.opus")))
        val page = recorderFilePage(rows, null, 0)
        assertEquals(1, page.pages)
        assertEquals(3, page.rows.size)
        assertEquals("2026-11-08 星期日", page.rows.first().day)
        assertEquals("日期未知", page.rows.last().day)
    }

    @Test fun manyFilesPaginateFilterAndClampAfterDeletion() {
        val rows = recorderDatedFiles((0..40).map { file("note 2026-11-08_22-18-${it.toString().padStart(2, '0')}.opus") } +
            file("call 2026-11-07_18-53-37.opus"))
        assertEquals(40, recorderFilePage(rows, null, 0).rows.size)
        val last = recorderFilePage(rows, null, 1)
        assertEquals(2, last.rows.size)
        assertEquals(2, last.pages)
        val filtered = recorderFilePage(rows, "2026-11-07 星期六", 100)
        assertEquals(0, filtered.index)
        assertEquals(1, filtered.total)
        val reduced = recorderFilePage(rows.take(40), null, 1)
        assertEquals(0, reduced.index)
        assertEquals(40, reduced.rows.size)
        assertEquals(0, recorderFilePage(emptyList(), null, 2).total)
    }
}
