package app.resound.recordings

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
    fun candidates(rows: List<RecordingSource>, s: PushSubscription, today: LocalDate): List<RecordingSource> {
        if (s.audio == AudioPush.Off) return emptyList()
        val from = since(s, today)
        return rows.filter { (from == null || it.day >= from) && it.day <= today &&
            !(s.excludeMedia && it.excluded) && (s.audio != AudioPush.Speech || it.speech) }
    }
    fun pending(row: RecordingSource, s: PushSubscription): Boolean {
        val r = records.optJSONObject(row.id.toString())
        return (r?.optBoolean("audio") != true && row.file.isFile) ||
            (r?.optBoolean("audio") == true && s.audioWithText && row.completed && r.optString("textHash") != sha256(row.text))
    }
    fun status(rows: List<RecordingSource>, s: PushSubscription, now: Long): AudioPushStatus {
        if (s.audio == AudioPush.Off) return AudioPushStatus(0, 0, 0, null, null, null)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val daily = json.optJSONObject("daily") ?: JSONObject()
        val monthBytes = daily.keys().asSequence().filter { it.startsWith(today.toString().take(7)) }.sumOf { daily.getLong(it) }
        val weekBytes = (0L..6L).sumOf { daily.optLong(today.minusDays(it).toString()) }
        return AudioPushStatus(records.keys().asSequence().count { records.getJSONObject(it).optBoolean("audio") },
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
        val base = "录音/${row.day}/${row.stem}"; var path = "$base.ogg"; var n = 2
        while (path in used) path = "$base（${n++}）.ogg"
        return JSONObject().put("path", path).also { records.put(row.id.toString(), it); save() }
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
    private suspend fun execute(request: Request, allowed: Set<Int> = emptySet()) {
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request)
        // 网络阻塞在 IO 上；取消协程时立即关闭请求。
        val handle = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try { call.execute().use { response ->
            if (!response.isSuccessful && response.code !in allowed) throw PushFailure(RecordingUploadErrors.status(response.code, response.peekBody(8192).string()))
        } } finally { handle.dispose() }
        currentCoroutineContext().ensureActive()
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
    override suspend fun audio(path: String, file: File) = put(path, file.asRequestBody("audio/ogg".toMediaType()))
    override suspend fun text(path: String, content: String) = put(path, content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
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
                if (!record.optBoolean("audio")) {
                    if (!row.file.isFile) continue
                    val bytes = row.file.length()
                    remote.audio(path, row.file)
                    record.put("audio", true); state.account(bytes, now); state.save(); uploaded++
                }
                if (s.audioWithText && row.completed && record.optString("textHash") != sha256(row.text)) {
                    if (!allowed()) break
                    remote.text(path.removeSuffix(".ogg") + ".txt", row.text)
                    record.put("textHash", sha256(row.text)); state.account(row.text.toByteArray().size.toLong(), now); state.save()
                }
            }
            state.json.remove("error"); state.save()
            return uploaded
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state.json.put("error", if (e is PushFailure) e.message else "录音上传失败，请检查网络后重试"); state.save(); throw e
        }
    }
}
