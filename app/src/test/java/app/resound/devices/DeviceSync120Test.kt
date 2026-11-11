package app.resound.devices

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.importer.*
import app.resound.devices.driver.MillisRange
import app.resound.devices.driver.DriverStore
import app.resound.data.SegmentEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DeviceSync120Test {
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private val card = DeviceCard("test", "test", 1, "0.2.21", CardStatus.Trial, Transport.Ble,
        CardMatch(), offline = OfflineMode.Files, filePlugin = "fake")
    private val file = DeviceFile("a", "a.pcm", 4, 10000, 10000, FileFormat.Pcm16)
    private val link = object : DeviceLink {
        override val mtu = 247
        override suspend fun read(service: String, characteristic: String) = byteArrayOf()
        override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {}
        override fun notifications(service: String, characteristic: String) = emptyFlow<ByteArray>()
    }
    private class Fake(val row: DeviceFile, resume: Boolean = true, checksum: Boolean = false) : FileSourcePlugin, DeviceFileDetails {
        override val id = "fake"
        override val caps = FileCaps(resume, true, checksum)
        var lists = 0; var deleted = 0; var interrupt = false; var bad = false
        val offsets = mutableListOf<Long>()
        override suspend fun list(link: DeviceLink): List<DeviceFile> { lists++; return listOf(row) }
        override fun checksumAlgorithm(file: DeviceFile) = "SHA-256"
        override suspend fun checksum(link: DeviceLink, file: DeviceFile) = ByteArray(32)
        override fun modifiedAt(file: DeviceFile) = 20000L
        override fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long) = flow {
            offsets += fromOffset
            if (bad) { emit(byteArrayOf(9)); return@flow }
            if (fromOffset == 0L) emit(byteArrayOf(1, 2))
            if (interrupt) { interrupt = false; throw java.io.IOException("disconnected") }
            emit(byteArrayOf(3, 4))
        }
        override suspend fun delete(link: DeviceLink, file: DeviceFile) { deleted++ }
    }
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.filesDir, "test-device120").apply { deleteRecursively(); mkdirs() }
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private fun sync(plugin: Fake, ingest: suspend (File, DeviceFile, DeviceSlice, String) -> Unit = { _, _, _, _ -> }) =
        DeviceSync("address", root, db, listOf(plugin), ingest)

    @Test fun overlapUsesPhoneOffsetAndPadding() {
        assertEquals(DeviceSlice(1000, 8000, 12000), deviceSlice(file, 14000, 17000, 1000, null))
        assertNull(deviceSlice(file, 0, 9000, 1000, null))
    }
    @Test fun missingClockUsesModificationAndClampsPadding() {
        assertEquals(DeviceSlice(0, 5000, 10000), deviceSlice(file.copy(startedAt = null), 10000, 13000, null, 20000))
        assertThrows(IllegalArgumentException::class.java) { deviceSlice(file, 10000, 13000, null, null) }
    }
    @Test fun firstPairDoesNotListButMissingPluginIsRecorded() = runBlocking {
        val plugin = Fake(file)
        sync(plugin).onLinked(card, link, null, null, null)
        assertEquals(0, plugin.lists)
        sync(plugin).onLinked(card.copy(filePlugin = "absent"), link, null, null, null)
        assertEquals("DeviceSync", db.capture().events(0, Long.MAX_VALUE).first().single().type)
    }
    @Test fun resumeSurvivesNewInstanceAndOldGapIsRestored() = runBlocking {
        val plugin = Fake(file).apply { interrupt = true }
        sync(plugin).onLinked(card, link, 11000, 13000, 0)
        assertEquals(2L, db.deviceSync().rows("address").single().received)
        sync(plugin).onLinked(card, link, null, null, null)
        assertEquals(listOf(0L, 2L), plugin.offsets)
        assertTrue(db.deviceSync().rows("address").single().imported)
        assertEquals(0, plugin.deleted) // 部分录音保留。
    }
    @Test fun unsupportedResumeRestartsWholeFile() = runBlocking {
        val plugin = Fake(file, false).apply { interrupt = true }
        sync(plugin).importAll(card, link, 0)
        sync(plugin).onLinked(card, link, null, null, null)
        assertEquals(listOf(0L, 0L), plugin.offsets); assertEquals(1, plugin.deleted)
    }
    @Test fun threeBadDownloadsNeverImportOrDelete() = runBlocking {
        val plugin = Fake(file).apply { bad = true }
        sync(plugin) { _, _, _, _ -> fail("不应导入") }.importAll(card, link, 0)
        assertEquals(3, plugin.offsets.size); assertEquals(0, plugin.deleted)
        assertFalse(db.deviceSync().rows("address").single().imported)
    }
    @Test fun checksumMismatchRetriesEvenWhenSizeMatches() = runBlocking {
        val plugin = Fake(file, checksum = true)
        sync(plugin) { _, _, _, _ -> fail("校验未通过") }.importAll(card, link, 0)
        assertEquals(listOf(0L, 0L, 0L), plugin.offsets); assertEquals(0, plugin.deleted)
    }
    @Test fun successfulImportPrecedesDeleteAndDoesNotRepeat() = runBlocking {
        val plugin = Fake(file); var count = 0
        val sync = sync(plugin) { local, _, _, _ -> assertEquals(0, plugin.deleted); assertEquals(4L, local.length()); count++ }
        sync.importAll(card, link, 0); sync.importAll(card, link, 0)
        assertEquals(1, count); assertEquals(1, plugin.deleted)
    }
    @Test fun adjacentGapsDoNotReimportPadding() = runBlocking {
        val plugin = Fake(file); val slices = mutableListOf<DeviceSlice>()
        val sync = sync(plugin) { _, _, slice, _ -> slices += slice }
        sync.onLinked(card, link, 12000, 14000, 0)
        sync.onLinked(card, link, 15000, 17000, 0)
        assertEquals(listOf(DeviceSlice(0, 6000, 10000), DeviceSlice(6000, 9000, 16000)), slices)
    }
    @Test fun liveCoverageIsRecordedAndOnlyUncoveredIntervalsAreImported() = runBlocking {
        val plugin = Fake(file)
        val slices = mutableListOf<DeviceSlice>()
        seedLiveCoverage(db,"address",file,listOf(MillisRange(2000,5000)))
        sync(plugin) { _,_,slice,_ -> slices += slice }.importAll(card,link,0)
        assertEquals(setOf(0L to 2000L,5000L to 10000L),slices.map { it.from to it.to }.toSet())
        assertTrue(db.deviceSync().rows("address").all { it.imported })
        assertEquals(0,plugin.deleted)
    }
    @Test fun crashRecoveryUsesPersistedSegmentsAndSpoolWithoutAssumingClockOffset() = runBlocking {
        val source = "CardDevice:device:a:1000:11000"
        val values = mutableMapOf("audioAnchor:$source" to "1000|11000|11040|a")
        val store = object : DriverStore {
            override fun getString(key:String) = values[key]
            override fun putString(key:String,value:String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun getBytes(key:String):ByteArray? = null
            override fun putBytes(key:String,value:ByteArray?) {}
            override fun keys():Set<String> = values.keys
        }
        db.capture().segment(SegmentEntity(startAt=12010,endAt=13020,sourceId=source,path="one.ogg",sizeBytes=1))
        db.capture().segment(SegmentEntity(startAt=12010,endAt=13020,sourceId="other",path="two.ogg",sizeBytes=1))
        val spool = File(root,"spool").apply { mkdirs() }
        File(spool,"14000_piece.source").writeText(source)
        File(spool,"14000_piece.pcm").writeBytes(ByteArray(32000))
        assertEquals(setOf(MillisRange(2020,2980),MillisRange(4000,4960)),
            recoverDriverCoverage(db,spool,file.copy(format=FileFormat.Opus),store).toSet())
    }
    @Test fun importFailureRetainsSource() = runBlocking {
        val plugin = Fake(file)
        sync(plugin) { _, _, _, _ -> error("failed") }.importAll(card, link, 0)
        assertEquals(0, plugin.deleted); assertFalse(db.deviceSync().rows("address").single().imported)
    }
    @Test fun sharedFingerprintSkipsWithoutUnsafeDelete() = runBlocking {
        val plugin = Fake(file)
        val content = File(root, "shared").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        db.folderImports().remember(ImportFingerprint(audioFingerprint(content), 1))
        sync(plugin) { _, _, _, _ -> fail("重复导入") }.importAll(card, link, 0)
        assertEquals(0, plugin.deleted)
    }
    @Test fun pcmIsCroppedAndWrappedAsStandardWav() = runBlocking {
        val input = File(root, "raw").apply { writeBytes(ByteArray(32000 * 5) { (it % 91).toByte() }) }
        val output = File(root, "clip.wav")
        deviceWav(input, output, FileFormat.Pcm16, DeviceSlice(1000, 3000, 0))
        val bytes = output.readBytes()
        assertEquals(64044, bytes.size)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertArrayEquals(input.readBytes().copyOfRange(32000, 96000), bytes.copyOfRange(44, bytes.size))
    }
    @Test fun protocolMatchesRealDownloadFrameAndReassembles() {
        val expected = "5a 03 9e 20 1e 00 02 02 00 00 00 00 6e 6f 74 65 32 30 32 36 30 37 31 30 2d 31 36 32 39 33 38 2e 77 61 76 00"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val frames = SyntheticFileFrames()
        repeat(3) { frames.command(0) }
        assertArrayEquals(expected, frames.download("note20260710-162938.wav"))
        val parser = SyntheticFileFrames.Parser()
        assertTrue(parser.feed(expected.copyOfRange(0, 10)).isEmpty())
        assertArrayEquals(expected.copyOfRange(6, 36), parser.feed(expected.copyOfRange(10, 36)).single())
    }
    @Test fun streamAcceptsCoalescedAndRepeatedFramesButRejectsCorruption() {
        // 官方真实抓包，用于独立核对 CRC，而非用待测编码器自证。
        val packet = "5a 03 9e 20 1e 00 02 02 00 00 00 00 6e 6f 74 65 32 30 32 36 30 37 31 30 2d 31 36 32 39 33 38 2e 77 61 76 00"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val parser = SyntheticFileFrames.Parser()
        val result = parser.feed(byteArrayOf(0, 1) + packet + packet + packet.copyOfRange(0, 5))
        assertEquals(2, result.size)
        result.forEach { assertArrayEquals(packet.copyOfRange(6, packet.size), it) }
        assertEquals(1, parser.feed(packet.copyOfRange(5, packet.size)).size)
        val corrupt = packet.copyOf().also { it[it.lastIndex] = 1 }
        assertThrows(IllegalStateException::class.java) { SyntheticFileFrames.Parser().feed(corrupt) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticFileFrames.Parser().feed(byteArrayOf(0x5a, 0, 0, 0, 1, 0x20)) }
    }

    @Test fun migrationFrom30PreservesDataAndPersistsTransfers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "device120-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/30.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            val queries = schema.getJSONArray("setupQueries")
            repeat(queries.length()) { old.execSQL(queries.getString(it)) }
            old.execSQL("INSERT INTO battery VALUES (1,1000,80,0)"); old.version = 30
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals(80, migrated.capture().batteries(0, 2000).first().single().percent)
            val row = DeviceTransfer("key", "d", "f", "a", 4, 0, 1, "Pcm16", 0, 1, 0, 2)
            migrated.deviceSync().save(row); assertEquals(row, migrated.deviceSync().rows("d").single())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
