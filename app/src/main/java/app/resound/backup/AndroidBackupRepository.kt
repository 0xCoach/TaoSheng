package app.resound.backup

import android.content.Context
import android.net.Uri
import app.resound.appName
import app.resound.data.CaptureDatabase
import app.resound.digest.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class AndroidBackupRepository internal constructor(
    private val context: Context,
    private val database: CaptureDatabase,
    private val config: BackupConfig = BackupConfig(),
    private val diary: app.resound.diary.DiaryRepository? = null,
    private val passphrases: BackupPassphrases = SecureBackupPassphrases(context),
    private val localFiles: LocalBackupFiles = LocalBackupFiles(context),
    private val restartProcess: () -> Unit = { BackupRestore.restart(context) },
    private val clientFactory: (() -> WebDavClient)? = null,
) : BackupRepository {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "backup-secrets", "resound-backup-aes-v1")
    private val scheduler = BackupScheduler(context)
    private val mutable = MutableStateFlow(readState())
    override val state = mutable.asStateFlow()
    private fun readState() = BackupState(prefs.getBoolean("enabled", false), prefs.getString("account", null),
        secrets.has("password"), false, prefs.getLong("lastAt", 0).takeIf { it > 0 },
        prefs.getLong("lastBytes", -1).takeIf { it >= 0 }, prefs.getInt("count", -1).takeIf { it >= 0 },
        prefs.getString("error", null), encrypted = passphrases.has(), keep = prefs.getInt("keep", 7))
    @Synchronized private fun refresh(running: Boolean = mutable.value.running) { mutable.value = readState().copy(running = running) }
    @Synchronized private fun error(message: String?) { check(prefs.edit().putString("error", message).commit()); refresh() }
    private val mutableLocal = MutableStateFlow(readLocal())
    override val local = mutableLocal.asStateFlow()
    private fun readLocal() = LocalBackupState(prefs.getString("localFolderName", null),
        prefs.getBoolean("localAuto", false), prefs.getInt("localKeep", 7), false,
        prefs.getLong("localLastAt", 0).takeIf { it > 0 }, prefs.getLong("localLastBytes", -1).takeIf { it >= 0 },
        prefs.getInt("localCount", -1).takeIf { it >= 0 }, prefs.getString("localError", null))
    @Synchronized private fun refreshLocal(running: Boolean = mutableLocal.value.running) {
        mutableLocal.value = readLocal().copy(running = running)
    }
    @Synchronized private fun localError(message: String?) {
        check(prefs.edit().putString("localError", message).commit()); refreshLocal()
    }
    private fun schedule() = scheduler.schedule(state.value.enabled && state.value.hasPassword && state.value.account != null,
        local.value.auto && prefs.contains("localTree"))
    init { schedule() }
    @Synchronized override fun saveCredentials(account: String, appPassword: String) {
        val email = account.trim()
        require(email.contains('@') && ':' !in email && email.none { it.isISOControl() } && appPassword.isNotBlank()) { "请填写邮箱和应用密码" }
        try {
            secrets.write("password", appPassword)
            check(prefs.edit().apply {
                if (prefs.getString("account", null) != email) remove("lastAt").remove("lastBytes").remove("count")
            }.putString("account", email).remove("error").commit())
            refresh(); schedule()
        } catch (_: Exception) { error("账号保存失败，请重试") }
    }
    @Synchronized override fun clearCredentials() {
        try {
            secrets.remove("password")
            check(prefs.edit().remove("account").putBoolean("enabled", false).remove("error")
                .remove("lastAt").remove("lastBytes").remove("count").commit())
            scheduler.cancelCloud(); refresh(); schedule()
        } catch (_: Exception) { error("账号清除失败，请重试") }
    }
    @Synchronized override fun setEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("enabled", enabled).commit())
        refresh(); schedule()
    }
    @Synchronized override fun setEncryption(passphrase: String?) {
        require(passphrase == null || passphrase.isNotBlank()) { "备份口令不能为空" }
        try {
            passphrases.write(passphrase)
            // 新口令不能依赖旧口令加密的完整包；下一次两端各自开新套。
            check(prefs.edit().remove("base-cloud").remove("base-local").commit())
            error(null)
        }
        catch (_: Exception) { error("备份口令安全保存失败，请重试") }
    }
    @Synchronized private fun backupPassphrase(): String? = if (!passphrases.has()) null else {
        // 安全区读取失败不能静默降级成明文备份。
        try { passphrases.read().takeIf { it.isNotBlank() } ?: throw IllegalStateException() }
        catch (_: Exception) { throw BackupFailure("备份口令读取失败，请重新设置") }
    }
    override suspend fun remoteBackups(): Result<List<RemoteBackup>> = withContext(Dispatchers.IO) {
        gate.withLock {
            try { Result.success(HistoryVolumes.list(client().volumes())) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(backupError(e)) }
        }
    }
    override fun backupNow() { scheduler.runNow() }
    @Synchronized private fun client(): WebDavClient {
        clientFactory?.let { return it() }
        val account = prefs.getString("account", null) ?: throw BackupFailure("请先填写坚果云账号和应用密码")
        val password = runCatching { secrets.read("password") }.getOrDefault("")
        if (password.isBlank()) throw BackupFailure("请先填写坚果云账号和应用密码")
        return WebDavClient(config, context.appName, account, password)
    }
    /** 同步文件夹现取安全区凭据，不复制到推送配置。 */
    @Synchronized internal fun pushCredentials(): Map<String, String>? {
        val account = prefs.getString("account", null)?.takeIf { it.isNotBlank() } ?: return null
        val password = runCatching { secrets.read("password") }.getOrDefault("").takeIf { it.isNotBlank() } ?: return null
        return mapOf("account" to account, "password" to password, "url" to config.endpoint)
    }
    override suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        gate.withLock {
            refresh(true)
            try { client().testWrite(); error(null); Result.success("连接成功，可以备份") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { val failure = backupError(e); error(failure.human); Result.failure(failure) }
            finally { refresh(false) }
        }
    }
    /** 返回是否需要 WorkManager 退避重试。 */
    internal suspend fun runBackup(periodic: Boolean): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            if (periodic && !state.value.enabled) return@withLock false
            refresh(true)
            val workspace = File(context.cacheDir, "backup-${UUID.randomUUID()}")
            try {
                check(workspace.mkdirs())
                val client = client()
                client.ensureDirectory()
                ensureActive()
                val summary = HistoryVolumes.sync(context, database, client.volumes(), workspace, backupPassphrase(), state.value.keep)
                check(prefs.edit().putLong("lastAt", summary.lastAt!!).putLong("lastBytes", summary.lastUploadBytes!!)
                    .putInt("count", summary.days).remove("error").commit())
                BackupRestore.clearSnapshots(context)
                if (periodic) syncDiary(client)
                false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { val failure = backupError(e); error(failure.human); failure.retryable }
            finally { workspace.deleteRecursively(); refresh(false) }
        }
    }
    /** 日记错误单独记一次诊断，绝不改已成功的备份状态。取消仍正常向上传递。 */
    private suspend fun syncDiary(client: WebDavClient) {
        val repository = diary ?: return
        if (!repository.cloudSync.value) return
        var failed = false
        val today = java.time.LocalDate.now()
        for (date in listOf(today.minusDays(1), today)) {
            try {
                client.uploadDiary(date, repository.markdown(date, date, false))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        if (failed) {
            // 不保存原文、响应体、账号或异常信息；诊断写盘失败也不影响 ZIP 备份。
            runCatching {
                context.getSharedPreferences("diary-sync", Context.MODE_PRIVATE).edit()
                    .putLong("lastFailureAt", System.currentTimeMillis())
                    .putString("lastFailure", "日记上传失败，下次定时备份时重试").commit()
            }
            android.util.Log.w("DiarySync", "日记上传失败，下次定时备份时重试")
        }
    }

    override suspend fun restore(name: String, passphrase: String?): Result<Unit> = withContext(Dispatchers.IO) {
        gate.withLock {
            refresh(true)
            try {
                ensureActive()
                if (historyPath(name)) restoreVolumes(client().volumes(), passphrase, name)
                else BackupRestore.restore(context, client(), name, database,
                    storedPassphrase = runCatching { passphrases.read() }.getOrNull(), suppliedPassphrase = passphrase,
                    closeDatabase = database::close, restartProcess = restartProcess)
                Result.success(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { val failure = backupError(e); error(failure.human); Result.failure(failure) }
            finally { refresh(false) }
        }
    }
    @Synchronized override fun setLocalFolder(tree: Uri) {
        try {
            val name = localFiles.select(tree)
            // 换手机时还没有备份口令，也必须先保存文件夹，随后恢复入口才能接收用户输入的口令。
            val count = runCatching {
                withWorkspace { HistoryVolumes.summary(localFiles.volumes(tree), it, runCatching { passphrases.read() }.getOrNull())?.days ?: 0 }
            }.getOrDefault(-1)
            check(prefs.edit().apply {
                if (prefs.getString("localTree", null) != tree.toString()) remove("localLastAt").remove("localLastBytes")
            }.putString("localTree", tree.toString()).putString("localFolderName", name)
                .putInt("localCount", count).remove("localError").commit())
            refreshLocal(); schedule()
        } catch (_: Exception) { localError(LocalBackupFiles.INACCESSIBLE) }
    }
    @Synchronized override fun setLocalAuto(enabled: Boolean) {
        check(prefs.edit().putBoolean("localAuto", enabled).commit()); refreshLocal(); schedule()
    }
    @Synchronized override fun setKeep(count: Int) {
        require(count in setOf(3, 7, 30))
        check(prefs.edit().putInt("keep", count).commit()); refresh()
    }
    @Synchronized override fun setLocalKeep(count: Int) {
        require(count in setOf(1, 3, 7, 30))
        check(prefs.edit().putInt("localKeep", count).commit()); refreshLocal()
    }
    private fun localTree(): Uri = prefs.getString("localTree", null)?.let(Uri::parse)
        ?: throw BackupFailure(LocalBackupFiles.INACCESSIBLE)
    override fun backupLocalNow() { scheduler.runLocalNow() }
    override suspend fun localBackups(): Result<List<RemoteBackup>> = localOperation(readOnly = true) {
        HistoryVolumes.list(localFiles.volumes(localTree()))
    }
    internal suspend fun runLocalBackup(periodic: Boolean): Boolean {
        if (periodic && !local.value.auto) return false
        val result = localOperation {
            val tree = localTree()
            withWorkspace { work ->
                val summary = HistoryVolumes.sync(context, database, localFiles.volumes(tree), work, backupPassphrase(), local.value.keep)
                check(prefs.edit().putLong("localLastAt", summary.lastAt!!).putLong("localLastBytes", summary.lastUploadBytes!!)
                    .putInt("localCount", summary.days).commit())
                BackupRestore.clearSnapshots(context)
            }
        }
        return (result.exceptionOrNull() as? BackupFailure)?.retryable == true
    }
    override suspend fun exportTo(target: Uri): Result<Unit> = localOperation {
        withWorkspace { work ->
            val file = File(work, "export.zip")
            DayVolumes.export(context, database, work, file, backupPassphrase())
            localFiles.export(file, target)
            check(prefs.edit().putLong("localLastAt", System.currentTimeMillis()).putLong("localLastBytes", file.length()).commit())
            BackupRestore.clearSnapshots(context)
        }
    }
    private fun <T> withWorkspace(block: (File) -> T): T {
        val workspace = File(context.cacheDir, "resound-volumes-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return try { block(workspace) } finally { workspace.deleteRecursively() }
    }
    override suspend fun cloudSummary(): Result<BackupSummary?> = cloudOperation(readOnly = true) {
        withWorkspace { HistoryVolumes.summary(client().volumes(), it, runCatching { passphrases.read() }.getOrNull()) }
    }
    override suspend fun localSummary(): Result<BackupSummary?> = localOperation(readOnly = true) {
        withWorkspace { HistoryVolumes.summary(localFiles.volumes(localTree()), it, runCatching { passphrases.read() }.getOrNull()) }
    }
    override suspend fun restoreAllFromCloud(passphrase: String?): Result<Unit> = cloudOperation {
        restoreVolumes(client().volumes(), passphrase)
    }
    override suspend fun restoreAllFromLocal(passphrase: String?): Result<Unit> = localOperation {
        restoreVolumes(localFiles.volumes(localTree()), passphrase)
    }
    private fun restoreVolumes(store: VolumeStore, passphrase: String?, checkpoint: String? = null) {
        BackupRestore.restoreVolumes(context, store, database, runCatching { passphrases.read() }.getOrNull(),
            passphrase, database::close, restartProcess, checkpoint)
    }
    private suspend fun <T> cloudOperation(readOnly: Boolean = false, block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!readOnly) refresh(true)
            try { ensureActive(); Result.success(block()).also { if (!readOnly) error(null) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { val failure = backupError(e); if (!readOnly) error(failure.human); Result.failure(failure) }
            finally { if (!readOnly) refresh(false) }
        }
    }
    override suspend fun restoreLocal(name: String, passphrase: String?): Result<Unit> = localOperation {
        if (historyPath(name)) restoreVolumes(localFiles.volumes(localTree()), passphrase, name)
        else restoreSource(localFiles.source(localTree(), name), passphrase, name)
    }
    override suspend fun restoreFrom(source: Uri, passphrase: String?): Result<Unit> = localOperation {
        restoreSource(source, passphrase)
    }
    private fun restoreSource(source: Uri, passphrase: String?, name: String? = null) {
        val download = File(context.cacheDir, "resound-import-${UUID.randomUUID()}")
        try {
            localFiles.read(source, download)
            BackupRestore.restoreFile(context, download, database,
                storedPassphrase = runCatching { passphrases.read() }.getOrNull(), suppliedPassphrase = passphrase,
                closeDatabase = database::close, restartProcess = restartProcess,
                fetchBase = { base, target -> localFiles.read(localFiles.source(localTree(), base), target) }, expectedName = name)
        } finally { download.delete() }
    }
    private suspend fun <T> localOperation(readOnly: Boolean = false, block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!readOnly) refreshLocal(true)
            try { ensureActive(); val result = block(); if (!readOnly) localError(null); Result.success(result) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? BackupFailure ?: BackupFailure(
                    if (e is SecurityException || e is java.io.FileNotFoundException) LocalBackupFiles.INACCESSIBLE
                    else "本机备份处理失败，请重试")
                if (!readOnly) localError(failure.human); Result.failure(failure)
            } finally { if (!readOnly) refreshLocal(false) }
        }
    }
    companion object { private val gate = Mutex() }
}
