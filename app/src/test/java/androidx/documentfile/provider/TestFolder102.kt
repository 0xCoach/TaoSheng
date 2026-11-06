package androidx.documentfile.provider

// DocumentFile 的构造器为包可见；测试替身放同包，不增加生产依赖。
internal class TestFolder102(private val delegate: DocumentFile, private val denyCreate: Boolean = false) : DocumentFile(null) {
        override fun canWrite() = false
        override fun canRead() = false
        override fun createFile(type: String, name: String) = if (denyCreate) null else delegate.createFile(type, name)
        override fun createDirectory(name: String) = delegate.createDirectory(name)
        override fun getUri() = delegate.uri
        override fun getName() = delegate.name
        override fun getType() = delegate.type
        override fun isDirectory() = delegate.isDirectory
        override fun isFile() = delegate.isFile
        override fun isVirtual() = delegate.isVirtual
        override fun lastModified() = delegate.lastModified()
        override fun length() = delegate.length()
        override fun delete() = delegate.delete()
        override fun exists() = delegate.exists()
        override fun listFiles() = delegate.listFiles()
        override fun renameTo(name: String) = delegate.renameTo(name)
    }
