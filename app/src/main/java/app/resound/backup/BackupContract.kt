package app.resound.backup

import kotlinx.coroutines.flow.StateFlow

/*
 * 坚果云（WebDAV）备份与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。
 */

data class BackupState(
    val enabled: Boolean,
    /** 已填账号（邮箱）；没填为 null。应用密码不回显 */
    val account: String?,
    val hasPassword: Boolean,
    val running: Boolean,
    val lastBackupAt: Long?,
    /** 最近一次备份大小（字节） */
    val lastBackupBytes: Long?,
    /** 云端现有备份份数 */
    val remoteCount: Int?,
    val lastError: String?,
    /** 备份加密已开启 */
    val encrypted: Boolean = false,
    /** 云端保留最近几个恢复点（每晚一个）：3 / 7 / 30，默认 7；被保留的增量所依赖的完整备份也会留着（只清理 Resound-Backup 目录） */
    val keep: Int = 7,
)

interface BackupRepository {
    val state: StateFlow<BackupState>

    /** 保存坚果云账号和“第三方应用密码”（不是登录密码）；密码只进系统安全区 */
    fun saveCredentials(account: String, appPassword: String)

    fun clearCredentials()

    fun setEnabled(enabled: Boolean)

    /** 云端保留份数：3、7 或 30；下次备份时清理多出的旧备份 */
    fun setKeep(count: Int) {}

    /** 立即备份一次 */
    fun backupNow()

    /** 测试能否连上并有写入权限；成功返回简短说明 */
    suspend fun test(): Result<String>

    /** 云端的备份（新的在前） */
    suspend fun remoteBackups(): Result<List<RemoteBackup>> = Result.success(emptyList())

    /**
     * 用某一份备份恢复：先下载校验，再替换本机数据库和设置；成功后 App 自动重启。
     * 恢复前会先把本机当前数据做一份本地快照（保留到下次成功备份）。密钥类不在备份里，恢复后需重填。
     */
    suspend fun restore(name: String, passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** 备份加密：设了口令就加密之后的备份（AES，口令不上传、忘了无法恢复）；null 关闭。云端和本机共用 */
    fun setEncryption(passphrase: String?) {}

    // ---------- 本机备份 ----------

    val local: StateFlow<LocalBackupState>
        get() = kotlinx.coroutines.flow.MutableStateFlow(LocalBackupState(null, false, 3, false, null, null, null, null))

    /** 用户在系统选择器里选的文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的地址），要长期保留访问权限 */
    fun setLocalFolder(tree: android.net.Uri) {}

    fun setLocalAuto(enabled: Boolean) {}

    /** 只保留最新几份：1、3 或 7 */
    fun setLocalKeep(count: Int) {}

    /** 立即备份一份到本机文件夹 */
    fun backupLocalNow() {}

    /** 本机文件夹里的备份（新的在前） */
    suspend fun localBackups(): Result<List<RemoteBackup>> = Result.success(emptyList())

    /** 用本机文件夹里的某一份恢复（流程同云端恢复） */
    suspend fun restoreLocal(name: String, passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** 另存一份到用户选的位置（ACTION_CREATE_DOCUMENT 返回的地址），比如发给别的手机 */
    suspend fun exportTo(target: android.net.Uri): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** 从用户选的任意备份文件恢复（ACTION_OPEN_DOCUMENT 返回的地址） */
    suspend fun restoreFrom(source: android.net.Uri, passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())
}

data class RemoteBackup(
    val name: String, val at: Long, val bytes: Long, val encrypted: Boolean,
    /** 完整备份为 true；增量（只含相对本周完整备份变化的部分）为 false */
    val full: Boolean = true,
    /** 增量所依赖的那份完整备份的文件名；完整备份为 null。恢复增量时会自动连同它一起下载 */
    val baseName: String? = null,
)

/** 本机备份：存到手机里用户选的文件夹（系统文件选择器授权），不用坚果云也能备份和恢复 */
data class LocalBackupState(
    /** 选的文件夹（给人看的名字，如“Download/Resound”）；还没选为 null */
    val folder: String?,
    /** 每晚自动备份到这个文件夹 */
    val auto: Boolean,
    /** 保留最近几个恢复点（1 / 3 / 7），多的自动删；被保留的增量所依赖的完整备份也会留着 */
    val keep: Int,
    val running: Boolean,
    val lastAt: Long?,
    val lastBytes: Long?,
    /** 文件夹里现有的备份份数 */
    val count: Int?,
    val lastError: String?,
)
