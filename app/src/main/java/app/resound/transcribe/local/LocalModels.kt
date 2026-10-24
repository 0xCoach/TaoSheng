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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class LocalBackend(val title: String) { Npu("NPU 加速"), Cpu("CPU 兼容") }
data class ModelSpec(val id: String, val url: String, val archiveBytes: Long, val archiveHash: String,
    val modelName: String, val modelBytes: Long, val modelHash: String, val needsTokens: Boolean = true)
object LocalModels {
    val cpu = ModelSpec("sense-cpu-v1", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",163002883,
        "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e", "model.int8.onnx",239233841,"c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51")
    val npu = ModelSpec("sense-sm8650-v1", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-30-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",161991500,
        "8d050c08b01898b73144329be150cb4d4cc8802d72d6cae6add16e96fb7f0ef5", "model.bin",255995904,"f66bc5b70ce1d0c7c5abab3f45bbe59fdc266952ab6defc99d9864149ffd0098")
    val speakers = ModelSpec("speakers-v2", "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", 6958444,
        "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488", "model.onnx", 5992913,
        "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", false)
    fun speakersEnabled(context: Context) = ready(context, speakers) && context.getSharedPreferences("local-asr",0).getBoolean("speakers-enabled", true)
    fun enqueueSpeakers(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("speaker-model", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWork>().setInputData(workDataOf("speakers" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun spec(backend: LocalBackend) = if (backend == LocalBackend.Npu) npu else cpu
    fun directory(context: Context, spec: ModelSpec) = File(context.noBackupFilesDir, "asr-models/${spec.id}")
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
    fun enqueue(context: Context, backend: LocalBackend) {
        if (backend == LocalBackend.Npu && !npuSupported()) return
        WorkManager.getInstance(context).enqueueUniqueWork("local-model-${backend.name}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWork>().setInputData(workDataOf("backend" to backend.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
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
        if (backend == LocalBackend.Npu && !LocalModels.npuSupported()) { status("本机尚未适配 NPU，请下载 CPU 模型");return@withContext Result.failure() }
        val spec=if(speakers) LocalModels.speakers else LocalModels.spec(backend);val dir=LocalModels.directory(applicationContext,spec)
        if(LocalModels.ready(applicationContext,spec)){status("已下载并校验");return@withContext Result.success()}
        val archive=File(applicationContext.noBackupFilesDir,"${spec.id}.part")
        val staging=File(dir.parentFile,"${spec.id}.staging")
        try {
            setForeground(getForegroundInfo())
            if(applicationContext.noBackupFilesDir.usableSpace < (if(speakers) 30_000_000L else 650_000_000L)) {status("空间不足，请预留至少 650 MB");return@withContext Result.failure()}
            status("连接官方下载源…")
            downloadModelFile(spec,archive,::status)
            status("校验下载文件…")
            if(archive.length()!=spec.archiveBytes || LocalModels.hash(archive)!=spec.archiveHash){archive.delete();error("checksum")}
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
        catch(_: Exception){status("下载未完成，请检查网络后重试（GitHub 国内可能不稳定）");Result.failure()}
        finally{staging.deleteRecursively()}
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("local-model","本地模型下载",NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3010+if(speakers)2 else if(::backend.isInitialized && backend==LocalBackend.Npu)1 else 0,
            NotificationCompat.Builder(applicationContext,"local-model").setSmallIcon(R.drawable.ic_notify).setContentTitle(if(speakers) "正在下载说话人分段模型" else "正在下载 SenseVoice 本地模型").setOngoing(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/** ASR 与搜索共用的有界、可取消断点下载；调用方负责 SHA 校验和原子发布。 */
suspend fun downloadModelFile(spec: ModelSpec, target: File, status: (String) -> Unit = {}) {
        if(target.length()>spec.archiveBytes)target.delete()
        if(target.length()==spec.archiveBytes)return
        val offset=target.length()
        val request=Request.Builder().url(spec.url).apply{if(offset>0)header("Range","bytes=$offset-")}.build()
        val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()
        val call=client.newCall(request)
        try { call.awaitResponse().use { response ->
            check(response.isSuccessful)
            val resume=response.code==206 && offset>0
            if(resume)check(response.header("Content-Range")?.startsWith("bytes $offset-")==true)
            var count=if(resume)offset else 0L;var last=0L
            response.body!!.byteStream().use{input->java.io.FileOutputStream(target,resume).buffered().use{output->
                val buffer=ByteArray(128*1024)
                while(true){kotlin.coroutines.coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;check(count<=spec.archiveBytes);output.write(buffer,0,n)
                    if(count-last>=1024*1024){status("下载 ${count*100/spec.archiveBytes}% · ${count/1_000_000}/${spec.archiveBytes/1_000_000} MB");last=count}
                }
            }}
        }} finally{call.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
