package app.resound.remote

import app.resound.transcribe.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun speechHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun speechFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun speechId() = UUID.randomUUID().toString().replace("-", "")
internal fun validSpeechId(value: String) = value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

data class SpeechConnection(val base: String, val model: String, val key: String) {
    val scope: String get() = speechHash("$base\n${speechHash(key)}")
    override fun toString() = "SpeechConnection(base=$base, model=$model, scope=$scope)"
}

/** 服务任务是现有安卓任务的远端句柄，Room 中不保存凭据或转写正文副本。 */
class SpeechServiceClient(
    private val dao: RemoteSpeechDao,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).followRedirects(false).build(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 1500,
    private val waitMillis: Long = 90_000,
    private val canRequest: () -> Boolean = { true },
) {
    private val tasks = Mutex()
    private val supported = mutableMapOf<String, Pair<Long, String?>>()
    suspend fun discover(endpoint: String, model: String, key: String): SpeechConnection? {
        val cacheKey = speechHash("$endpoint\n$model\n${speechHash(key)}")
        synchronized(supported) { supported[cacheKey] }?.takeIf { now() - it.first < 300_000 }?.let {
            return it.second?.let { base -> SpeechConnection(base, model, key) }
        }
        for (base in OpenAiEndpoint.bases(endpoint)) {
            val reply = try { request(SpeechConnection(base, model, key), "GET", "/models", allowMissing = true) }
            catch (e: AsrException) { if (e.type in setOf(AsrError.Protocol, AsrError.Auth)) null else throw e } ?: continue
            val models = reply.optJSONArray("data") ?: continue
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                val capabilities = item.optJSONArray("capabilities") ?: continue
                val names = (0 until capabilities.length()).map { capabilities.optString(it) }.toSet()
                if (item.optString("id") == model && names.containsAll(setOf("async_jobs", "diarization", "identification"))) {
                    synchronized(supported) { supported[cacheKey] = now() to base }
                    return SpeechConnection(base, model, key)
                }
            }
        }
        synchronized(supported) { supported[cacheKey] = now() to null }
        return null
    }

    internal suspend fun request(c: SpeechConnection, method: String, path: String, body: RequestBody? = null,
                                 idempotency: String? = null, allowMissing: Boolean = false): JSONObject? {
        if (!canRequest()) throw AsrException(AsrError.Network)
        try {
            val builder = Request.Builder().url(c.base + path).header("Authorization", "Bearer ${c.key}")
                .method(method, body)
            idempotency?.let { builder.header("Idempotency-Key", it) }
            return http.newCall(builder.build()).awaitResponse().use { response ->
                if (allowMissing && response.code in setOf(404, 405)) return@use null
                if (response.code == 401) throw AsrException(AsrError.Auth)
                if (response.code == 403) throw AsrException(AsrError.Auth, detail = "当前密钥不能管理人物，请在自定义转写中填写该应用的管理密钥")
                if (!response.isSuccessful) throw AsrException(if (response.code >= 500 || response.code == 429) AsrError.Server else AsrError.Protocol)
                val bytes = response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (output.size() <= 8 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                    output.toByteArray()
                } ?: throw AsrException(AsrError.Protocol)
                if (bytes.size > 8 * 1024 * 1024) throw AsrException(AsrError.Protocol)
                if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: AsrException) { throw e }
        catch (_: java.io.IOException) { throw AsrException(AsrError.Network) }
        catch (_: Exception) { throw AsrException(AsrError.Protocol) }
    }

    /** 给正常慢任务90秒完成窗口，避免30秒时交还分钟级退避队列；不重复上传。 */
    suspend fun operation(c: SpeechConnection, taskKey: String, method: String, path: String,
                          body: () -> RequestBody?, segmentId: Long? = null,
                          beforeUpload: suspend () -> Unit = {}, valid: suspend () -> Boolean = { true }): JSONObject {
        val queued = System.nanoTime()
        return tasks.withLock {
            currentCoroutineContext()[SegmentTimings]?.add(AsrPart.RemoteQueue, (System.nanoTime() - queued) / 1_000_000)
            var row = dao.task(taskKey)
            if (row != null && row.scope != c.scope) throw AsrException(AsrError.Protocol)
            if (row == null) {
                row = RemoteSpeechTask(taskKey, c.scope, speechId(), null, segmentId, now())
                dao.task(row)
            }
            if (!valid()) { cancel(c, row); throw AsrException(AsrError.Interrupted) }
            var response = row.jobId?.let { measureAsr(AsrPart.RemotePoll) { request(c, "GET", "/transcription-jobs/$it", allowMissing = true) } }
            if (row.jobId != null && response == null) {
                row = row.copy(requestId = speechId(), jobId = null, createdAt = now()); dao.task(row)
            }
            if (row.jobId == null) {
                beforeUpload()
                val requestId = row.requestId
                response = measureAsr(AsrPart.RemoteSubmit) { request(c, method, path, body(), requestId) } ?: throw AsrException(AsrError.Protocol)
                if (!response.has("state")) return@withLock response
                val id = response.optString("id")
                if (!validSpeechId(id)) throw AsrException(AsrError.Protocol)
                row = row.copy(jobId = id); dao.task(row)
            }
            val began = System.nanoTime()
            val until = began + waitMillis * 1_000_000
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!valid()) { cancel(c, row); throw AsrException(AsrError.Interrupted) }
                val value = response ?: throw AsrException(AsrError.Protocol)
                when (value.optString("state")) {
                    "succeeded" -> return@withLock value.optJSONObject("result") ?: throw AsrException(AsrError.Protocol)
                    "failed" -> { dao.removeTask(taskKey); throw AsrException(if (value.optString("error") in setOf("audio_too_long", "invalid_audio", "audio_too_short")) AsrError.Audio else AsrError.Protocol, detail = remoteFailure(value.optString("error"))) }
                    "cancelled", "expired" -> { dao.removeTask(taskKey); throw AsrException(AsrError.RemotePending) }
                    "queued", "running" -> Unit
                    else -> throw AsrException(AsrError.Protocol)
                }
                val tick = System.nanoTime()
                if (tick >= until) throw AsrException(AsrError.RemotePending)
                val pause = pollMillis.coerceAtLeast(1)
                    .coerceAtMost(((until - tick) / 1_000_000).coerceAtLeast(1))
                measureAsr(AsrPart.RemoteDelay) { delay(pause) }
                // 等待期间录音可能被删除或服务配置被切换。
                if (!valid()) { cancel(c, row); throw AsrException(AsrError.Interrupted) }
                if (System.nanoTime() >= until) throw AsrException(AsrError.RemotePending)
                response = measureAsr(AsrPart.RemotePoll) { request(c, "GET", "/transcription-jobs/${row.jobId}", allowMissing = true) }
                if (response == null) { dao.removeTask(taskKey); throw AsrException(AsrError.RemotePending) }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    suspend fun cancel(c: SpeechConnection, row: RemoteSpeechTask) {
        if (row.scope != c.scope) return
        row.jobId?.let { request(c, "DELETE", "/transcription-jobs/$it", allowMissing = true) }
        dao.removeTask(row.key)
    }
    suspend fun forget(key: String) = dao.removeTask(key)

    companion object {
        internal fun remoteFailure(code: String): String = when (code) {
            "sample_requires_clear_single_speaker", "sample_audio_quality_low", "sample_too_short" -> "声纹样本需要至少5秒清晰单人人声，请重新录制"
            "person_not_found" -> "服务端人物已不存在，请重新录入该人物"
            "audio_too_long" -> "自建语音服务每段录音最多10分钟"
            "invalid_audio", "audio_too_short" -> "服务端无法读取这段录音"
            else -> "远端任务失败，请重试或检查语音服务"
        }
        internal fun audioBody(c: SpeechConnection, audio: AsrAudio): RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", c.model).addFormDataPart("language", "zh")
            .addFormDataPart("diarize", audio.separateSpeakers.toString()).addFormDataPart("identify", audio.separateSpeakers.toString())
            .addFormDataPart("file", audio.file.name, audio.file.asRequestBody("application/octet-stream".toMediaType())).build()
        internal fun jsonBody(json: JSONObject) = json.toString().toRequestBody("application/json".toMediaType())
    }
}

/** 已确认身份才映射人物，未知分数不交给旧版UI的“可能是我”阈值。 */
internal suspend fun parseSpeechResult(json: JSONObject, duration: Long, scope: String,
                                     person: suspend (String, String) -> Long?): AsrResult {
    val array = json.optJSONArray("segments") ?: throw AsrException(AsrError.Protocol)
    val lines = mutableListOf<AsrSentence>()
    for (i in 0 until array.length()) {
        val row = array.getJSONObject(i)
        val a = row.getDouble("start"); val b = row.getDouble("end")
        if (!a.isFinite() || !b.isFinite() || a < 0 || a * 1000 >= duration || b <= a || b * 1000 > duration + 500) throw AsrException(AsrError.Protocol)
        val text = row.getString("text").trim(); if (text.isBlank()) continue
        val remote = row.optString("person_id").takeIf { validSpeechId(it) && row.optString("identity_status") == "matched" }
        val name = row.optString("person_name").takeIf { !row.isNull("person_name") && it.isNotBlank() && it.length <= 80 }
        val local = if (remote != null && name != null) person(remote, name) else null
        val score = row.optDouble("similarity", Double.NaN).takeIf { local != null && it.isFinite() && it in -1.0..1.0 }?.toFloat()
        val start = (a * 1000).toLong(); val end = (b * 1000).toLong().coerceAtMost(duration)
        if (end <= start) throw AsrException(AsrError.Protocol)
        lines += AsrSentence(start, end, text,
            row.optString("speaker_id").takeIf { !row.isNull("speaker_id") && it.isNotBlank() && it.length <= 128 },
            personId = local, personName = name.takeIf { local != null }, similarity = score)
    }
    if (lines.isEmpty() && json.optString("text").isNotBlank()) throw AsrException(AsrError.Protocol)
    return AsrResult(lines, duration, null, scope)
}
