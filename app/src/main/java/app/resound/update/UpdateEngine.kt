package app.resound.update

import app.resound.diagnostics.FailureNotes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal interface UpdateStore {
    fun string(key: String): String?
    fun long(key: String): Long
    fun save(vararg values: Pair<String, Any?>)
}

internal interface UpdatePlatform {
    fun verify(apk: File, manifest: UpdateManifest)
    /** [progress]：当前步骤文字和整体进度 0..1，给更新页显示 */
    suspend fun backup(progress: (String, Float) -> Unit)
    fun install(apk: File, rememberSession: (Int) -> Unit)
    fun endSession(id: Int) {}
    fun installedVersionCode(): Long? = null
    suspend fun afterCommit(session: Int): Boolean = true
    fun canInstall(): Boolean
    fun sessionExists(id: Int): Boolean
    fun notifyAvailable(info: UpdateInfo): Boolean
    fun dismissNotification()
    fun afterReplacement()
}

/** 网络、摘要、拼包及快照均在 IO 线程；一个互斥锁串行化检查、下载、安装和安装回调。 */
internal class UpdateEngine(
    private val versionCode: Long,
    override val currentVersion: String,
    private val installedApk: File,
    private val directory: File,
    private val scope: CoroutineScope,
    private val store: UpdateStore,
    private val remote: UpdateRemote,
    private val platform: UpdatePlatform,
    private val log: (String) -> Unit,
    private val availableSpace: () -> Long = { directory.usableSpace },
    private val now: () -> Long = System::currentTimeMillis,
    private val reschedule: (Boolean, Boolean) -> Unit = { _, _ -> },
) : AppUpdater {
    private val gate = Mutex()
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state = mutable.asStateFlow()
    private val fallback = MutableStateFlow<UpdateInfo?>(null)
    val systemInstallerAvailable = fallback.asStateFlow()
    private var watchdog: kotlinx.coroutines.Job? = null

    private fun offerInstaller(signal: InstallSignal, reason: String) {
        if (InstallConfirmationRule.action(signal, false) != InstallAction.OFFER_SYSTEM_INSTALLER) return
        watchdog?.cancel()
        store.save("fallback" to manifest!!.apk.sha256)
        fallback.value = info()
        mutable.value = UpdateState.Failed("可以用系统安装器安装", info())
        log("install session=${store.long("session")} fallback=$reason")
    }

    private fun watchCommit(session: Int) {
        watchdog?.cancel()
        val remaining = InstallConfirmationRule.remainingMs(store.long("installStarted"), now())
        watchdog = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(remaining)
            gate.withLock {
                if (session.toLong() == store.long("session"))
                    offerInstaller(InstallSignal.TIMEOUT, "no_progress_30s")
            }
        }
    }

    suspend fun pending(session: Int): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            if (manifest == null || session.toLong() != store.long("session")) return@withLock false
            watchdog?.cancel()
            log("install session=$session result=pending_user_action")
            true
        }
    }

    suspend fun pendingRelayIsCurrent(session: Int) = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            session >= 0 && manifest != null && session.toLong() == store.long("session")
        }
    }

    suspend fun confirmationReturned(session: Int) = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            val release = manifest ?: return@withLock
            if (session.toLong() != store.long("session")) return@withLock
            if ((platform.installedVersionCode() ?: versionCode) >= release.versionCode) {
                watchdog?.cancel()
                store.save("session" to null, "fallback" to null)
                fallback.value = null
                mutable.value = UpdateState.UpToDate(now())
                log("install session=$session result=success_on_return")
            } else {
                val exists = runCatching { platform.sessionExists(session) }.getOrNull()
                offerInstaller(InstallSignal.UNRESOLVED_RETURN, "confirmation_returned_session_exists_$exists")
            }
        }
    }

    suspend fun unresolved(session: Int, reason: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            if (manifest != null && session.toLong() == store.long("session"))
                offerInstaller(InstallSignal.UNRESOLVED_RETURN, reason)
        }
    }

    suspend fun handToSystem(open: suspend (File) -> Unit): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            if (fallback.value == null) return@withLock false
            if (!canInstall()) {
                log("install system_installer_failure=unknown_sources_permission")
                return@withLock false
            }
            try {
                verifyReady(apkFile())
                open(apkFile())
                // 系统安装器已接手。原会话的迟到取消不能撤回兜底或覆盖新结果。
                endOriginalSession()
                log("install result=system_installer_opened")
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is UpdateFailure) {
                    endOriginalSession()
                    fallback.value = null
                    store.save("fallback" to null, "ready" to null, "session" to null)
                    apkFile().delete()
                }
                log("install system_installer_failure=${reason(e)}")
                mutable.value = UpdateState.Failed((e as? UpdateFailure)?.human ?: "打不开系统安装器", info())
                false
            }
        }
    }

    private fun endOriginalSession() {
        val session = store.long("session").toInt()
        watchdog?.cancel()
        store.save("session" to null)
        if (session > 0) platform.endSession(session)
    }

    private val auto = MutableStateFlow(store.long("autoOff") != 1L)
    override val autoCheck = auto.asStateFlow()
    override fun setAutoCheck(enabled: Boolean) {
        store.save("autoOff" to if (enabled) 0L else 1L); auto.value = enabled
        reschedule(enabled, preview.value)
    }
    private val preview = MutableStateFlow(store.long("previewChannel") == 1L)
    override val previewChannel = preview.asStateFlow()
    override val isPreviewBuild: Boolean get() = "-preview." in currentVersion
    override fun setPreviewChannel(enabled: Boolean) {
        store.save("previewChannel" to if (enabled) 1L else 0L)
        preview.value = enabled
        reschedule(auto.value, enabled)
        scope.launch(Dispatchers.IO) {
            gate.withLock { restore(); discardDisabledPreview() }
            if (enabled) checkNow()
        }
    }
    private fun discardDisabledPreview() {
        if (fallback.value == null && !preview.value && manifest?.previewSource == true && mutable.value !is UpdateState.Installing) {
            manifest = null; patch = null
            store.save("manifest" to null, "ready" to null, "manifestPreview" to null)
            mutable.value = UpdateState.Idle
            platform.dismissNotification()
        }
    }
    private var restored = false
    private var manifest: UpdateManifest? = null
    private var patch: UpdatePatch? = null
    private fun info() = manifest!!.info(patch)
    private fun apkFile() = File(directory, "${manifest!!.apk.sha256}.apk")

    override fun check(manual: Boolean) { scope.launch { checkNow(manual) } }
    suspend fun checkNow(manual: Boolean = false, startup: Boolean = false) = withContext(Dispatchers.IO) {
        gate.withLock {
            restore()
            discardDisabledPreview()
            if (mutable.value is UpdateState.Installing || fallback.value != null) return@withLock
            // 关了自动检查：后台和启动时都不查，只认手动
            if (!manual && !auto.value) return@withLock
            val last = store.long("lastCheck")
            if (startup && !preview.value && last > 0 && now() - last in 0 until HOUR) return@withLock
            val previous = mutable.value
            mutable.value = UpdateState.Checking
            store.save("lastCheck" to now())
            try {
                val result = remote.check(preview.value)
                if (!preview.value && result.previewSource) {
                    discardDisabledPreview()
                    mutable.value = UpdateState.Idle
                    return@withLock
                }
                if (previous is UpdateState.Ready && result.versionCode <= previous.info.versionCode) {
                    mutable.value = previous
                    return@withLock
                }
                // 跳过的版本：自动检查当作已是最新、不通知；手动检查照常显示，方便改主意
                val skipped = !manual && store.long("skipVersion") >= result.versionCode
                if (!result.newerThan(versionCode) || skipped) {
                    manifest = null; patch = null
                    store.save("manifest" to null, "ready" to null)
                    mutable.value = UpdateState.UpToDate(now())
                } else {
                    manifest = result
                    patch = result.patchFor(installedHash())
                    store.save("manifest" to result.json(), "manifestPreview" to if (result.previewSource) 1L else 0L, "incremental" to if (patch != null) 1L else 0L)
                    mutable.value = UpdateState.Available(info())
                    notifyOnce()
                }
            } catch (e: CancellationException) { mutable.value = previous; throw e }
            catch (e: Exception) {
                log("check manual=$manual failure=${reason(e)}")
                mutable.value = if (manual) UpdateState.Failed(UPDATE_NETWORK_ERROR, manifest?.let { info() }) else previous
            }
        }
    }

    /** 进程重启只恢复状态，不会恢复自动下载或自动安装。 */
    private fun restore() {
        if (restored) return
        kotlin.check(directory.isDirectory || directory.mkdirs())
        val seen = store.long("installedVersion")
        val saved = store.string("manifest")?.let { runCatching { UpdateManifest.parse(it).let { release -> release.copy(previewSource = store.long(
            "manifestPreview") == 1L || release.preview) } }.onFailure {
                FailureNotes.record("update/UpdateEngine.kt:132", it)
            }.getOrNull() }
        if ((seen > 0 && seen != versionCode) || (saved != null && !saved.newerThan(versionCode))) {
            directory.listFiles()?.forEach { kotlin.check(it.deleteRecursively()) }
            store.save("manifest" to null, "ready" to null, "session" to null, "fallback" to null, "installedHash" to null)
            if (versionCode > seen) platform.afterReplacement()
            log("install result=replacement installed=$versionCode")
            log("startup code=$versionCode cleanup=1")
        } else if (saved != null) {
            manifest = saved
            patch = if (store.long("incremental") == 1L) saved.patchFor(installedHash()) else null
            mutable.value = UpdateState.Available(info())
            val file = apkFile()
            if (store.string("ready") == saved.apk.sha256 && file.isFile) {
                try {
                    verifyReady(file)
                    val session = store.long("session").toInt()
                    when {
                        store.string("fallback") == saved.apk.sha256 -> offerInstaller(InstallSignal.FAILURE, "restored")
                        session > 0 && runCatching { platform.sessionExists(session) }.getOrDefault(false) -> {
                            mutable.value = UpdateState.Installing(info(), false)
                            watchCommit(session)
                        }
                        session > 0 -> offerInstaller(InstallSignal.UNRESOLVED_RETURN, "session_missing")
                        else -> mutable.value = UpdateState.Ready(info())
                    }
                } catch (e: Exception) {
                    file.delete(); store.save("ready" to null, "session" to null)
                    log("restore failure=${reason(e)}")
                }
            }
        }
        store.save("installedVersion" to versionCode)
        restored = true
    }

    private fun installedHash(): String? {
        val identity = "$versionCode:${installedApk.absolutePath}:${installedApk.length()}:${installedApk.lastModified()}"
        if (store.string("hashIdentity") == identity) return store.string("installedHash")
        return try {
            val hash = installedApk.updateSha256()
            store.save("hashIdentity" to identity, "installedHash" to hash)
            hash
        } catch (e: Exception) { log("installed_hash failure=${reason(e)}"); null }
    }

    override fun download() { scope.launch(Dispatchers.IO) {
        // 卡 210：安装没成（进了“用系统安装器”退路）时点“重试”，原先会被下面的条件直接忽略，怎么点都没反应。
        // 安装包还在就清掉旧会话、重新走一次安装，不重新下载。
        val retryInstall = gate.withLock {
            restore()
            val release = manifest
            if (release == null || fallback.value == null || store.string("ready") != release.apk.sha256) return@withLock false
            endOriginalSession()
            store.save("fallback" to null); fallback.value = null
            mutable.value = UpdateState.Ready(info())
            log("install retry_from_fallback")
            true
        }
        if (retryInstall) { install(); return@launch }
        gate.withLock {
            restore()
            discardDisabledPreview()
            if (manifest == null || fallback.value != null || mutable.value is UpdateState.Ready || mutable.value is UpdateState.Installing) return@withLock
            val release = manifest!!
            val start = System.nanoTime()
            try {
                val target = apkFile()
                val selected = patch
                if (selected != null) {
                    space(release.apk.size + selected.asset.size)
                    val part = File(directory, "${selected.asset.sha256}.patch")
                    val assembled = File(directory, "assembled.part")
                    try {
                        fetch(selected.asset, part, true)
                        mutable.value = UpdateState.Assembling(info())
                        val assemblyStart = System.nanoTime()
                        try {
                            val hash = Bspatch.apply(installedApk, part, assembled, release.apk.size)
                            if (hash != release.apk.sha256) throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "assembled_sha256")
                            kotlin.check(!target.exists() || target.delete())
                            kotlin.check(assembled.renameTo(target))
                            log("assemble incremental=true bytes=${target.length()} ms=${elapsed(assemblyStart)} result=ok")
                        } catch (e: Exception) {
                            log("assemble incremental=true ms=${elapsed(assemblyStart)} failure=${reason(e)}")
                            throw e
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        part.delete(); assembled.delete(); target.delete()
                        patch = null
                        store.save("incremental" to 0L)
                        log("fallback incremental=false failure=${reason(e)}")
                        space(release.apk.size)
                        fetch(release.apk, target, false)
                    } finally { part.delete(); assembled.delete() }
                } else {
                    space(release.apk.size)
                    fetch(release.apk, target, false)
                }
                // 只有完整摘要、包名、版本及证书都通过，才让界面出现“安装”。
                try { platform.verify(target, release) }
                catch (e: Exception) { target.delete(); throw e }
                store.save("ready" to release.apk.sha256)
                mutable.value = UpdateState.Ready(info())
                platform.dismissNotification()
                log("ready incremental=${patch != null} bytes=${target.length()} ms=${elapsed(start)} result=ok")
            } catch (e: CancellationException) { mutable.value = UpdateState.Available(info()); throw e }
            catch (e: Exception) { fail(e, UPDATE_NETWORK_ERROR, start); return@withLock }
            // Ready 已发布，但继续持有 gate；此时点安装会等待本次备份，不会重复启动。
            val backupStart = System.nanoTime()
            preBackupRunning = true
            try {
                backupReady { _, _ -> }
                log("pre_backup ms=${elapsed(backupStart)} result=ok")
            } catch (e: CancellationException) {
                log("pre_backup ms=${elapsed(backupStart)} result=cancelled")
                throw e
            } catch (e: Exception) {
                // 安装包已校验完成；备份失败不撤销 Ready，安装时按原流程重试。
                log("pre_backup ms=${elapsed(backupStart)} failure=${reason(e)}")
            } finally { preBackupRunning = false }
        }
    } }

    private fun fetch(asset: UpdateAsset, file: File, incremental: Boolean) {
        mutable.value = UpdateState.Downloading(info(), file.length(), asset.size)
        remote.download(asset, manifest!!.versionName, file, incremental, manifest!!.previewSource) { done ->
            mutable.value = UpdateState.Downloading(info(), done, asset.size)
        }
    }
    private fun space(bytes: Long) {
        val required = bytes + (bytes + 4) / 5
        val missing = required - availableSpace()
        if (missing > 0) throw UpdateFailure("手机空间不够，还差 ${(missing + 1_048_575) / 1_048_576} MB", "space_short_$missing")
    }
    private fun verifyReady(file: File) {
        val asset = manifest!!.apk
        if (!file.isFile || file.length() != asset.size || file.updateSha256() != asset.sha256)
            throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "ready_sha256")
        platform.verify(file, manifest!!)
    }

    /** 只在完整备份成功后写入；AndroidUpdateStore 将两项一起持久化。 */
    private suspend fun backupReady(progress: (String, Float) -> Unit) {
        platform.backup(progress)
        store.save("preBackupFor" to manifest!!.apk.sha256, "preBackupAt" to now())
    }

    /** 下载后的后台备份进行中；此时点安装要等它，先把界面切到“正在保存”，免得像没点上 */
    @Volatile private var preBackupRunning = false

    override fun install() { scope.launch(Dispatchers.IO) {
        val waited = preBackupRunning && mutable.value is UpdateState.Ready
        if (waited) mutable.value = UpdateState.Installing(info(), true, "正在保存本机数据", 0f)
        gate.withLock {
            restore()
            discardDisabledPreview()
            if (manifest == null || fallback.value != null || (mutable.value is UpdateState.Installing && !waited) || store.string("ready") != manifest!!.apk.sha256) {
                if (waited && mutable.value is UpdateState.Installing && manifest != null) mutable.value = UpdateState.Ready(info())
                // 被忽略的点击也要留痕，否则“点了没反应”查不出原因
                log("install ignored manifest=${manifest != null} fallback=${fallback.value != null} state=${mutable.value::class.simpleName} ready_match=${manifest?.let { store.string("ready") == it.apk.sha256 }}")
                return@withLock
            }
            if (!canInstall()) { mutable.value = UpdateState.Ready(info()); log("install ignored unknown_sources_permission"); return@withLock }
            val start = System.nanoTime()
            try {
                try { verifyReady(apkFile()) }
                catch (e: Exception) { apkFile().delete(); store.save("ready" to null); throw e }
                val backupAt = store.long("preBackupAt")
                val reuseBackup = store.string("preBackupFor") == manifest!!.apk.sha256 &&
                    backupAt > 0 && now() - backupAt in 0 until 6 * HOUR
                var backupMs = 0L
                try {
                    if (!reuseBackup) {
                        mutable.value = UpdateState.Installing(info(), true, "正在保存本机数据", 0f)
                        val backupStart = System.nanoTime()
                        try { backupReady { step, p -> mutable.value = UpdateState.Installing(info(), true, step, p) } }
                        finally { backupMs = elapsed(backupStart) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw UpdateFailure("更新前备份没成功，没有安装", "backup_${reason(e)}") }
                finally { log("install_backup reused=$reuseBackup backup_ms=$backupMs") }
                mutable.value = UpdateState.Installing(info(), false)
                try {
                    platform.install(apkFile()) { session -> store.save("session" to session.toLong()) }
                } catch (e: Exception) {
                    store.save("session" to null)
                    offerInstaller(InstallSignal.FAILURE, "submit_${reason(e)}")
                    return@withLock
                }
                val session = store.long("session").toInt()
                store.save("installStarted" to now())
                watchCommit(session)
                log("install incremental=${patch != null} bytes=${apkFile().length()} ms=${elapsed(start)} result=submitted session=$session")
                if (!platform.afterCommit(session)) offerInstaller(InstallSignal.FAILURE, "relay_start_failed")
            } catch (e: CancellationException) { mutable.value = UpdateState.Ready(info()); throw e }
            catch (e: Exception) {
                store.save("session" to null)
                fail(e, "系统没能装上：无法启动安装", start)
            }
        }
    } }

    /** receiver 在进程被重建后也可调用；旧会话回调不会覆盖新会话。 */
    suspend fun installResult(session: Int, success: Boolean = false, cancelled: Boolean = false,
        message: String? = null, diagnosticStatus: Int = 0) =
        withContext(Dispatchers.IO) { gate.withLock {
            restore()
            discardDisabledPreview()
            if (manifest == null || session.toLong() != store.long("session")) return@withLock
            log("install session=$session status=$diagnosticStatus ms=${now() - store.long("installStarted")} result=${if (success) "success" else if (cancelled) "cancelled" else "failed"}")
            store.save("session" to null)
            watchdog?.cancel()
            val signal = when {
                success -> InstallSignal.SUCCESS
                cancelled -> InstallSignal.CANCELLED
                else -> InstallSignal.FAILURE
            }
            when (InstallConfirmationRule.action(signal, false)) {
                InstallAction.COMPLETE, InstallAction.READY -> {
                    fallback.value = null
                    store.save("fallback" to null)
                    mutable.value = if (success) UpdateState.UpToDate(now()) else UpdateState.Ready(info())
                }
                else -> {
                    offerInstaller(signal, "status_$diagnosticStatus")
                    mutable.value = UpdateState.Failed("系统没能装上：${message?.lineSequence()?.firstOrNull()?.take(160) ?: "安装失败"}", info())
                }
            }
        } }

    override fun later() { scope.launch(Dispatchers.IO) { gate.withLock {
        restore()
        manifest?.let { store.save("laterVersion" to it.versionCode.toLong(), "laterUntil" to now() + 24 * HOUR) }
        platform.dismissNotification()
    } } }
    override fun skip() { scope.launch(Dispatchers.IO) { gate.withLock {
        restore()
        if (fallback.value != null) {
            endOriginalSession()
            store.save("fallback" to null, "ready" to null)
            fallback.value = null
            apkFile().delete()
        }
        manifest?.let { store.save("skipVersion" to it.versionCode.toLong()) }
        platform.dismissNotification()
        if (mutable.value is UpdateState.Available || mutable.value is UpdateState.Failed) mutable.value = UpdateState.UpToDate(now())
    } } }
    override fun canInstall() = platform.canInstall()
    private fun notifyOnce() {
        val info = info()
        if (store.long("notifiedVersion") >= info.versionCode ||
            (store.long("laterVersion") == info.versionCode.toLong() && now() < store.long("laterUntil"))) return
        if (platform.notifyAvailable(info)) store.save("notifiedVersion" to info.versionCode.toLong())
    }
    private fun fail(e: Exception, default: String, start: Long) {
        log("update ms=${elapsed(start)} failure=${reason(e)}")
        mutable.value = UpdateState.Failed((e as? UpdateFailure)?.human ?: default, manifest?.let { info() })
    }
    companion object { private const val HOUR = 60 * 60 * 1000L }
}
