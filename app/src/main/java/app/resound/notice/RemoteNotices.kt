package app.resound.notice

import kotlinx.coroutines.flow.first
import android.content.Context
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * 远程通知：从魔搭（国内）/ GitHub 下载签名的 notices.json 和里面用到的图片（如二维码），
 * 用 App 内置公钥（与外设说明卡同一把）验签、核对图片校验值，存在本机不备份的目录里。
 * 只下载公开文件，不上传任何东西、不带用户编号。
 */
class RemoteNotices private constructor(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "notices").apply { mkdirs() }
    private val jsonFile = AtomicFile(File(dir, "notices.json"))
    private val sigFile = AtomicFile(File(dir, "notices.json.sig"))
    private val prefs = context.getSharedPreferences("home-notices", Context.MODE_PRIVATE)
    private val current = MutableStateFlow<NoticeFile?>(null)
    val file: StateFlow<NoticeFile?> = current.asStateFlow()
    private val dismissedState = MutableStateFlow(prefs.getStringSet(DISMISSED, emptySet()).orEmpty().toSet())
    val dismissed: StateFlow<Set<String>> = dismissedState.asStateFlow()

    init {
        // 本机缓存也重新验签，防止文件被改
        runCatching {
            if (jsonFile.baseFile.exists() && sigFile.baseFile.exists()) {
                val bytes = jsonFile.readFully(); val sig = sigFile.readFully()
                if (verify(bytes, sig)) current.value = NoticeRules.parse(bytes)
            }
        }.onFailure { FailureNotes.record("notice/RemoteNotices.kt:cache", it) }
    }

    val playbackHintSeen get() = PLAYBACK_HINT in dismissed.value
    private val hintReady = MutableStateFlow(prefs.getBoolean("heavy-playback", false))
    val showPlaybackHint = kotlinx.coroutines.flow.combine(hintReady, dismissed) { ready, seen -> ready && PLAYBACK_HINT !in seen }
    suspend fun watchPlayback(dao: app.resound.data.TranscribeDao) {
        if (hintReady.value || playbackHintSeen) return
        dao.hasHeavyPlaybackDay().first { it }
        check(prefs.edit().putBoolean("heavy-playback", true).commit())
        hintReady.value = true
    }
    fun playbackHintShown() = dismiss(PLAYBACK_HINT)

    fun image(notice: Notice): File? = notice.image?.let { File(dir, it.file) }?.takeIf { it.isFile }

    fun dismiss(id: String) {
        val next = dismissedState.value + id
        dismissedState.value = next
        prefs.edit().putStringSet(DISMISSED, next).apply()
    }

    /** 依次试各个来源，有一个成功就停；全部失败保留旧缓存 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (base in SOURCES) {
            try {
                val bytes = read(base + "notices.json", NoticeRules.MAX_FILE)
                val sig = read(base + "notices.json.sig", 128)
                require(verify(bytes, sig)) { "signature_invalid" }
                val incoming = NoticeRules.parse(bytes)
                val cachedBytes = runCatching { jsonFile.readFully() }.getOrNull()
                if (!NoticeRules.accepts(current.value?.version, cachedBytes, incoming, bytes)) return@withContext
                // 先把图片下好并核对，再换通知文件：不会出现“有通知没图片”
                incoming.notices.mapNotNull { it.image }.distinctBy { it.file }.forEach { img ->
                    val target = File(dir, img.file)
                    if (target.isFile && sha256(target.readBytes()) == img.sha256) return@forEach
                    val data = read(base + img.file, NoticeRules.MAX_IMAGE)
                    require(sha256(data) == img.sha256) { "image_mismatch" }
                    AtomicFile(target).write(data)
                }
                sigFile.write(sig); jsonFile.write(bytes)
                current.value = incoming
                // 清掉不再用到的旧图片
                val keep = incoming.notices.mapNotNull { it.image?.file }.toSet() + setOf("notices.json", "notices.json.sig")
                dir.listFiles()?.filter { it.name !in keep && !it.name.endsWith(".new") && !it.name.endsWith(".bak") }?.forEach { it.delete() }
                return@withContext
            } catch (caught: Exception) {
                FailureNotes.record("notice/RemoteNotices.kt:refresh", caught)
            }
        }
    }

    private fun read(url: String, limit: Int): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "http_${response.code}" }
            response.body!!.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= limit) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                out.toByteArray().also { require(it.size <= limit) { "too_large" } }
            }
        }

    private fun verify(json: ByteArray, signature: ByteArray): Boolean = runCatching {
        val hex = context.assets.open("device-cards-public-key.hex").bufferedReader().use { it.readText().trim() }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(json); verify(signature) }
    }.onFailure { FailureNotes.record("notice/RemoteNotices.kt:verify", it) }.getOrDefault(false)

    private fun AtomicFile.write(data: ByteArray) {
        val out = startWrite()
        try { out.write(data); finishWrite(out) } catch (e: Exception) { failWrite(out); throw e }
    }

    companion object {
        private const val PLAYBACK_HINT = "local-playback-30m"
        private const val DISMISSED = "notice-dismissed"
        /** 国内先走魔搭，GitHub 备用 */
        val SOURCES = listOf(
            "https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/notices/",
            "https://raw.githubusercontent.com/0xCoach/TaoSheng/main/notices/",
        )
        private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build() }
        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        @Volatile private var instance: RemoteNotices? = null
        fun get(context: Context): RemoteNotices = instance ?: synchronized(this) {
            instance ?: RemoteNotices(context.applicationContext).also { instance = it }
        }
    }
}

/** 打开 App 时拉一次，之后每 6 小时一次；只在联网时跑 */
class NoticeWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { RemoteNotices.get(applicationContext).refresh(); return Result.success() }
    companion object {
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniqueWork("notices-start", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NoticeWork>().setConstraints(network).build())
            manager.enqueueUniquePeriodicWork("notices-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NoticeWork>(6, TimeUnit.HOURS).setInitialDelay(6, TimeUnit.HOURS).setConstraints(network).build())
        }
    }
}
