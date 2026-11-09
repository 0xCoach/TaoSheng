package app.resound.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

internal data class FolderDocument(val uri: Uri, val name: String, val size: Long, val modified: Long,
    val mime: String = "audio/*") {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    val hidden get() = name.startsWith(".")
}

internal interface FolderDocuments {
    fun acquire(tree: Uri)
    fun granted(tree: Uri): Boolean
    fun root(tree: Uri): FolderDocument
    fun files(tree: Uri): List<FolderDocument>
    fun stat(uri: Uri): FolderDocument
    fun fingerprint(document: FolderDocument): String
}

internal class AndroidFolderDocuments(private val context: Context) : FolderDocuments {
    private val resolver get() = context.contentResolver
    override fun acquire(tree: Uri) = resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    override fun granted(tree: Uri) = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    override fun root(tree: Uri): FolderDocument = stat(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        .also { if (!it.directory) throw java.io.FileNotFoundException("文件夹已不存在，请重新选择") }
    private fun query(uri: Uri, tree: Uri): List<FolderDocument> {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw java.io.IOException("无法读取文件夹")
        return cursor.use { c ->
            if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw java.io.IOException("文件夹尚未加载完成，请稍后重试")
            buildList {
                while (c.moveToNext()) add(FolderDocument(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    c.getString(1).orEmpty(), if (c.isNull(2)) -1 else c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3), c.getString(4).orEmpty()))
            }
        }
    }
    override fun stat(uri: Uri) = query(uri, uri).singleOrNull() ?: throw java.io.FileNotFoundException("文件已不存在")
    override fun files(tree: Uri): List<FolderDocument> {
        fun children(directory: Uri) = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,
            DocumentsContract.getDocumentId(directory)), tree)
        val top = children(root(tree).uri).filterNot { it.hidden }
        return (top.filterNot { it.directory } + top.filter { it.directory }.flatMap { children(it.uri) })
            .filter { !it.directory && !it.hidden && ImportMedia.accepts(it.name, it.mime) }.distinctBy { it.uri }
    }
    override fun fingerprint(document: FolderDocument): String {
        // 本地文件只读首尾；管道或云盘 provider 不可 seek 时才流式读取。
        try {
            resolver.openFileDescriptor(document.uri, "r")?.let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { return audioFingerprint(it.channel) }
            }
        } catch (_: java.io.IOException) { /* 重新打开流，兼容不可 seek 的 provider。 */ }
        return resolver.openInputStream(document.uri)?.use(::audioFingerprint) ?: throw java.io.IOException("无法读取录音")
    }
}
