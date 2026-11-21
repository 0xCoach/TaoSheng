package app.resound.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.resound.ResoundApp
import app.resound.backup.BackupRepository
import java.io.File

@Suppress("DEPRECATION")
internal class AndroidUpdatePlatform(
    private val context: Context,
    private val backup: PreUpdateBackup,
) : UpdatePlatform {
    private val manager get() = context.packageManager
    override fun verify(apk: File, manifest: UpdateManifest) {
        val archive = manager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "archive_unreadable")
        val current = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        validateIdentity(archive.packageName, archive.longVersionCode, current.longVersionCode,
            manifest.versionCode, signers(archive), signers(current), signerHistory(archive))
    }
    override suspend fun backup(progress: (String, Float) -> Unit) = backup.run(progress)
    override fun canInstall() = manager.canRequestPackageInstalls()
    override fun sessionExists(id: Int): Boolean = manager.packageInstaller.getSessionInfo(id)?.let {
        // sealed 在 API 29 可用；未提交的会话不能让更新页永远停在“安装中”。
        if (!it.isSealed) { manager.packageInstaller.abandonSession(id); false } else true
    } ?: false

    override fun endSession(id: Int) {
        runCatching { manager.packageInstaller.abandonSession(id) }
            .onFailure { UpdateDiagnostics.record(context, "install session=$id abandon=${it.javaClass.simpleName}") }
        UpdateNotifications.dismissConfirmation(context)
    }
    override fun installedVersionCode() = manager.getPackageInfo(context.packageName, 0).longVersionCode
    override suspend fun afterCommit(session: Int) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        InstallRelay.openFromForeground(session)
    }

    override fun install(apk: File, requireUserAction: Boolean, rememberSession: (Int) -> Unit) {
        val installer = manager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName("app.resound")
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(if (requireUserAction) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            rememberSession(id)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                // API 35 的 commit 不接受 immutable；显式组件 + 不导出 receiver 限定接收范围。
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) { runCatching {
            // 仅释放/清除临时资源；失败不改变已确定的业务结果，可忽略。
             installer.abandonSession(id) }; throw e }
    }
    override fun notifyAvailable(info: UpdateInfo) = UpdateNotifications.available(context, info)
    override fun notifyReady(info: UpdateInfo) = UpdateNotifications.available(context, info, ready = true)
    /** 卡 210：WiFi（不计流量）随便下；用流量时只自动下 20 MB 以内的差异包 */
    override fun canPrepare(bytes: Long): Boolean {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return unmetered || bytes in 1..20L * 1024 * 1024
    }
    override fun dismissNotification() = UpdateNotifications.dismiss(context)
    override fun afterReplacement() = VoiceInputAfterUpdateWork.schedule(context)

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
        ?.map { digest(it) }?.toSet().orEmpty()

    /** v3 签名轮换：新包 signingCertificateHistory 返回 lineage 全部证书（含当前 signer；未轮换时仅当前证书）。 */
    private fun signerHistory(info: PackageInfo): Set<String> =
        (info.signingInfo?.signingCertificateHistory ?: emptyArray<android.content.pm.Signature>())
            .map { digest(it) }.toSet()

    private fun digest(sig: android.content.pm.Signature): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).hex()

    companion object {
        internal fun validateIdentity(packageName: String?, version: Long, installed: Long, expected: Int,
            newSigners: Set<String>, currentSigners: Set<String>, newHistory: Set<String> = emptySet()) {
            if (packageName != "app.resound" || version <= installed || version != expected.toLong())
                throw UpdateFailure("安装包不适用于声声不息，已删除", "package_or_version")
            if (newSigners.isEmpty() || currentSigners.isEmpty()) throw UpdateFailure("安装包签名不对，已删除", "signature")
            // 同证书直接放行；换证书只认新包 lineage 明确包含当前证书的轮换链（Android 装包时还会再核一遍）。
            if (newSigners != currentSigners && !currentSigners.all { it in newHistory })
                throw UpdateFailure("安装包签名不对，已删除", "signature")
        }
    }
}

internal fun createUpdater(app: ResoundApp, backup: BackupRepository): UpdateEngine {
    val log: (String) -> Unit = { UpdateDiagnostics.record(app, it) }
    val store = AndroidUpdateStore(app)
    @Suppress("DEPRECATION") val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    return UpdateEngine(installed.longVersionCode, installed.versionName.orEmpty(), File(app.applicationInfo.sourceDir),
        File(app.filesDir, "update"), app.scope, store,
        UpdateRemote(log = log, sources = { UpdateSources.configured(store.string("manifestUrl")) },
            previewSources = { UpdateSources.preview() }),
        AndroidUpdatePlatform(app, PreUpdateBackup(app, app.database, backup, log)), log,
        reschedule = { enabled, preview -> UpdateWork.schedule(app, enabled, preview) })
}
