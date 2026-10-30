package app.resound.recordings

import android.content.*
import android.net.Uri
import android.provider.MediaStore
import androidx.work.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class AndroidRecordingPacker(
    private val context: Context,
    private val load: suspend () -> List<RecordingSource>,
    scope: CoroutineScope,
    private val enqueue: (String) -> Unit = { id -> WorkManager.getInstance(context).enqueueUniqueWork("recording-pack-$id", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<RecordingPackWorker>().setInputData(workDataOf("id" to id)).build()); Unit },
    private val stop: (String) -> Unit = { WorkManager.getInstance(context).cancelUniqueWork("recording-pack-$it"); Unit },
    private val metadata: suspend (Boolean) -> List<RecordingSource> = { load() },
) : RecordingPacker {
    private val prefs = context.getSharedPreferences("recording-pack", Context.MODE_PRIVATE)
    private var entry = prefs.getString("job", null)?.let { JSONObject(it) }
    private val mutableCurrent = MutableStateFlow<Pair<String, RecordingPackState>?>(null)
    override val current = mutableCurrent.asStateFlow()
    private val days = MutableStateFlow<List<LocalDate>>(emptyList())
    override val availableDays = days.asStateFlow()
    private val mutex = Mutex()
    init {
        publish()
        entry?.takeIf { it.optString("state") == "running" }?.let { enqueue(it.getString("id")) }
        scope.launch(Dispatchers.IO) { while (isActive) { refreshDays(); delay(30_000) } }
    }
    private suspend fun refreshDays() { days.value = metadata(false).filter { it.file.isFile }.map { it.day }.distinct().sortedDescending() }
    override suspend fun estimate(range: RecordingRange, options: RecordingPackOptions) = withContext(Dispatchers.IO) {
        RecordingZip.estimate(RecordingZip.select(metadata(options.skipMedia), range, options))
    }
    @Synchronized override fun start(range: RecordingRange, options: RecordingPackOptions): String {
        require(range.startMillis() <= range.endMillis()) { "开始时间不能晚于结束时间" }
        entry?.takeIf { it.optString("state") == "running" }?.let { return it.getString("id") }
        val id = UUID.randomUUID().toString()
        entry = JSONObject().put("id", id).put("from", range.from).put("to", range.to)
            .put("fromTime", range.fromTime.toString()).put("toTime", range.toTime.toString()).put("text", options.withText)
            .put("skip", options.skipMedia).put("state", "running")
        publish()
        try { enqueue(id) } catch (_: Exception) { fail(id, "无法安排后台打包，请重试") }
        return id
    }
    @Synchronized override fun cancel(id: String) {
        if (!running(id)) return
        entry!!.put("state", "cancelled"); cleanup(); publish(); stop(id)
        PackNotifications.clear(context, id)
    }
    @Synchronized override fun shareUri(id: String): Uri? {
        val j = entry?.takeIf { it.optString("id") == id && it.optString("state") == "done" } ?: return null
        val uri = Uri.parse(j.getString("uri"))
        return runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { uri } }.getOrNull()
    }
    @Synchronized private fun running(id: String) = entry?.let { it.optString("id") == id && it.optString("state") == "running" } == true
    @Synchronized internal fun fail(id: String, message: String) {
        if (running(id)) { cleanup(); entry!!.put("state", "failed").put("message", message); publish() }
    }
    private fun cleanup() {
        entry?.optString("uri")?.takeIf { it.isNotBlank() }?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
        entry?.remove("uri")
    }
    @Synchronized private fun publish() {
        val j = entry ?: return
        check(prefs.edit().putString("job", j.toString()).commit())
        val state = when (j.getString("state")) {
            "done" -> RecordingPackState.Done(j.getString("name"), j.getLong("bytes"), j.getInt("total"))
            "failed" -> RecordingPackState.Failed(j.getString("message"))
            "cancelled" -> RecordingPackState.Cancelled
            else -> RecordingPackState.Running(j.optDouble("fraction", 0.0).toFloat(), j.optInt("done"), j.optInt("total"))
        }
        mutableCurrent.value = j.getString("id") to state
    }
    internal suspend fun run(id: String, progress: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val request = synchronized(this@AndroidRecordingPacker) { entry?.takeIf { running(id) }?.let { JSONObject(it.toString()) } }
            ?: return@withLock false
        var uri: Uri? = null; var done = false
        try {
            val range = RecordingRange(LocalDate.parse(request.getString("from")), LocalDate.parse(request.getString("to")),
                request.optString("fromTime").ifBlank { null }?.let(java.time.LocalTime::parse) ?: java.time.LocalTime.MIN,
                request.optString("toTime").ifBlank { null }?.let(java.time.LocalTime::parse) ?: java.time.LocalTime.MAX)
            val options = RecordingPackOptions(request.getBoolean("text"), request.getBoolean("skip"))
            val rows = RecordingZip.select(load(), range, options)
            check(rows.isNotEmpty()) { "所选时间没有可导出的原声" }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relative = "Download/Resound/录音包/"
            var name = RecordingZip.name(range); val base = name.removeSuffix(".zip"); var suffix = 2
            synchronized(this@AndroidRecordingPacker) {
                if (!running(id)) throw CancellationException()
                cleanup() // 系统中断后重跑，清理持久化的半成品。
                while (resolver.query(collection, arrayOf("_id"), "relative_path = ? AND _display_name = ?", arrayOf(relative, name), null)?.use { it.count > 0 } == true)
                    name = "$base（${suffix++}）.zip"
                uri = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("无法创建下载文件")
                entry!!.put("uri", uri.toString()).put("name", name).put("total", rows.size); publish()
            }
            resolver.openOutputStream(uri!!, "w")!!.use { output -> RecordingZip.write(output, rows, options.withText) { count ->
                synchronized(this@AndroidRecordingPacker) {
                    if (!running(id)) throw CancellationException()
                    entry!!.put("done", count).put("fraction", count.toDouble() / rows.size); publish()
                }
                progress()
            } }
            currentCoroutineContext().ensureActive()
            synchronized(this@AndroidRecordingPacker) {
                if (!running(id)) throw CancellationException()
                val bytes = resolver.openFileDescriptor(uri!!, "r")!!.use { it.statSize }
                check(resolver.update(uri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
                entry!!.put("bytes", bytes).put("state", "done"); publish(); done = true
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(id, "录音打包失败，请检查原声文件和剩余空间后重试"); false }
        finally { if (!done && uri != null) context.contentResolver.delete(uri!!, null, null) }
    } }
}
