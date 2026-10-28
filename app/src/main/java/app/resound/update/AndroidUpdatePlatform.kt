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
            manifest.versionCode, signers(archive), signers(current))
    }
    override suspend fun backup() = backup.run()
    override fun canInstall() = manager.canRequestPackageInstalls()
    override fun sessionExists(id: Int): Boolean = manager.packageInstaller.getSessionInfo(id)?.let {
        // sealed 在 API 29 可用；未提交的会话不能让更新页永远停在“安装中”。
        if (!it.isSealed) { manager.packageInstaller.abandonSession(id); false } else true
    } ?: false

    override fun install(apk: File, rememberSession: (Int) -> Unit) {
        val installer = manager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName("app.resound")
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
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
        } catch (e: Exception) { runCatching { installer.abandonSession(id) }; throw e }
    }
    override fun notifyAvailable(info: UpdateInfo) = UpdateNotifications.available(context, info)
    override fun dismissNotification() = UpdateNotifications.dismiss(context)
    override fun afterReplacement() = VoiceInputAfterUpdateWork.schedule(context)

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
        ?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()

    companion object {
        internal fun validateIdentity(packageName: String?, version: Long, installed: Long, expected: Int,
            newSigners: Set<String>, currentSigners: Set<String>) {
            if (packageName != "app.resound" || version <= installed || version != expected.toLong())
                throw UpdateFailure("安装包不适用于涛声，已删除", "package_or_version")
            if (newSigners.isEmpty() || currentSigners.isEmpty() || newSigners != currentSigners)
                throw UpdateFailure("安装包签名不对，已删除", "signature")
        }
    }
}

internal fun createUpdater(app: ResoundApp, backup: BackupRepository): UpdateEngine {
    val log: (String) -> Unit = { UpdateDiagnostics.record(app, it) }
    @Suppress("DEPRECATION") val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    return UpdateEngine(installed.longVersionCode, installed.versionName.orEmpty(), File(app.applicationInfo.sourceDir),
        File(app.filesDir, "update"), app.scope, AndroidUpdateStore(app), UpdateRemote(log = log),
        AndroidUpdatePlatform(app, PreUpdateBackup(app, app.database, backup, log)), log,
        reschedule = { enabled, preview -> UpdateWork.schedule(app, enabled, preview) })
}
