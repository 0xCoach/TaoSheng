package app.resound.push

import java.io.*
import org.junit.Test
import org.junit.Assert.*

class LocalFolder214Test {
    private class Memory:FolderDocuments {
        val files=mutableMapOf<String,ByteArray>(); var corrupt=false; var revoked=false
        override fun size(path:String):Long? { check(!revoked); return files[path]?.size?.toLong() }
        override fun directories(path:String) {}
        override fun write(path:String):OutputStream { check(!revoked); return object:ByteArrayOutputStream(){ override fun close(){files[path]=toByteArray()} } }
        override fun read(path:String):InputStream? { check(!revoked); return files[path]?.let { if(corrupt) byteArrayOf(0).inputStream() else it.inputStream() } }
        override fun delete(path:String) {files.remove(path)}
        override fun rename(from:String,to:String) {files[to]=files.remove(from)!!}
    }
    @Test fun partialIsOverwrittenReadBackAndRenamed() {
        val docs=Memory(); val store=LocalFolderStorage(docs)
        docs.files["录音/.a.ogg.part"]="old-part".toByteArray()
        val file=File.createTempFile("local214-",".ogg").apply{writeText("audio")}
        try {store.audio("录音/a.ogg",file); assertEquals(setOf("录音/a.ogg"),docs.files.keys); assertEquals("audio",docs.files.values.single().decodeToString()); assertEquals(5L,store.size("录音/a.ogg")); assertNull(store.size("录音/missing.ogg"))}
        finally {file.delete()}
    }
    @Test fun hashMismatchOrRevokedPermissionNeverPublishes() {
        val docs=Memory(); val store=LocalFolderStorage(docs)
        docs.corrupt=true
        assertEquals("文件夹不可用，请重新选择",runCatching{store.text("录音/a.txt","body")}.exceptionOrNull()?.message)
        assertFalse(docs.files.containsKey("录音/a.txt"))
        docs.corrupt=false; docs.revoked=true
        assertEquals("文件夹不可用，请重新选择",runCatching{store.size("录音/a.txt")}.exceptionOrNull()?.message)
        assertEquals("文件夹不可用，请重新选择",runCatching{store.text("录音/a.txt","body")}.exceptionOrNull()?.message)
    }
}
