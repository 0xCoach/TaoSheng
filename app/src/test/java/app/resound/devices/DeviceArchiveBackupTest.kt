package app.resound.devices

import android.app.Application
import app.resound.backup.*
import app.resound.diagnostics.FailureNotes
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class DeviceArchiveBackupTest {
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var row: DeviceArchiveRecord
    private lateinit var store: VolumeStore
    @Before fun setup() {
        root = Files.createTempDirectory("archive-backup-test-").toFile()
        source = File(root,"source.audio").apply { writeBytes(ByteArray(200000) { (it%97).toByte() }) }
        val hash = DeviceSourceCache.sha256(source)
        row = DeviceArchiveRecord("a".repeat(64),"driver","stable","file",1,1000,2,source.length(),hash,
            source.length(),hash,"Ogg",16000,3,"install",imported=true)
        store = DirectoryVolumeStore(File(root,"remote"))
    }
    @After fun close() { root.deleteRecursively() }
    @Test fun encryptedReadbackAndRestoreReproduceWholeFile() {
        val receipt = DeviceArchiveBackup.upload(store,row,source,"test passphrase","remote",{100},root)
        val backed = row.copy(backup=receipt)
        assertEquals(100L,receipt.verifiedAtMillis)
        assertTrue(DeviceArchiveBackup.verify(store,backed,"test passphrase",root))
        val recovered = DeviceArchiveBackup.discover(store,"stable","remote",root).single()
        assertEquals(0L,recovered.backup!!.verifiedAtMillis)
        assertFalse(recovered.imported)
        val target = File(root,"restored.audio")
        DeviceArchiveBackup.restore(store,recovered,"test passphrase",root,target)
        assertArrayEquals(source.readBytes(),target.readBytes())
        // Equal plaintext encrypted for another record must not overwrite this record's ciphertext.
        DeviceArchiveBackup.upload(store,row.copy(id="b".repeat(64)),source,"test passphrase","remote",{101},root)
        assertTrue(DeviceArchiveBackup.verify(store,backed,"test passphrase",root))
    }
    @Test fun sameSizeMiddleCorruptionFailsWholeFileVerification() {
        val receipt = DeviceArchiveBackup.upload(store,row,source,null,"remote",{100},root)
        val remote = File(root,"remote/${receipt.objectPath}")
        java.io.RandomAccessFile(remote,"rw").use { it.seek(100000);it.write(255) }
        assertThrows(IllegalStateException::class.java) { DeviceArchiveBackup.verify(store,row.copy(backup=receipt),null,root) }
        assertTrue(source.isFile)
    }
    @Test fun successfulPutWithoutReadbackCannotProduceReceipt() {
        val unavailable = object : VolumeStore by store { override fun read(path: String,target: File) = false }
        assertThrows(IllegalStateException::class.java) { DeviceArchiveBackup.upload(unavailable,row,source,null,"remote",{100},root) }
        assertTrue(source.isFile)
    }
    @Test fun unreadableRemoteManifestFailsDiscoveryAndLeavesProblemNote() {
        DeviceArchiveBackup.upload(store,row,source,null,"remote",{100},root)
        val unavailable = object : VolumeStore by store { override fun read(path: String,target: File) = false }
        assertThrows(IllegalStateException::class.java) { DeviceArchiveBackup.discover(unavailable,"stable","remote",root) }
        assertTrue(FailureNotes.dump().contains("backup/DeviceArchiveBackup:discoverManifest IllegalStateException"))
        assertTrue(source.isFile)
    }
}
