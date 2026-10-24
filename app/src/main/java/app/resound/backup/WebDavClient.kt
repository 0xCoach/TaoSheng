package app.resound.backup

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class BackupConfig(val endpoint: String = "https://dav.jianguoyun.com/dav/")
internal class BackupFailure(val human: String, val retryable: Boolean = false) : IOException(human)
internal fun backupError(e: Exception): BackupFailure = when (e) {
    is BackupFailure -> e
    is IOException -> BackupFailure("网络不通", true)
    else -> BackupFailure("备份处理失败，请重试")
}

/** 不跟随重定向，防止认证头和备份被送往其他地址。所有路径由单个 segment 编码。 */
internal class WebDavClient(
    config: BackupConfig, @Suppress("UNUSED_PARAMETER") appName: String, account: String, password: String,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
        .followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build(),
) {
    private val root = config.endpoint.toHttpUrl()
    // 云端目录和文件名用英文，避免中文在各家 WebDAV 里编码不一致；旧的中文目录只读，用于恢复以前的备份
    private val directory = folder(FOLDER)
    private val legacyDirectory = folder("回响备份")
    private val legacyPrefix = "回响-"
    private val auth = Credentials.basic(account, password, Charsets.UTF_8)
    private fun folder(name: String) = root.newBuilder().addPathSegment(name).addPathSegment("").build()
    private fun folderOf(name: String) = if (name.startsWith(legacyPrefix)) legacyDirectory else directory
    internal fun metadata(name: String, bytes: Long = 0): RemoteBackup? = BackupNames.metadata(name, bytes)
    private fun url(name: String) = folderOf(name).newBuilder().addPathSegment(name).build()
    private fun request(method: String, target: HttpUrl, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url(target).header("Authorization", auth).method(method, body)
        headers.forEach { (key, value) -> request.header(key, value) }
        val response = client.newCall(request.build()).execute()
        if (response.isSuccessful) return response
        val code = response.code
        response.close()
        android.util.Log.w("ResoundBackup", "坚果云拒绝: $method ${target.pathSegments.takeLast(2).joinToString("/")} → HTTP $code")
        throw when (code) {
            401, 403 -> BackupFailure("账号或应用密码不对")
            507 -> BackupFailure("坚果云空间不足")
            404 -> BackupFailure("备份目录不存在")
            408, 429, in 500..599 -> BackupFailure("坚果云暂时不可用", true)
            else -> BackupFailure("坚果云操作失败")
        }
    }
    fun ensureDirectory() {
        try { listIn(directory); return } catch (e: BackupFailure) { if (e.human != "备份目录不存在") throw e }
        request("MKCOL", directory).close()
    }
    fun list(): List<String> = backups().asReversed().map { it.name }
    /** 新目录 + 旧中文目录里的全部备份（恢复列表用），新的在前；新目录还没建时视为空 */
    fun backups(): List<RemoteBackup> {
        fun quiet(dir: HttpUrl) = try { listIn(dir) } catch (e: BackupFailure) { if (e.human == "备份目录不存在") emptyList() else throw e }
        return (quiet(directory) + runCatching { quiet(legacyDirectory) }.getOrDefault(emptyList()))
            .sortedWith(compareByDescending<RemoteBackup> { it.at }.thenByDescending { it.name })
    }
    private fun listIn(dir: HttpUrl): List<RemoteBackup> = request("PROPFIND", dir, "".toRequestBody(), mapOf("Depth" to "1")).use { response ->
        // Android 自带 PullParser；不用各 Android 版本支持不一致的 DOM 安全 feature。
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        val names = linkedMapOf<String, RemoteBackup>()
        response.body!!.byteStream().use { input ->
            parser.setInput(input, "UTF-8")
            var href: String? = null
            var collection = false
            var success = false
            var size = 0L
            var propSize = 0L
            var propSuccess = false
            var inPropstat = false
            var inResponse = false
            var events = 0
            while (true) {
                val event = parser.nextToken()
                require(++events < 200_000)
                require(event != XmlPullParser.DOCDECL) // 禁止 DTD / 外部实体。
                if (event == XmlPullParser.END_DOCUMENT) break
                if (parser.namespace != "DAV:") continue
                if (event == XmlPullParser.START_TAG) when (parser.name) {
                    "response" -> { inResponse = true; href = null; collection = false; success = false; size = 0L }
                    "href" -> if (inResponse) href = parser.nextText()
                    "collection" -> if (inResponse) collection = true
                    "propstat" -> { inPropstat = true; propSuccess = false; propSize = 0L }
                    "getcontentlength" -> if (inResponse) propSize = parser.nextText().toLongOrNull()?.coerceAtLeast(0) ?: 0
                    "status" -> if (inResponse) {
                        val ok = parser.nextText().contains(" 200 ")
                        if (inPropstat) propSuccess = ok else success = ok
                    }
                }
                if (event == XmlPullParser.END_TAG && parser.name == "propstat") {
                    if (propSuccess) { success = true; size = maxOf(size, propSize) }
                    inPropstat = false
                }
                if (event == XmlPullParser.END_TAG && parser.name == "response") {
                    val resolved = href?.let(dir::resolve)
                    val name = resolved?.pathSegments?.lastOrNull()
                    // 按解码后的路径比：服务器返回的编码大小写、中文写法各不相同（坚果云就会用小写编码）
                    val inDirectory = resolved != null && resolved.scheme == dir.scheme && resolved.host == dir.host && resolved.port == dir.port &&
                        resolved.pathSegments.dropLast(1) == dir.pathSegments.dropLast(1)
                    if (!collection && success && !name.isNullOrEmpty() && inDirectory) {
                        metadata(name, size)?.takeIf { folderOf(name) == dir }?.let { names[name] = it }
                    }
                    inResponse = false
                }
            }
        }
        names.values.sortedWith(compareByDescending<RemoteBackup> { it.at }.thenByDescending { it.name })
    }

    fun upload(file: File, name: String) {
        require(BackupNames.metadata(name) != null && name.startsWith("$PREFIX-"))
        val part = "$name.${UUID.randomUUID()}.part"
        try {
            request("PUT", url(part), file.asRequestBody()).close()
            request("MOVE", url(part), headers = mapOf("Destination" to url(name).toString(), "Overwrite" to "F")).close()
        } finally {
            // 仅清理本次临时文件；失败绝不触碰旧正式备份。
            runCatching { delete(part) }
        }
    }
    /** 日记是固定路径的覆盖上传；与 ZIP 目录、保留策略分离。 */
    fun uploadDiary(date: java.time.LocalDate, markdown: String) {
        val diaryDirectory = folder(DIARY_FOLDER)
        try {
            request("PROPFIND", diaryDirectory, "".toRequestBody(), mapOf("Depth" to "0")).close()
        } catch (e: BackupFailure) {
            if (e.human != "备份目录不存在") throw e
            request("MKCOL", diaryDirectory).close()
        }
        val target = diaryDirectory.newBuilder().addPathSegment("$date.md").build()
        request("PUT", target, markdown.toRequestBody("text/markdown; charset=utf-8".toMediaType())).close()
    }

    fun retain(count: Int): Int {
        require(count > 0)
        val files = listIn(directory) // 只清理新目录，旧中文目录里的不动
        val retained = BackupRetention.names(files, count)
        files.filter { it.name !in retained }.forEach { delete(it.name) }
        return files.count { it.name in retained }
    }
    fun delete(name: String) {
        require(!name.startsWith(legacyPrefix)) { "旧备份目录只读" }
        request("DELETE", url(name)).close()
    }
    fun download(name: String, destination: File) {
        require(metadata(name) != null) { "备份文件名无效" }
        request("GET", url(name)).use { response ->
            response.body!!.byteStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }
    /** 固定英文分卷路径；404 才表示缺失，鉴权/网络错误不能当作空清单。 */
    fun volumes(): VolumeStore = object : VolumeStore {
        private fun target(path: String): HttpUrl {
            require(volumePath(path) || ('/' !in path && path.startsWith("resound-") && metadata(path) != null))
            return directory.newBuilder().apply { path.split('/').forEach { addPathSegment(it) } }.build()
        }
        private fun missing(block: () -> Unit): Boolean = try { block(); true } catch (e: BackupFailure) {
            if (e.human == "备份目录不存在") false else throw e
        }
        private fun parents(path: String) {
            if ('/' !in path) return
            val dir = directory.newBuilder().addPathSegment(path.substringBefore('/')).addPathSegment("").build()
            if (!missing { request("PROPFIND", dir, "".toRequestBody(), mapOf("Depth" to "0")).close() })
                request("MKCOL", dir).close()
        }
        override fun read(path: String, target: File): Boolean = missing {
            request("GET", target(path)).use { response ->
                response.body!!.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= 2L * 1024 * 1024 * 1024); output.write(buffer, 0, n) }
                } }
            }
        }
        override fun exists(path: String) = missing { request("HEAD", target(path)).close() }
        override fun put(path: String, source: File) {
            parents(path)
            val destination = target(path)
            val temporary = destination.newBuilder().setPathSegment(destination.pathSegments.lastIndex,
                destination.pathSegments.last() + ".${UUID.randomUUID()}.part").build()
            try {
                request("PUT", temporary, source.asRequestBody()).close()
                try {
                    request("MOVE", temporary, headers = mapOf("Destination" to destination.toString(), "Overwrite" to "T")).close()
                } catch (e: BackupFailure) {
                    // 坚果云不允许 MOVE 覆盖已存在的文件（返回 409）：先删旧的再移。旧内容覆盖前已按指纹留了 .previous 副本，中途失败也能恢复
                    if (!exists(path)) throw e
                    request("DELETE", destination).close()
                    request("MOVE", temporary, headers = mapOf("Destination" to destination.toString(), "Overwrite" to "F")).close()
                }
            } finally { runCatching { request("DELETE", temporary).close() } }
        }
        override fun delete(path: String) { missing { request("DELETE", target(path)).close() } }
        override fun copy(from: String, to: String) {
            parents(to)
            request("COPY", target(from), headers = mapOf("Destination" to target(to).toString(), "Overwrite" to "T")).close()
        }
        override fun legacy() = listIn(directory).filter { it.name.startsWith("resound-") }.map { it.name }
    }

    fun testWrite() {
        ensureDirectory()
        val name = ".probe-${UUID.randomUUID()}.part"
        try { request("PUT", url(name), ByteArray(0).toRequestBody()).close() }
        finally { delete(name) }
    }

    companion object {
        const val FOLDER = "Resound-Backup"
        const val DIARY_FOLDER = "Resound-Diary"
        const val PREFIX = "resound"
    }
}
