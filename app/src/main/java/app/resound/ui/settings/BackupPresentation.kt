package app.resound.ui.settings

import app.resound.backup.BackupRepository
import app.resound.backup.BackupSummary
import app.resound.backup.RemoteBackup
import java.util.WeakHashMap

internal data class BackupSuccess(val at: Long?, val uploadedBytes: Long?)

/** 本次成功可能比尚未刷新的概况更新；时间与传输大小必须来自同一次成功。 */
internal fun latestBackupSuccess(summary: BackupSummary?, at: Long?, bytes: Long?): BackupSuccess =
    if (summary?.lastAt != null && (at == null || summary.lastAt >= at))
        BackupSuccess(summary.lastAt, summary.lastUploadBytes)
    else BackupSuccess(at, bytes)

internal fun backupHeadline(local: Boolean, configured: Boolean, running: Boolean, successAt: Long?, error: String?): String = when {
    running -> "正在备份…"
    successAt != null -> "最近成功备份"
    error != null -> "尚无成功备份"
    configured -> "还没备份过"
    local -> "还没选备份文件夹"
    else -> "还没连接坚果云"
}

/** 只保留读成功的结果；暂时断网不能把已有概况清成空白。成功读到空内容则正常清空。 */
internal class BackupReadSnapshot {
    var summary: BackupSummary? = null
        private set
    var backups: Result<List<RemoteBackup>>? = null
        private set
    fun acceptSummary(result: Result<BackupSummary?>) { result.onSuccess { summary = it } }
    fun acceptBackups(result: Result<List<RemoteBackup>>) { result.onSuccess { backups = result } }
}

internal class BackupPageCache {
    private var cloudKey: Pair<String?, Boolean>? = null
    private var cloud = BackupReadSnapshot()
    private var localKey: String? = null
    private var local = BackupReadSnapshot()
    fun cloud(account: String?, configured: Boolean): BackupReadSnapshot {
        val key = account to configured
        if (cloudKey != key) { cloudKey = key; cloud = BackupReadSnapshot() }
        return cloud
    }
    fun local(folder: String?): BackupReadSnapshot {
        if (localKey != folder) { localKey = folder; local = BackupReadSnapshot() }
        return local
    }
    // 系统选择器可能返回同名但不同 URI 的文件夹，也必须重新加载。
    fun invalidateLocal() { local = BackupReadSnapshot() }
    companion object {
        private val instances = WeakHashMap<BackupRepository, BackupPageCache>()
        fun forRepository(repo: BackupRepository) = instances.getOrPut(repo) { BackupPageCache() }
    }
}
