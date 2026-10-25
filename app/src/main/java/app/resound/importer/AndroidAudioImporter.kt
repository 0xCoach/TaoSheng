package app.resound.importer

import app.resound.appDisplayName
import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ImportEntry(val pending: PendingImport, val startAt: Long? = null, val cancelled: Boolean = false)

/** 文件先落盘，再提交确认信息；队列与确认列表同一次 commit，避免进程死亡丢掉授权内容。 */
class AndroidAudioImporter internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val inspect: (File) -> AudioMetadata = ImportMedia(context)::inspect,
    private val describe: (Uri) -> SharedAudio = ImportMedia(context)::shared,
    private val enqueue: () -> Unit = { enqueue(context) },
) : AudioImporter {
    private val root = File(context.cacheDir, "import").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("imports", Context.MODE_PRIVATE)
    private val entries = runCatching {
        val json = JSONArray(prefs.getString("entries", "[]"))
        (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            ImportEntry(PendingImport(item.getString("id"), ImportInfo(item.getString("name"), item.getLong("duration"),
                if (item.isNull("suggested")) null else item.getLong("suggested"))),
                if (item.isNull("start")) null else item.getLong("start"), item.optBoolean("cancelled"))
        }.filter { runCatching { UUID.fromString(it.pending.id) }.isSuccess }.toMutableList()
    }.getOrDefault(mutableListOf())
    private val _pending = MutableStateFlow(entries.filter { it.startAt == null }.map { it.pending })
    override val pending = _pending.asStateFlow()
    private val _progress = MutableStateFlow(ImportProgress(false, null, 0, entries.count { it.startAt != null }, 0, null))
    override val progress = _progress.asStateFlow()
    private var active: String? = null
    private val commands = kotlinx.coroutines.channels.Channel<() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            for (command in commands) try { command() }
            catch (_: Exception) { reportError("导入队列保存失败，请重新打开${appDisplayName}重试") }
        }
        File(context.cacheDir, "import-pcm").deleteRecursively()
        // 仅清理未被登记的半份复制；已排队缓存若被系统回收，由 worker 给出明确错误。
        root.listFiles()?.filter { file -> entries.none { it.pending.id == file.name } }?.forEach { it.deleteRecursively() }
        if (entries.any { it.startAt != null }) enqueue()
    }
    internal fun file(id: String) = File(root, id)
    @Synchronized private fun save() {
        val array = JSONArray(entries.map { row -> JSONObject().apply {
            put("id", row.pending.id); put("name", row.pending.info.displayName); put("duration", row.pending.info.durationMillis)
            put("suggested", row.pending.info.suggestedStartAt ?: JSONObject.NULL)
            put("start", row.startAt ?: JSONObject.NULL); put("cancelled", row.cancelled)
        } })
        check(prefs.edit().putString("entries", array.toString()).commit()) { "无法保存导入队列" }
        _pending.value = entries.filter { it.startAt == null }.map { it.pending }
        _progress.value = _progress.value.copy(queued = entries.count { it.startAt != null && it.pending.id != active })
    }
    override suspend fun receive(uris: List<Uri>): List<Result<PendingImport>> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val id = UUID.randomUUID().toString()
            val target = file(id)
            try {
                if (uri.scheme != "content") throw ImportFailure("请从录音机或文件应用分享音频")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } } ?: throw ImportFailure("无法读取录音，请重新分享")
                val source = describe(uri)
                val audio = inspect(target)
                val item = PendingImport(id, ImportInfo(source.name, audio.duration, suggestedStart(audio.date, source.modified, audio.duration)))
                synchronized(this@AndroidAudioImporter) {
                    entries += ImportEntry(item)
                    try { save() } catch (e: Exception) { entries.removeAll { it.pending.id == id }; throw e }
                }
                Result.success(item)
            } catch (e: Exception) {
                target.delete()
                if (e is CancellationException) throw e
                Result.failure(ImportFailure(when (e) {
                    is ImportFailure -> e.message!!
                    is SecurityException -> "读取权限已失效，请重新分享"
                    else -> "无法读取或保存录音，请检查可用空间后重新分享"
                }))
            }
        }
    }
    override fun start(id: String, startAt: Long) {
        commands.trySend command@{
            try {
                synchronized(this@AndroidAudioImporter) {
                    val index = entries.indexOfFirst { it.pending.id == id && it.startAt == null }
                    if (index < 0) return@command
                    val entry = entries.removeAt(index)
                    entries.add(entry.copy(startAt = startAt))
                    save()
                }
                enqueue()
            } catch (_: Exception) { reportError("导入排队失败，请重新打开${appDisplayName}重试") }
        }
    }
    override fun discard(id: String) {
        commands.trySend command@{
            synchronized(this@AndroidAudioImporter) {
                if (entries.none { it.pending.id == id && it.startAt == null }) return@command
                check(file(id).delete() || !file(id).exists()) { "临时录音删除失败" }; entries.removeAll { it.pending.id == id }; save()
            }
        }
    }
    @Synchronized internal fun next(): ImportEntry? = entries.firstOrNull { it.startAt != null }
    @Synchronized internal fun cancel(id: String) {
        val index = entries.indexOfFirst { it.pending.id == id }
        if (index >= 0) { entries[index] = entries[index].copy(cancelled = true); save() }
    }
    @Synchronized internal fun isCancelled(id: String) = entries.firstOrNull { it.pending.id == id }?.cancelled != false
    @Synchronized internal fun update(id: String, percent: Int, segments: Int) {
        active = id
        _progress.value = ImportProgress(true, entries.firstOrNull { it.pending.id == id }?.pending?.info?.displayName,
            percent, entries.count { it.startAt != null && it.pending.id != id }, segments, null)
    }
    @Synchronized internal fun finish(id: String, error: String?) {
        check(file(id).delete() || !file(id).exists()) { "临时录音删除失败" }
        entries.removeAll { it.pending.id == id }; active = null
        _progress.value = _progress.value.copy(running = false, percent = if (error == null) 100 else _progress.value.percent, error = error)
        save()
    }
    @Synchronized internal fun interrupted() {
        active = null; _progress.value = _progress.value.copy(running = false, error = "导入已中断，系统将恢复任务")
    }
    @Synchronized private fun reportError(error: String) { _progress.value = _progress.value.copy(error = error) }
    companion object {
        internal fun enqueue(context: Context) {
            // 每次确认追加一个唤醒任务，避免 drain 即将结束时 KEEP 吞掉新文件；实际文件顺序以持久化列表为准。
            WorkManager.getInstance(context).enqueueUniqueWork("audio-import", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ImportWork>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build())
        }
    }
}
