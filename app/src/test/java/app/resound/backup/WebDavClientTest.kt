package app.resound.backup

import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], manifest = org.robolectric.annotation.Config.NONE, application = android.app.Application::class)
class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    @Before fun setup() {
        server = MockWebServer(); server.start()
        client = WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "me@example.com", "private-password")
    }
    @After fun close() { server.shutdown() }
    private fun response(vararg names: String) = MockResponse().setResponseCode(207).setBody(
        "<d:multistatus xmlns:d='DAV:'>" + names.joinToString("") {
            "<d:response><d:href>${server.url("/dav/").newBuilder().addPathSegment(WebDavClient.FOLDER).addPathSegment(it).build().encodedPath}</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        } + "</d:multistatus>")
    @Test fun parsesEncodedNamesAndIgnoresTemporaryFiles() {
        server.enqueue(response("resound-2026-10-23-0310.zip", "foreign.zip", "回响-2026-10-23-0311.zip.part"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(listOf("resound-2026-10-23-0310.zip"), client.list())
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method); assertEquals("1", request.getHeader("Depth"))
        assertEquals(Credentials.basic("me@example.com", "private-password"), request.getHeader("Authorization"))
    }
    /** 坚果云等服务返回的 href 写法不一：小写百分号编码、直接中文、带域名的完整地址，都要认得 */
    @Test fun acceptsHrefVariantsFromServers() {
        val encoded = server.url("/dav/").newBuilder().addPathSegment("回响备份").addPathSegment("回响-2026-10-24-1237-23965-abcdef12.zip").build().encodedPath
        fun item(href: String) = "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>10</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        server.enqueue(MockResponse().setResponseCode(404)) // 新目录尚未建立；仍须列出旧目录。
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'>" +
            item(encoded.lowercase()) +
            item("/dav/回响备份/回响-2026-10-24-1238-00001-abcdef12.zip") +
            item(server.url("/dav/").toString().removeSuffix("/dav/") + "/dav/%E5%9B%9E%E5%93%8D%E5%A4%87%E4%BB%BD/%E5%9B%9E%E5%93%8D-2026-10-24-1239-00002-abcdef12.zip") +
            item("/dav/别的目录/回响-2026-10-24-1240-00003-abcdef12.zip") +
            "</d:multistatus>"))
        assertEquals(listOf("回响-2026-10-24-1237-23965-abcdef12.zip", "回响-2026-10-24-1238-00001-abcdef12.zip", "回响-2026-10-24-1239-00002-abcdef12.zip"),
            client.list())
    }
    @Test fun missingDirectoryIsCreated() {
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201))
        client.ensureDirectory()
        assertEquals("PROPFIND", server.takeRequest().method); assertEquals("MKCOL", server.takeRequest().method)
    }
    @Test fun uploadPublishesOnlyAfterPutAndCleansOwnPart() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }
        val file = File.createTempFile("backup", ".zip").apply { writeText("archive") }
        try { client.upload(file, "resound-2026-10-23-0310.zip") } finally { file.delete() }
        val put = server.takeRequest(); val move = server.takeRequest(); val delete = server.takeRequest()
        assertEquals("PUT", put.method); assertEquals("archive", put.body.readUtf8()); assertTrue(put.path!!.endsWith(".part"))
        assertEquals("MOVE", move.method); assertEquals(put.path, move.path); assertEquals("F", move.getHeader("Overwrite"))
        assertTrue(move.getHeader("Destination")!!.endsWith("0310.zip"))
        assertEquals("DELETE", delete.method); assertEquals(put.path, delete.path)
    }
    @Test fun failedUploadNeverDeletesFormalBackups() {
        server.enqueue(MockResponse().setResponseCode(507)); server.enqueue(MockResponse().setResponseCode(404))
        val file = File.createTempFile("backup", ".zip")
        try { client.upload(file, "resound-2026-10-23-0310.zip"); fail() }
        catch (e: BackupFailure) { assertEquals("坚果云空间不足", e.human) }
        finally { file.delete() }
        assertEquals("PUT", server.takeRequest().method)
        val delete = server.takeRequest(); assertEquals("DELETE", delete.method); assertTrue(delete.path!!.endsWith(".part"))
        assertEquals(2, server.requestCount)
    }
    @Test fun onlyOldestExcessBackupsAreDeleted() {
        server.enqueue(response(*(1..10).reversed().map { "resound-2026-10-%02d-0310.zip".format(it) }.toTypedArray()))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertEquals(7, client.retain(7)); server.takeRequest()
        // 删的是最旧的 3 份；先删哪份不重要
        val deleted = (1..3).map { server.takeRequest().also { r -> assertEquals("DELETE", r.method) }.path!!.takeLast(14) }.toSet()
        assertEquals((1..3).map { "10-%02d-0310.zip".format(it) }.toSet(), deleted)
    }
    @Test fun fixedErrorsNeverExposeServerText() {
        listOf(401 to "账号或应用密码不对", 507 to "坚果云空间不足", 503 to "坚果云暂时不可用").forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("secret diagnostic password"))
            try { client.list(); fail() } catch (e: Exception) { assertEquals(expected, backupError(e).human) }
        }
        assertEquals("网络不通", backupError(IOException("private detail")).human)
        server.shutdown()
        try { client.list(); fail() } catch (e: IOException) { assertEquals("网络不通", backupError(e).human) }
    }
    @Test fun writeProbeCreatesAndRemovesTemporaryFile() {
        server.enqueue(response()); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(204))
        client.testWrite(); server.takeRequest()
        val put = server.takeRequest(); val delete = server.takeRequest()
        assertEquals("PUT", put.method); assertEquals("DELETE", delete.method); assertEquals(put.path, delete.path)
    }
    @Test fun legacyFolderIsReadOnlyAndRecognitionIgnoresDisplayName() {
        val renamed = WebDavClient(BackupConfig(server.url("/dav/").toString()), "新名称", "test@example.com", "test")
        assertNotNull(renamed.metadata("回响-2026-10-23-0310.zip"))
        assertNotNull(renamed.metadata("resound-2026-10-23-0310.zip"))
        assertNull(renamed.metadata("新名称-2026-10-23-0310.zip"))
        val legacy = "回响-2026-10-23-0310.zip"
        try { renamed.delete(legacy); fail() } catch (_: IllegalArgumentException) { }
        val file = File.createTempFile("resound", ".zip")
        try {
            try { renamed.upload(file, legacy); fail() } catch (_: IllegalArgumentException) { }
            server.enqueue(MockResponse().setBody("legacy archive"))
            renamed.download(legacy, file)
            assertEquals("legacy archive", file.readText())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(listOf("dav", "回响备份", legacy), request.requestUrl!!.pathSegments)
            assertEquals(1, server.requestCount)
        } finally { file.delete() }
    }

    @Test fun combinesBothFoldersButRetentionDeletesOnlyEnglishBackups() {
        val recent = "resound-2026-10-24-0310.zip"
        val old = "resound-2026-10-22-0310.zip"
        val legacy = "回响-2026-10-23-0310.zip"
        server.enqueue(response(recent, old, legacy)) // 新目录中的错放文件不能路由到旧目录去删。
        val href = server.url("/dav/").newBuilder().addPathSegment("回响备份").addPathSegment(legacy).build().encodedPath.lowercase()
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            "<d:multistatus xmlns:d='DAV:'><d:response><d:href>$href</d:href>" +
                "<d:status>HTTP/1.1 200 OK</d:status></d:response></d:multistatus>"))
        assertEquals(listOf(recent, legacy, old), client.backups().map { it.name })
        server.takeRequest(); server.takeRequest()
        server.enqueue(response(recent, old, legacy))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(1, client.retain(1))
        assertEquals(listOf("dav", WebDavClient.FOLDER, ""), server.takeRequest().requestUrl!!.pathSegments)
        val deleted = server.takeRequest()
        assertEquals("DELETE", deleted.method)
        assertEquals(listOf("dav", WebDavClient.FOLDER, old), deleted.requestUrl!!.pathSegments)
        assertEquals(4, server.requestCount)
    }

}
