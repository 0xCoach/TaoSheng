package app.resound.devices

import app.resound.devices.driver.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DriverFileAdapter143Test {
    @Test fun resumedReadUsesCompletedTransferSizeEvenWhenListSizeIsOnlyEstimated() = runBlocking {
        var listings=0
        val source=object:DeviceFiles {
            override val caps=app.resound.devices.driver.FileCaps(true,false,false,null,Reclaim.Never)
            override suspend fun list():List<RecordingFile> {
                listings++
                return listOf(RecordingFile("one",FileState.Complete,160,AudioFileFormat.Opus20,DeviceTime(10000),40,
                    liveCovered=listOf(MillisRange(0,20))))
            }
            override fun read(file:RecordingFile,fromOffset:Long):Flow<ByteArray> {
                assertEquals(160L,fromOffset)
                return flowOf(ByteArray(160) { 1 })
            }
            override suspend fun confirm(file:RecordingFile,checksum:ByteArray?) {}
            override suspend fun delete(file:RecordingFile) { fail("source must be retained") }
        }
        val link=object:DeviceLink {
            override val mtu=247
            override suspend fun read(service:String,characteristic:String)=byteArrayOf()
            override suspend fun write(service:String,characteristic:String,bytes:ByteArray,withResponse:Boolean) {}
            override fun notifications(service:String,characteristic:String)=emptyFlow<ByteArray>()
        }
        val adapter=DriverFileAdapter("test",source); val file=adapter.list(link).single()
        assertEquals(file,adapter.list(link).single()); assertEquals(1,listings)
        assertEquals(listOf(MillisRange(0,20)),adapter.original(file).liveCovered)
        val local=File.createTempFile("resume-test",".bin")
        try {
            local.writeBytes(ByteArray(160))
            adapter.download(link,file,160).collect { local.appendBytes(it) }
            assertTrue(adapter.validSize(file,local)); assertEquals(320L,local.length())
        } finally { local.delete() }
    }
}
