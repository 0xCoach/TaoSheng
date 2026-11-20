package app.resound.recordings

import app.resound.backup.BackupFailure

import app.resound.push.*
import java.io.File
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 独立于文字同步；不保存账号、密码、原话或响应体。确认成功的 OGG 永不探测/删除/重传。 */
internal class RecordingUploadState(private val file: File) {
    val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    val records = json.optJSONObject("records") ?: JSONObject().also { json.put("records", it) }
    fun save() {
        file.parentFile!!.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { "无法保存录音上传状态" }
    }
    fun since(s: PushSubscription, today: LocalDate): LocalDate? {
        if (!json.has("since")) {
            json.put("since", s.backfillDays?.let { today.minusDays((it - 1L).coerceAtLeast(0)).toString() } ?: JSONObject.NULL); save()
        }
        val first = if (json.isNull("since")) null else LocalDate.parse(json.getString("since"))
        val rolling = s.rangeDays?.let { today.minusDays(it - 1L) }
        return listOfNotNull(first, rolling).maxOrNull()
    }
    val tombstones = json.optJSONObject("tombstones") ?: JSONObject().also { json.put("tombstones", it) }
    val dailyHash = json.optJSONObject("dailyHash") ?: JSONObject().also { json.put("dailyHash", it) }
    val dirtyDays = json.optJSONObject("dirtyDays") ?: JSONObject().also { json.put("dirtyDays", it) }
    fun uploaded(id: Long): Boolean = records.optJSONObject(id.toString())?.let {
        RecordingKeepRules.verified(it.optBoolean("audio"), if (it.has("verified")) it.optInt("verified") else null)
    } == true
    fun successful() = records.keys().asSequence().mapNotNull { it.toLongOrNull() }.filter(::uploaded).toSet()
    fun candidates(rows: List<RecordingSource>, s: PushSubscription, today: LocalDate): List<RecordingSource> {
        if (s.audio == AudioPush.Off) return emptyList()
        val from = since(s, today)
        return rows.filter { !tombstones.has(it.id.toString()) &&
            RecordingKeepRules.eligible(it.keep(), s, from?.toString(), today.toString()) }
    }
    fun pending(row: RecordingSource, s: PushSubscription): Boolean {
        val r = records.optJSONObject(row.id.toString())
        return (!uploaded(row.id) && row.file.isFile) ||
            (uploaded(row.id) && RecordingKeepRules.perSegment(s) && row.completed && r.optString("textHash") != sha256(row.text))
    }
    fun status(rows: List<RecordingSource>, s: PushSubscription, now: Long): AudioPushStatus {
        if (s.audio == AudioPush.Off) return AudioPushStatus(0, 0, 0, null, null, null)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val daily = json.optJSONObject("daily") ?: JSONObject()
        val monthBytes = daily.keys().asSequence().filter { it.startsWith(today.toString().take(7)) }.sumOf { daily.getLong(it) }
        val weekBytes = (0L..6L).sumOf { daily.optLong(today.minusDays(it).toString()) }
        return AudioPushStatus(successful().size,
            candidates(rows, s, today).count { pending(it, s) }, monthBytes, (weekBytes * 30.0 / 7).toLong(),
            json.optLong("lastAt").takeIf { it > 0 }, json.optString("error").takeIf { it.isNotBlank() })
    }
    fun account(bytes: Long, now: Long) {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val daily = json.optJSONObject("daily") ?: JSONObject().also { json.put("daily", it) }
        daily.put(day, daily.optLong(day) + bytes); json.put("lastAt", now); json.remove("error")
    }
    fun path(row: RecordingSource): JSONObject {
        records.optJSONObject(row.id.toString())?.let { return it }
        val used = records.keys().asSequence().map { records.getJSONObject(it).getString("path") }.toSet()
        val base = "录音/${row.day}/${row.stem}"; val ext = row.file.extension.lowercase(); var path = "$base.$ext"; var n = 2
        while (path in used) path = "$base ${n++}.$ext"
        return JSONObject().put("path", path).put("day", row.day.toString()).also { records.put(row.id.toString(), it); save() }
    }
}

internal object RecordingUploadErrors {
    fun status(code: Int, body: String = ""): String = when {
        code == 401 -> "坚果云账号或应用密码不对"
        code == 507 || body.contains("流量") || body.contains("quota", true) || body.contains("traffic", true) -> "坚果云本月上传流量用完了"
        code == 403 -> "坚果云拒绝访问，请检查文件夹权限"
        code == 429 || code == 503 -> "坚果云暂时限流，稍后自动重试"
        code == 413 -> "录音文件太大，超过坚果云单文件限制"
        code in 300..399 -> "坚果云地址要求跳转，请检查通道地址"
        else -> "录音上传失败（HTTP $code），稍后自动重试"
    }
}
internal interface RecordingRemote {
    suspend fun audio(path: String, file: File)
    suspend fun text(path: String, content: String)
    suspend fun delete(path: String) { error("删除未实现") }
}
internal class RecordingDav(values: Map<String, String>, client: OkHttpClient = OkHttpClient()) : RecordingRemote {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.MINUTES).build()
    private val root = (values["url"].orEmpty().ifBlank { "https://dav.jianguoyun.com/dav/" }).toHttpUrl()
    private val folder = values.getValue("folder").split('/').filter { it.isNotEmpty() }
    private val auth = Credentials.basic(values.getValue("account"), values.getValue("password"), Charsets.UTF_8)
    private val made = mutableSetOf<String>()
    private fun url(parts: List<String>) = root.newBuilder().apply {
        if (!root.encodedPath.endsWith('/')) addPathSegment("")
        parts.forEach { addPathSegment(it) }
    }.build()
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun execute(request: Request, allowed: Set<Int> = emptySet()): String {
        var body = ""
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request)
        // 网络阻塞在 IO 上；取消协程时立即关闭请求。
        val handle = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try { call.execute().use { response ->
            if (!response.isSuccessful && response.code !in allowed) throw PushFailure(RecordingUploadErrors.status(response.code, response.peekBody(8192).string()))
            if (request.method in setOf("MOVE", "DELETE") && response.code !in setOf(200, 201, 204, 404))
                throw PushFailure("远端文件操作尚未确认完成，稍后自动重试")
            if (request.method == "PROPFIND") body = response.peekBody(65536).string()
        } } finally { handle.dispose() }
        currentCoroutineContext().ensureActive()
        return body
    }
    private suspend fun put(path: String, body: RequestBody) {
        val parts = folder + path.split('/')
        for (i in 1 until parts.size) {
            val parent = parts.take(i); val key = parent.joinToString("/")
            if (key !in made) { execute(Request.Builder().url(url(parent).newBuilder().addPathSegment("").build()).header("Authorization", auth)
                .method("MKCOL", ByteArray(0).toRequestBody()).build(), setOf(405)); made += key }
        }
        execute(Request.Builder().url(url(parts)).header("Authorization", auth).put(body).build())
    }
    override suspend fun audio(path: String, file: File) {
        val part = path.substringBeforeLast('/') + "/." + path.substringAfterLast('/') + ".part"
        put(part, file.asRequestBody((if (file.extension == "m4a") "audio/mp4" else "audio/ogg").toMediaType()))
        val xml = execute(Request.Builder().url(url(folder + part.split('/'))).header("Authorization", auth).header("Depth", "0")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType())).build())
        if (davLength(xml) != file.length()) throw PushFailure("录音大小核对失败，稍后自动重试")
        execute(Request.Builder().url(url(folder + part.split('/'))).header("Authorization", auth)
            .header("Destination", url(folder + path.split('/')).toString()).header("Overwrite", "T").method("MOVE", null).build())
    }
    override suspend fun delete(path: String) {
        execute(Request.Builder().url(url(folder + path.split('/'))).header("Authorization", auth).delete().build(), setOf(404))
    }
    override suspend fun text(path: String, content: String) = put(path, content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
}
internal fun davLength(xml: String): Long? {
    val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(xml.reader())
    var length: Long? = null; var status: String? = null
    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.namespace == "DAV:") when (parser.name) {
            "propstat" -> { length = null; status = null }
            "getcontentlength" -> length = parser.nextText().trim().toLongOrNull()
            "status" -> status = parser.nextText()
        }
        if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "propstat" &&
            status?.split(' ')?.getOrNull(1) == "200" && length != null && length >= 0) return length
        parser.next()
    }
    return null
}

internal class RecordingUploader(private val state: RecordingUploadState, private val remote: RecordingRemote) {
    suspend fun run(rows: List<RecordingSource>, s: PushSubscription, now: Long, allowed: () -> Boolean = { true }): Int {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        var uploaded = 0
        try {
            for (row in state.candidates(rows, s, today).filter { state.pending(it, s) }.take(20)) {
                currentCoroutineContext().ensureActive()
                if (!allowed()) break
                val record = state.path(row); val path = record.getString("path")
                if (!state.uploaded(row.id)) {
                    if (!row.file.isFile) continue
                    val bytes = row.file.length()
                    record.put("temporary", RecordingPrivacyRules.partPath(path)); state.save()
                    remote.audio(path, row.file)
                    record.remove("temporary")
                    record.put("audio", true).put("verified", 1); state.account(bytes, now); state.save(); uploaded++
                }
                if (RecordingKeepRules.perSegment(s) && row.completed && record.optString("textHash") != sha256(row.text)) {
                    if (!allowed()) break
                    val textPath = RecordingPrivacyRules.textPath(path)
                    record.put("temporary", RecordingPrivacyRules.partPath(textPath)); state.save()
                    remote.text(textPath, row.text)
                    record.remove("temporary")
                    record.put("textHash", sha256(row.text)); state.account(row.text.toByteArray().size.toLong(), now); state.save()
                }
            }
            if (RecordingKeepRules.daily(s)) {
                val documents = RecordingDailyRules.documents(rows.filterNot { state.tombstones.has(it.id.toString()) }.map {
                    DailyRecording(it.keep(), state.records.optJSONObject(it.id.toString())?.optString("path")?.substringAfterLast('/') ?: "${it.stem}.${it.file.extension}", it.completed, it.dailyLines)
                }, s, today.toString(), state.candidates(rows, s, today).mapTo(mutableSetOf()) { it.id }, state.successful(),
                    state.records.keys().asSequence().mapNotNull { it.toLongOrNull() }.toSet(),
                    state.dailyHash.keys().asSequence().toSet(), state.dirtyDays.keys().asSequence().toSet())
                for ((day, content) in documents) {
                    currentCoroutineContext().ensureActive()
                    if (!allowed()) break
                    val hash = sha256(content)
                    if (state.dailyHash.optString(day) != hash || state.dirtyDays.has(day)) {
                        // 写前登记：服务器收到了但进程未记成功，也必须能在隐私删除时找到这一天。
                        state.dirtyDays.put(day, true); state.save()
                        remote.text(RecordingKeepRules.dailyPath(day), content)
                        state.dailyHash.put(day, hash); state.dirtyDays.remove(day)
                        state.account(content.toByteArray().size.toLong(), now); state.save()
                    }
                }
            }
            state.json.remove("error"); state.save()
            return uploaded
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state.json.put("error", when (e) { is PushFailure -> e.message; is BackupFailure -> e.human; else -> "录音上传失败，请检查网络后重试" }); state.save(); throw e
        }
    }
}
