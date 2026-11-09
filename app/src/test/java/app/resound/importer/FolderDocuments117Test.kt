package app.resound.importer

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class FolderDocuments117Test {
    @Test fun safReadsOnlyVisibleRootAndOneChildLevelWithoutWriting() {
        val queried = mutableListOf<String>()
        val dir = DocumentsContract.Document.MIME_TYPE_DIR
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = dir
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): MatrixCursor {
                val id = DocumentsContract.getDocumentId(uri)
                val children = uri.lastPathSegment == "children"
                queried += if (children) "$id/children" else id
                val result = MatrixCursor(projection)
                fun row(key: String, name: String, mime: String, size: Int = 10) { result.addRow(arrayOf(key, name, size, 500_000, mime)) }
                when {
                    !children -> row("root", "录音笔", dir)
                    id == "root" -> {
                        row("audio", "top.M4A", "application/octet-stream")
                        row("hidden", ".hidden.wav", "audio/wav")
                        row("text", "notes.txt", "text/plain")
                        row("child", "子目录", dir)
                        row("private", ".private", dir)
                    }
                    id == "child" -> {
                        row("child-audio", "new.wav", "audio/wav")
                        row("empty", "empty.wav", "audio/wav", 0)
                        row("deep", "第三层", dir)
                    }
                    else -> error("不应递归进入 $id")
                }
                return result
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri = error("不得写入源目录")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("不得修改源文件")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("不得删除源文件")
        }
        ShadowContentResolver.registerProviderInternal("folder117", provider)
        val source = AndroidFolderDocuments(RuntimeEnvironment.getApplication())
        val files = source.files(Uri.parse("content://folder117/tree/root"))
        assertEquals(listOf("top.M4A", "new.wav", "empty.wav"), files.map { it.name })
        assertEquals(listOf("root", "root/children", "child/children"), queried)
    }
}
