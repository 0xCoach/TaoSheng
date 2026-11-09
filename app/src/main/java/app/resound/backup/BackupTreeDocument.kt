package app.resound.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** DocumentFile 会吞掉部分提供方异常。树 URI 直接调用同一套 SAF API，保留失败步骤和原因。 */
internal class BackupTreeDocument(private val context: Context, private val delegate: DocumentFile) {
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
    val name: String? get() = if (tree) value(DocumentsContract.Document.COLUMN_DISPLAY_NAME) else delegate.name
    val isDirectory: Boolean get() = if (tree) value(DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR else delegate.isDirectory
    val isFile: Boolean get() = if (tree) value(DocumentsContract.Document.COLUMN_MIME_TYPE)?.let { it != DocumentsContract.Document.MIME_TYPE_DIR } == true else delegate.isFile
    fun length() = if (tree) value(DocumentsContract.Document.COLUMN_SIZE)?.toLongOrNull() ?: 0L else delegate.length()
    fun lastModified() = if (tree) value(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull() ?: 0L else delegate.lastModified()
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
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(wrap(DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)))) }
            } ?: throw IllegalStateException("Children query returned no cursor")
        }
    }
    fun findFile(name: String) = listFiles().firstOrNull { it.name == name }
    fun renameTo(name: String): Boolean = step("rename") {
        if (tree) uri = DocumentsContract.renameDocument(context.contentResolver, uri, name)
            ?: throw IllegalStateException("renameDocument returned null")
        else { check(delegate.renameTo(name)) { "renameTo returned false" }; uri = delegate.uri }
        check(this.name == name) { "Document renamed to unexpected name" }
        true
    }
    fun delete(): Boolean = step("delete") {
        check(if (tree) DocumentsContract.deleteDocument(context.contentResolver, uri) else delegate.delete()) { "delete returned false" }
        true
    }
}
