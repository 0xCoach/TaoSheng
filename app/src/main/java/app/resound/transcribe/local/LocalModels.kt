package app.resound.transcribe.local

import android.content.Context
import app.resound.transcribe.awaitResponse
import android.os.Build
import androidx.work.*
import app.resound.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class LocalBackend(val title: String) { Npu("NPU 识别"), Cpu("CPU 识别") }
data class ModelSpec(val id: String, val url: String, val archiveBytes: Long, val archiveHash: String,
    val modelName: String, val modelBytes: Long, val modelHash: String, val needsTokens: Boolean = true,
    val fallbackUrls: List<String> = emptyList())
// 镜像保留上游原始压缩包；固定提交，跨源续传后仍校验完整 SHA-256。
private const val MODEL_MIRROR = "https://modelscope.cn/models/OpenRecode/TaoSheng-models/resolve/8c9fb74a04920e12bf1d43f49649a841469ad5ef"
private fun ModelSpec.withDomesticMirror() = copy(
    url = "$MODEL_MIRROR/${url.substringAfterLast('/')}", fallbackUrls = listOf(url))

object LocalModels {
    val cpu = ModelSpec("sense-cpu-v1", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",163002883,
        "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e", "model.int8.onnx",239233841,"c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51").withDomesticMirror()
    val npu = ModelSpec("sense-sm8650-v1", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-30-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",161991500,
        "8d050c08b01898b73144329be150cb4d4cc8802d72d6cae6add16e96fb7f0ef5", "model.bin",255995904,"f66bc5b70ce1d0c7c5abab3f45bbe59fdc266952ab6defc99d9864149ffd0098").withDomesticMirror()
    val speakers = ModelSpec("speakers-v2", "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", 6958444,
        "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488", "model.onnx", 5992913,
        "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", false).withDomesticMirror()
    fun speakersEnabled(context: Context) = ready(context, speakers) && context.getSharedPreferences("local-asr",0).getBoolean("speakers-enabled", true)
    /** retry=true：不管原来的下载卡在哪（排队、龟速、早已无响应），都换一个新任务从断点接着下 */
    fun enqueueSpeakers(context: Context, retry: Boolean = false) =
        enqueueDownload(context, SPEAKERS_WORK, workDataOf("speakers" to true), retry)
    private fun enqueueDownload(context: Context, name: String, data: Data, retry: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(name, if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWork>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    const val SPEAKERS_WORK = "speaker-model"
    fun workName(backend: LocalBackend) = "local-model-${backend.name}"
    /** 下载任务的真实状态（RUNNING/ENQUEUED 才算在下载）；界面不能只看上次写下的进度文字，进程被杀后那段文字会一直停着 */
    fun downloadActive(context: Context, name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name)
        .map { infos -> infos.firstOrNull { !it.state.isFinished }?.state }
    fun spec(backend: LocalBackend) = if (backend == LocalBackend.Npu) npu else cpu
    fun directory(context: Context, spec: ModelSpec) = File(context.noBackupFilesDir, "asr-models/${spec.id}")
    /** NPU 检查结果的有效范围：App 版本或 NPU 模型文件变了就要重查 */
    fun npuCheckKey(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return "$version:${File(directory(context, npu), npu.modelName).lastModified()}"
    }
    fun ready(context: Context, spec: ModelSpec): Boolean {
        val d = directory(context, spec)
        return ModelVerification.matches(d,spec) || ModelVerification.legacy(d,spec)
    }
    fun supportedSoc(soc: String) = soc.uppercase() == "SM8650"
    fun npuSupported(): Boolean = Build.VERSION.SDK_INT >= 31 && supportedSoc(Build.SOC_MODEL)
    fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input -> val b = ByteArray(128 * 1024); while (true) { val n=input.read(b);if(n<0)break;digest.update(b,0,n) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun enqueue(context: Context, backend: LocalBackend, retry: Boolean = false) {
        if (backend == LocalBackend.Npu && !npuSupported()) return
        enqueueDownload(context, workName(backend), workDataOf("backend" to backend.name), retry)
    }
}

class ModelDownloadWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val prefs = context.getSharedPreferences("local-asr", Context.MODE_PRIVATE)
    private lateinit var backend: LocalBackend
    private var speakers = false
    private fun status(text: String) { prefs.edit().putString("download-${if (speakers) "Speakers" else backend.name}",text).apply() }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        speakers = inputData.getBoolean("speakers", false)
        backend = if (speakers) LocalBackend.Cpu else runCatching { LocalBackend.valueOf(inputData.getString("backend") ?: "") }.getOrElse { return@withContext Result.failure() }
        if (backend == LocalBackend.Npu && !LocalModels.npuSupported()) { status("您的手机暂不支持 NPU 识别，请下载 CPU 模型");return@withContext Result.failure() }
        val spec=if(speakers) LocalModels.speakers else LocalModels.spec(backend);val dir=LocalModels.directory(applicationContext,spec)
        if(LocalModels.ready(applicationContext,spec)){status("已下载并校验");return@withContext Result.success()}
        val archive=File(applicationContext.noBackupFilesDir,"${spec.id}.part")
        val staging=File(dir.parentFile,"${spec.id}.staging")
        // “重试”会替换旧任务，旧任务可能还没完全停下；同一个文件只让一个任务写
        val lock = synchronized(locks) { locks.getOrPut(spec.id) { kotlinx.coroutines.sync.Mutex() } }
        lock.lock()
        try {
            setForeground(getForegroundInfo())
            if(applicationContext.noBackupFilesDir.usableSpace < (if(speakers) 30_000_000L else 650_000_000L)) {status("空间不足，请预留至少 650 MB");return@withContext Result.failure()}
            status("连接国内模型源…")
            try { downloadModelFile(spec,archive,::status) }
            catch(e: java.io.IOException) {
                // 断网、龟速：保留已下载的部分，自动再试几次；用尽后等用户点“重试”
                if(runAttemptCount < AUTO_RETRIES) {status("下载中断，稍后自动接着下（第 ${runAttemptCount+1}/$AUTO_RETRIES 次）· 也可点重试");return@withContext Result.retry()}
                throw e
            }
            staging.deleteRecursively();staging.mkdirs()
            status("解压与校验模型…")
            TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                while(true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val entry=tar.nextTarEntry ?: break
                    val name=entry.name.substringAfterLast('/')
                    if(!entry.isFile || name !in setOf(spec.modelName,"tokens.txt","LICENSE"))continue
                    val max=if(name==spec.modelName)spec.modelBytes else 1_000_000L
                    check(entry.size in 1..max)
                    File(staging,name).outputStream().use { tar.copyTo(it) }
                }
            }
            check(File(staging,spec.modelName).length()==spec.modelBytes && LocalModels.hash(File(staging,spec.modelName))==spec.modelHash)
            if(spec.needsTokens)check(File(staging,"tokens.txt").length() in 1..1_000_000)
            ModelVerification.record(staging,spec)
            dir.parentFile?.mkdirs();dir.deleteRecursively();check(staging.renameTo(dir))
            archive.delete();status("已下载并校验")
            Result.success()
        } catch(e: kotlinx.coroutines.CancellationException){status("下载已暂停，可点击继续");throw e}
        catch(_: Exception){status("下载未完成，请检查网络后重试；已校验失败的文件会重新下载");Result.failure()}
        finally{staging.deleteRecursively();lock.unlock()}
    }
    private companion object {
        const val AUTO_RETRIES = 3
        val locks = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("local-model","本地模型下载",NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3010+if(speakers)2 else if(::backend.isInitialized && backend==LocalBackend.Npu)1 else 0,
            NotificationCompat.Builder(applicationContext,"local-model").setSmallIcon(R.drawable.ic_notify).setContentTitle(if(speakers) "正在下载说话人分段模型" else "正在下载 SenseVoice 本地模型").setOngoing(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/** ASR 与搜索共用的有界、可取消下载；只有完整 SHA-256 通过后才返回。 */
suspend fun downloadModelFile(spec: ModelSpec, target: File, status: (String) -> Unit = {},
    stallWindowNanos: Long = STALL_WINDOW_NANOS, stallMinBytes: Long = STALL_MIN_BYTES) {
    coroutineContext.ensureActive()
    if (target.length() >= spec.archiveBytes) {
        if (target.length() == spec.archiveBytes && LocalModels.hash(target) == spec.archiveHash) return
        target.delete()
    }
    var failure: IOException? = null
    for ((index, url) in (listOf(spec.url) + spec.fallbackUrls).distinct().withIndex()) {
        coroutineContext.ensureActive()
        if (index > 0) status("正在切换备用下载源…")
        // 跨源续传可能沿用旧任务的损坏前缀；校验失败后允许当前源完整重下一次。
        var canRestart = target.length() > 0
        while (true) {
            try {
                downloadFromSource(spec, url, target, status, stallWindowNanos, stallMinBytes)
                status("校验下载文件…")
                coroutineContext.ensureActive()
                if (LocalModels.hash(target) != spec.archiveHash) {
                    target.delete()
                    if (canRestart) {
                        canRestart = false
                        status("文件校验未通过，正在重新下载…")
                        continue
                    }
                    throw IOException("Model checksum mismatch")
                }
                coroutineContext.ensureActive()
                return
            } catch (e: IOException) {
                coroutineContext.ensureActive() // 取消任务不能触发备用源下载。
                failure = e
                break
            }
        }
    }
    throw failure ?: IOException("No model download source")
}

private suspend fun downloadFromSource(spec: ModelSpec, url: String, target: File, status: (String) -> Unit,
    stallWindowNanos: Long, stallMinBytes: Long) {
    val offset = target.length()
    val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val call = client.newCall(request)
    try {
        call.awaitResponse().use { response ->
            if (response.code != 200 && response.code != 206) throw IOException("HTTP ${response.code}")
            val resume = response.code == 206
            if (resume) {
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                    .matchEntire(response.header("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val end = range?.groupValues?.get(2)?.toLongOrNull()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                if (start != offset || end == null || end < offset || end >= spec.archiveBytes || total != spec.archiveBytes)
                    throw IOException("Invalid model Content-Range")
            }
            var count = if (resume) offset else 0L
            var last = count
            var windowAt = System.nanoTime()
            var windowBytes = count
            val body = response.body ?: throw IOException("Empty model response")
            body.byteStream().use { input ->
                java.io.FileOutputStream(target, resume).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > spec.archiveBytes) throw IOException("Model exceeds expected size")
                        output.write(buffer, 0, n)
                        if (count - last >= 1024 * 1024) {
                            status("下载 ${count * 100 / spec.archiveBytes}% · ${count / 1_000_000}/${spec.archiveBytes / 1_000_000} MB")
                            last = count
                        }
                        if (System.nanoTime() - windowAt >= stallWindowNanos) {
                            if (count - windowBytes < stallMinBytes) throw IOException("download stalled")
                            windowAt = System.nanoTime()
                            windowBytes = count
                        }
                    }
                }
            }
            if (count != spec.archiveBytes) throw IOException("Incomplete model download")
        }
    } finally {
        call.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

private const val STALL_WINDOW_NANOS = 90_000_000_000L
private const val STALL_MIN_BYTES = 256 * 1024L
