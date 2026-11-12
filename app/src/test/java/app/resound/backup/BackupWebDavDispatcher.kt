package app.resound.backup

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.Collections

/** 备份仓库测试用的有状态 WebDAV；PUT/MOVE 后可列出并读回原始字节。 */
internal class BackupWebDavDispatcher : Dispatcher() {
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    @Volatile var failBackupUploads = false
    @Volatile var failDiaryUploads = false
    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf("/dav/", "/dav/${WebDavClient.FOLDER}/")

    fun read(path: String): ByteArray = synchronized(files) { files.getValue(path).clone() }
    fun paths(): Set<String> = synchronized(files) { files.keys.toSet() }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) {
        requests += request
        val url = request.requestUrl!!
        val path = url.encodedPath
        val directory = path.trimEnd('/') + "/"
        fun response(code: Int) = MockResponse().setResponseCode(code)
        when (request.method) {
            "PROPFIND" -> if (directory !in directories) response(404) else {
                val rows = files.filterKeys { it.startsWith(directory) && '/' !in it.removePrefix(directory) }
                    .map { (name, bytes) ->
                        "<d:response><d:href>$name</d:href><d:propstat><d:prop>" +
                            "<d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop>" +
                            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }.joinToString("")
                response(207).setBody("<d:multistatus xmlns:d='DAV:'>$rows</d:multistatus>")
            }
            "MKCOL" -> { directories += directory; response(201) }
            "GET" -> files[path]?.let { response(200).setBody(Buffer().write(it)) } ?: response(404)
            "HEAD" -> response(if (path in files) 200 else 404)
            "PUT" -> when {
                (failBackupUploads && WebDavClient.FOLDER in url.pathSegments) ||
                    (failDiaryUploads && WebDavClient.DIARY_FOLDER in url.pathSegments) -> response(503)
                path.substringBeforeLast('/') + "/" !in directories -> response(409)
                else -> { files[path] = request.body.clone().readByteArray(); response(201) }
            }
            "MOVE" -> {
                val destination = request.getHeader("Destination")!!.toHttpUrl().encodedPath
                val bytes = files.remove(path)
                if (bytes == null) response(404) else { files[destination] = bytes; response(201) }
            }
            "DELETE" -> { files.remove(path); response(204) }
            else -> response(405)
        }
    }
}
