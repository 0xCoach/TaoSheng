package app.resound.backup

import android.app.Application
import app.resound.push.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.time.LocalDate
import java.util.UUID

/** 本机临时容器；默认不访问任何云服务。需显式设置 RESOUND_MINIO_ENDPOINT 才运行。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Minio114Test {
    private lateinit var settings: S3Settings
    private lateinit var s3: S3Client
    private lateinit var root: File
    private val http = OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }).build()
    @Before fun setup() {
        val endpoint = System.getenv("RESOUND_MINIO_ENDPOINT")
        Assume.assumeTrue("未启动本卡 MinIO 临时容器", !endpoint.isNullOrBlank())
        settings = S3Settings("minio", endpoint!!, "us-east-1", "resound-test", "run-${UUID.randomUUID()}/", true)
        s3 = S3Client(settings, "resound-test", "resound-test-only-secret", http, pageSize = 1)
        root = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
    }
    @After fun cleanup() { if (::root.isInitialized) root.deleteRecursively() }
    @Test fun minioCrudPaginationVolumesAndDiary() {
        s3.ensureDirectory()
        val source = File(root, "source").apply { writeText("中文 + % / backup") }
        val target = File(root, "target")
        val names = (1..3).map { "resound-2026-11-0$it-0310.zip" }
        names.forEach { s3.upload(source, it) }
        assertEquals(names.reversed(), s3.backups().map { it.name }) // max-keys=1：真正走三页。
        s3.download(names.last(), target); assertEquals(source.readText(), target.readText())
        assertEquals(1, s3.retain(1)); assertEquals(listOf(names.last()), s3.list())
        s3.delete(names.last()); assertTrue(s3.backups().isEmpty())
        val volumes = s3.volumes(); val day = "days/resound-day-2026-11-09.zip"
        assertFalse(volumes.exists(day)); volumes.put(day, source); assertTrue(volumes.exists(day))
        assertTrue(volumes.read(day, target)); assertEquals(source.readText(), target.readText())
        volumes.copy(day, "days/resound-day-2026-11-08.zip")
        assertTrue(volumes.exists("days/resound-day-2026-11-08.zip"))
        val objectPath = "objects/${"a".repeat(64)}.bin"
        volumes.put(objectPath, source); assertEquals(listOf(objectPath), volumes.files("objects").map { it.path })
        volumes.delete(day); assertFalse(volumes.read(day, target))
        s3.uploadDiary(LocalDate.parse("2026-11-09"), "# 日记")
        assertEquals("# 日记", s3.readText("Resound-Diary/2026-11-09.md")!!.first)
        s3.testWrite()
    }
    @Test fun linkedS3PushPersistsOnlyFlagAndUploadsIntoSeparateFolder() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("push114-${UUID.randomUUID()}", 0)
        val channels = AndroidPushChannels(prefs, context.getSharedPreferences("outbox114", 0), object : PushSecrets {
            override fun read(ref: String) = error("借用模式不读推送密钥")
            override fun write(ref: String, value: String) = error("借用模式不复制密钥")
            override fun remove(ref: String) = Unit
        }, PushTransport(http), {}, stateDirectory = root)
        channels.linkedCredentials = { mapOf("cloudKind" to "S3", "s3" to s3Json(settings).toString(), "url" to settings.endpoint,
            "account" to "resound-test", "password" to "resound-test-only-secret") }
        val id = channels.saveWithSubscription(null, PushKind.MarkdownFolder, "S3 记忆", mapOf("folder" to "Resound-Memory"),
            PushSubscription(), PushFormat.Markdown, true).getOrThrow()
        assertTrue(channels.channels.value.single().useLinkedNutstore)
        val stored = prefs.getString("channels", "")!!
        assertFalse(stored.contains("resound-test-only-secret")); assertFalse(stored.contains("access"))
        val values = channels.resolveAudio(id)!!.second
        val state = PushSyncState(File(root, "folder-state"))
        val batch = PushTransport(http).syncFolder(values, state, mapOf("README.md" to "# 测试文档"), false, 0)
        assertEquals(1, batch.updated)
        assertEquals("# 测试文档", s3.readText("Resound-Memory/README.md")!!.first)
        assertTrue(s3.objects("Resound-Backup/").isEmpty())
        assertNotEquals(folderIdentity(values), folderIdentity(values + ("s3" to s3Json(settings.copy(prefix = "elsewhere/")).toString())))
        val next = PushTransport(http).syncFolder(values, state, emptyMap(), false, 1)
        assertEquals(1, next.deleted); assertNull(s3.readText("Resound-Memory/README.md"))
    }
}
