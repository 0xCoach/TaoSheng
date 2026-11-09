package app.resound.backup

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 单次 PUT 原子发布，不用 multipart，不跟随重定向，不记录密钥或原始错误响应。 */
internal class S3Client(
    private val settings: S3Settings, private val access: String, private val secret: String,
    client: OkHttpClient = OkHttpClient(), private val now: () -> Instant = Instant::now,
    private val pageSize: Int = 1000,
) : RemoteStore {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES).build()
    private val endpoint = settings.endpoint.toHttpUrl()
    private val prefix = settings.prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val backup = "Resound-Backup/"
    init { require(pageSize in 1..1000) }
    internal fun url(key: String? = null): HttpUrl = endpoint.newBuilder().apply {
        if (settings.pathStyle) addPathSegment(settings.bucket) else host("${settings.bucket}.${endpoint.host}")
        if (key == null) addPathSegment("") else {
            require(key.isNotEmpty() && key.split('/').none { it == "." || it == ".." || '\\' in it || it.any(Char::isISOControl) })
            (prefix + key).split('/').forEach { addEncodedPathSegment(S3Signing.encode(it)) }
        }
    }.build()
    private fun request(method: String, target: HttpUrl, body: RequestBody? = null, hash: String = S3Signing.hash(byteArrayOf()),
                        missing: Boolean = false, headers: Map<String, String> = emptyMap()): Response {
        val raw = Request.Builder().url(target).method(method, body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        // OBS 官方 V4 仅支持 unsigned 或流式负载；本实现只用 HTTPS unsigned，其他服务均签实际摘要。
        val payload = if (settings.presetId == "obs" || endpoint.host.endsWith(".myhuaweicloud.com")) {
            require(endpoint.isHttps) { "OBS V4 请使用 HTTPS" }; "UNSIGNED-PAYLOAD"
        } else hash
        val response = client.newCall(S3Signing.sign(raw, settings.region, access, secret, payload, now())).execute()
        if (response.isSuccessful) return response
        val code = runCatching { xml(response.peekBody(65536).byteStream()).firstOrNull { it.first == "Code" }?.second }.getOrNull()
        // HEAD 404 无响应体；先确认桶可读，避免把桶不存在冒充对象不存在。
        if (missing && response.code == 404 && code != "NoSuchBucket") return response
        val failure = failure(response.code, code)
        response.close()
        throw failure
    }
    private fun existsKey(key: String): Boolean = request("HEAD", url(key), missing = true).use {
        if (it.code != 404) true else { checkBucket(); false }
    }
    private fun checkBucket() { request("HEAD", url()).close() }
    internal fun putObject(key: String, source: File) {
        checkSize(source.length())
        request("PUT", url(key), source.asRequestBody(), BackupArchive.sha(source)).close()
    }
    internal fun putText(key: String, value: String, headers: Map<String, String> = emptyMap()) {
        val bytes = value.toByteArray(Charsets.UTF_8); checkSize(bytes.size.toLong())
        request("PUT", url(key), bytes.toRequestBody(), S3Signing.hash(bytes), headers = headers).close()
    }
    internal fun readText(key: String): Pair<String, String?>? = request("GET", url(key), missing = true).use { response ->
        if (response.code == 404) { checkBucket(); null } else {
            val body = response.body ?: throw BackupFailure("S3 返回了空响应")
            val bytes = body.byteStream().readBytesLimited(2 * 1024 * 1024)
            bytes.toString(Charsets.UTF_8) to response.header("ETag")
        }
    }
    internal fun deleteObject(key: String) { request("DELETE", url(key)).close() }
    private fun readObject(key: String, target: File): Boolean = request("GET", url(key), missing = true).use { response ->
        if (response.code == 404) { checkBucket(); false } else {
            try {
                response.body!!.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break
                        total += count; if (total > 2L * 1024 * 1024 * 1024) throw BackupFailure("备份分卷超过 2 GB，无法恢复")
                        output.write(buffer, 0, count)
                    }
                } }; true
            } catch (e: Exception) { target.delete(); throw e }
        }
    }
    /** 全部分页成功后才返回，分页损坏/循环直接报错，禁止基于半份清单清理恢复点。 */
    internal fun objects(directory: String): List<VolumeFile> {
        val result = mutableListOf<VolumeFile>(); val tokens = mutableSetOf<String>(); var token: String? = null
        do {
            val target = url().newBuilder().addQueryParameter("list-type", "2").addQueryParameter("prefix", prefix + directory)
                .addQueryParameter("delimiter", "/").addQueryParameter("encoding-type", "url").addQueryParameter("max-keys", pageSize.toString())
                .apply { token?.let { addQueryParameter("continuation-token", it) } }.build()
            request("GET", target).use { response ->
                val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
                parser.setInput(response.body!!.byteStream(), "UTF-8")
                var key: String? = null; var size: Long? = null; var modified = 0L; var contents = false
                var truncated: Boolean? = null; var next: String? = null; var encoded = false; var root = false; var events = 0
                val page = mutableListOf<VolumeFile>()
                while (true) {
                    val event = parser.nextToken()
                    if (++events > 200_000 || event == XmlPullParser.DOCDECL) throw BackupFailure("S3 返回了无效的对象列表")
                    if (event == XmlPullParser.END_DOCUMENT) break
                    if (event == XmlPullParser.START_TAG) when (parser.name) {
                        "ListBucketResult" -> root = true
                        "Contents" -> { contents = true; key = null; size = null; modified = 0 }
                        "Key" -> if (contents) key = parser.nextText()
                        "Size" -> if (contents) size = parser.nextText().toLongOrNull()
                        "LastModified" -> if (contents) modified = Instant.parse(parser.nextText()).toEpochMilli()
                        "IsTruncated" -> truncated = parser.nextText().toBooleanStrictOrNull()
                        "NextContinuationToken" -> next = parser.nextText()
                        "EncodingType" -> encoded = parser.nextText() == "url"
                    }
                    if (event == XmlPullParser.END_TAG && parser.name == "Contents") {
                        if (key == null || size == null || size!! < 0) throw BackupFailure("S3 返回了无效的对象列表")
                        page += VolumeFile(key!!, size!!, modified); contents = false
                    }
                }
                if (!root || truncated == null) throw BackupFailure("S3 返回了无效的对象列表")
                page.forEach { item ->
                    val path = if (encoded) java.net.URLDecoder.decode(item.path.replace("+", "%2B"), "UTF-8") else item.path
                    if (!path.startsWith(prefix + directory)) throw BackupFailure("S3 返回了目录之外的对象")
                    val relative = path.removePrefix(prefix + directory)
                    if (relative.isNotEmpty() && '/' !in relative) result += item.copy(path = directory + relative)
                }
                token = if (truncated == true) next?.takeIf { it.isNotEmpty() && tokens.add(it) }
                    ?: throw BackupFailure("S3 分页标记无效，请重试") else null
            }
        } while (token != null)
        return result.distinctBy { it.path }
    }
    override fun ensureDirectory() { checkBucket() }
    override fun backups(): List<RemoteBackup> = objects(backup).mapNotNull { metadata(it.path.removePrefix(backup), it.bytes) }.sortedByDescending { it.at }
    override fun upload(file: File, name: String) { require(metadata(name) != null && name.startsWith("resound-")); putObject(backup + name, file) }
    override fun download(name: String, destination: File) {
        require(metadata(name) != null)
        if (!readObject(backup + name, destination)) throw BackupFailure("备份文件不存在")
    }
    override fun delete(name: String) { require(metadata(name) != null && name.startsWith("resound-")); deleteObject(backup + name) }
    override fun retain(count: Int): Int {
        require(count > 0); val files = backups(); val keep = BackupRetention.names(files, count)
        files.filter { it.name !in keep }.forEach { delete(it.name) }; return files.count { it.name in keep }
    }
    override fun uploadDiary(date: LocalDate, markdown: String) = putText("Resound-Diary/$date.md", markdown)
    override fun testWrite() {
        checkBucket(); val key = backup + ".probe-${UUID.randomUUID()}.part"
        try { putText(key, ""); objects(backup) } finally { deleteObject(key) }
    }
    override fun volumes(): VolumeStore = object : VolumeStore {
        private fun key(path: String): String {
            require(volumePath(path) || '/' !in path && metadata(path) != null); return backup + path
        }
        override fun read(path: String, target: File) = readObject(key(path), target)
        override fun exists(path: String) = existsKey(key(path))
        override fun put(path: String, source: File) = putObject(key(path), source)
        override fun delete(path: String) = deleteObject(key(path))
        override fun copy(from: String, to: String) {
            // 复用 GET/PUT，兼容不支持 CopyObject 的服务；只为旧分卷兼容使用。
            val temporary = File.createTempFile("resound-s3-copy", ".bin")
            try { if (!read(from, temporary)) throw BackupFailure("要复制的备份分卷不存在"); put(to, temporary) }
            finally { temporary.delete() }
        }
        override fun legacy() = backups().map { it.name }
        override fun files(directory: String): List<VolumeFile> {
            require(directory in setOf("history", "objects"))
            return objects("$backup$directory/").map { it.copy(path = it.path.removePrefix(backup)) }.filter { volumePath(it.path) }
        }
    }
    companion object {
        internal fun checkSize(bytes: Long) { if (bytes > 5L * 1024 * 1024 * 1024) throw BackupFailure("单个对象超过 5 GB，暂不支持分块上传") }
        internal fun failure(status: Int, code: String?): BackupFailure = when {
            code in setOf("RequestTimeTooSkewed", "RequestExpired", "RequestInTheFuture", "RequestNotYetValid", "RequestTimeTooSkewedException") -> BackupFailure("手机时间不准，请到系统设置里打开自动设置时间")
            code in setOf("InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken", "ExpiredToken") -> BackupFailure("S3 访问密钥不正确，请检查访问密钥 ID 和私有访问密钥")
            code == "NoSuchBucket" -> BackupFailure("S3 桶不存在，请检查桶名和服务地址")
            code in setOf("AuthorizationHeaderMalformed", "IncorrectEndpoint", "IllegalLocationConstraintException", "PermanentRedirect", "AuthorizationQueryParametersError") || status in 300..399 -> BackupFailure("S3 区域或服务地址不对，请按桶所在区域填写")
            status == 401 || status == 403 -> BackupFailure("S3 无访问权限，请检查密钥及桶的读写、列出和删除权限")
            status == 404 -> BackupFailure("S3 桶或对象不存在，请检查桶名和路径")
            status == 412 -> BackupFailure("文件已被其他客户端修改，请重试")
            status == 413 || code == "EntityTooLarge" -> BackupFailure("单个对象过大，暂不支持分块上传")
            status == 429 || status >= 500 -> BackupFailure("S3 暂时不可用，请稍后重试", true)
            else -> BackupFailure("S3 操作失败，请检查服务地址及 S3 兼容设置")
        }
        private fun xml(input: InputStream): List<Pair<String, String>> = input.use {
            val parser = XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(it, "UTF-8")
            val out = mutableListOf<Pair<String, String>>(); var events = 0
            while (true) {
                val event = parser.nextToken(); require(++events < 10000 && event != XmlPullParser.DOCDECL)
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event == XmlPullParser.START_TAG && parser.name == "Code") out += "Code" to parser.nextText()
            }; out
        }
    }
}
private fun InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break
        if (out.size() + n > max) throw BackupFailure("云端文档过大，无法安全追加")
        out.write(buffer, 0, n)
    }; return out.toByteArray()
}
