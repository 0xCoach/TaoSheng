package app.resound.transcribe

import android.app.Application
import app.resound.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 卡 211：人工修改原话——直接替换、清掉同音纠正旧句、记下修改时间、全文索引跟上 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LineEdit211Test {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun editReplacesTextClearsOriginalAndReindexes() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase("edit211.db")
        val db = CaptureDatabase.open(context, "edit211.db")
        try {
            db.capture().segment(SegmentEntity(1, 1000, 3000, "mic", "/none.ogg", 5, "Done", completedAt = 1))
            db.transcribe().insertLines(listOf(
                TranscriptLineEntity(1, 1, 1000, 2000, "明天找张薇开会", null, originalText = "明天找张维开会"),
                TranscriptLineEntity(2, 1, 2000, 3000, "好的", null)))
            assertEquals(0, db.transcribe().editedLineCount(listOf(1)))
            assertEquals(1, db.transcribe().editLineText(1, "明天找张伟开会", 500))
            val line = db.transcribe().exportSegments(listOf(1)).single().lines.first { it.id == 1L }
            assertEquals("明天找张伟开会", line.text)
            assertNull(line.originalText)
            assertEquals(500L, line.textEditedAt)
            assertEquals(1, db.transcribe().editedLineCount(listOf(1)))
            // 全文索引由触发器更新：新词进索引，旧词不在
            val indexed = db.readSql("SELECT text FROM lines_fts WHERE rowid = 1") { it.step(); it.getText(0) }
            assertTrue(indexed.contains("张伟") && !indexed.contains("张薇"))
            assertEquals(0, db.transcribe().editLineText(99, "不存在", 600))
        } finally { db.close(); context.deleteDatabase("edit211.db") }
    }
}
