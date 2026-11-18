package app.resound.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** DocumentFile 会吞掉部分提供方异常。树 URI 直接调用同一套 SAF API，保留失败步骤和原因。 */
internal class BackupTreeDocument(private val context: Context, private val delegate: DocumentFile, private var cached: Meta? = null) {
    /** 列目录时一次查回的元数据；改名、删除后作废，之后照常现查 */
    internal data class Meta(val name: String?, val mime: String?, val size: Long, val modified: Long)
    var uri: Uri = delegate.uri
        private set
    private val tree get() = uri.scheme == "content" && DocumentsContract.isTreeUri(uri)
    private fun <T> step(name: String, block: () -> T) = BackupDiagnostics.step(context, name, block)
    private fun value(column: String): String? = step("resolve-directory") {
        val cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
            ?: throw IllegalStateException("Document query returned no cursor")
        cursor.use {
            check(it.moveToFirst()) { "Document metadata missing" }
            it.getString(0)
        }
    }
    val name: String? get() = cached?.name ?: if (tree) value(DocumentsContract.Document.COLUMN_DISPLAY_NAME) else delegate.name
    private val mime: String? get() = cached?.mime ?: value(DocumentsContract.Document.COLUMN_MIME_TYPE)
    val isDirectory: Boolean get() = if (tree) mime == DocumentsContract.Document.MIME_TYPE_DIR else delegate.isDirectory
    val isFile: Boolean get() = if (tree) mime?.let { it != DocumentsContract.Document.MIME_TYPE_DIR } == true else delegate.isFile
    fun length() = cached?.size ?: if (tree) value(DocumentsContract.Document.COLUMN_SIZE)?.toLongOrNull() ?: 0L else delegate.length()
    fun lastModified() = cached?.modified ?: if (tree) value(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull() ?: 0L else delegate.lastModified()
    private fun wrap(uri: Uri) = BackupTreeDocument(context, checkNotNull(DocumentFile.fromSingleUri(context, uri)))
    fun createFile(type: String, name: String): BackupTreeDocument = step(if (name.startsWith("resound-probe-")) "create-probe" else "create-file") {
        if (tree) wrap(DocumentsContract.createDocument(context.contentResolver, uri, type, name)
            ?: throw IllegalStateException("createDocument returned null"))
        else delegate.createFile(type, name)?.let { BackupTreeDocument(context, it) }
            ?: throw BackupFailure(LocalBackupFiles.UNSUPPORTED)
    }
    fun createDirectory(name: String): BackupTreeDocument = step("create-directory") {
        if (tree) wrap(DocumentsContract.createDocument(context.contentResolver, uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IllegalStateException("createDirectory returned null"))
        else delegate.createDirectory(name)?.let { BackupTreeDocument(context, it) }
            ?: throw BackupFailure(LocalBackupFiles.UNSUPPORTED)
    }
    fun listFiles(): List<BackupTreeDocument> = step("list-directory") {
        if (!tree) delegate.listFiles().map { BackupTreeDocument(context, it) }
        else {
            // 一次查回名字、类型、大小、修改时间：不再逐个回查子文件。
            // 逐个回查既慢（objects 里文件多），也会撞上列表后刚被清理或改名的文件，被系统当成“不在授权目录里”整次失败。
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val meta = Meta(cursor.getString(1), cursor.getString(2),
                            if (cursor.isNull(3)) 0L else cursor.getLong(3), if (cursor.isNull(4)) 0L else cursor.getLong(4))
                        add(BackupTreeDocument(context, checkNotNull(DocumentFile.fromSingleUri(context,
                            DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)))), meta))
                    }
                }
            } ?: throw IllegalStateException("Children query returned no cursor")
        }
    }
    fun findFile(name: String) = listFiles().firstOrNull { it.name == name }
    fun renameTo(name: String): Boolean = step("rename") {
        cached = null
        if (tree) uri = DocumentsContract.renameDocument(context.contentResolver, uri, name)
            ?: throw IllegalStateException("renameDocument returned null")
        else { check(delegate.renameTo(name)) { "renameTo returned false" }; uri = delegate.uri }
        check(this.name == name) { "Document renamed to unexpected name" }
        true
    }
    fun delete(): Boolean = step("delete") {
        cached = null
        check(if (tree) DocumentsContract.deleteDocument(context.contentResolver, uri) else delegate.delete()) { "delete returned false" }
        true
    }
}
