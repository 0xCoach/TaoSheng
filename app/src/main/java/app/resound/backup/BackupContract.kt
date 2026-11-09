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
    /** 云端保留最近几个恢复点（每次成功备份一个）：3 / 7 / 30，默认 7；保留点共用未变化的数据对象 */
    val keep: Int = 7,
    /** 卡 114：云端存储选的是哪一种（三选一）；老用户升级后为 Nutstore */
    val cloud: CloudKind = CloudKind.Nutstore,
    /** 自定义 WebDAV 的地址（cloud == WebDav 时有值） */
    val webDavUrl: String? = null,
    /** S3 的非密钥设置（cloud == S3 时有值）；account 存的是访问密钥 ID，hasPassword 表示已存私有访问密钥 */
    val s3: S3Settings? = null,
)

/** 卡 114：云端存储三选一 */
enum class CloudKind { Nutstore, WebDav, S3 }

/** S3 兼容存储的设置（不含私有访问密钥） */
data class S3Settings(
    /** 预填服务商 id（见 [S3Preset.id]），自己填的为 "custom" */
    val presetId: String,
    /** 服务地址，如 https://oss-cn-hangzhou.aliyuncs.com */
    val endpoint: String,
    /** 区域，如 cn-hangzhou；R2 填 auto */
    val region: String,
    val bucket: String,
    /** 桶里的目录前缀，默认 "resound/"，可为空 */
    val prefix: String = "resound/",
    /** true：地址/桶/对象（路径式）；false：桶.地址/对象（虚拟主机式） */
    val pathStyle: Boolean = false,
)

/** S3 预填服务商；界面据此预填，用户仍可改 */
data class S3Preset(
    val id: String,
    /** 给人看的名字，如“阿里云 OSS” */
    val title: String,
    /** 地址模板，{region} 由界面替换，如 https://oss-{region}.aliyuncs.com；R2 含 {accountId} */
    val endpointTemplate: String,
    /** 常用区域（第一个为默认），空表示让用户自己填 */
    val regions: List<String>,
    val pathStyle: Boolean,
    /** 一句话填写提示，如“访问密钥在 RAM 访问控制里创建” */
    val hint: String,
)

interface BackupRepository {
    val state: StateFlow<BackupState>

    /** 保存坚果云账号和“第三方应用密码”（不是登录密码）；密码只进系统安全区 */
    fun saveCredentials(account: String, appPassword: String)

    fun clearCredentials()

    /** 卡 114：S3 预填服务商列表（最后一个固定为 id="custom" 的“自定义”） */
    val s3Presets: List<S3Preset> get() = emptyList()

    /**
     * 卡 114：保存云端存储（三选一，覆盖之前的选择）。
     * - Nutstore：account=邮箱，secret=应用密码，其余忽略（等同 [saveCredentials]）
     * - WebDav：webDavUrl 必填（https，或局域网 http），account=用户名，secret=密码
     * - S3：s3 必填，account=访问密钥 ID，secret=私有访问密钥
     * secret 传 null 表示沿用已存的（只改地址等）。密钥只进系统安全区，并随备份一起备份、恢复。
     */
    fun saveCloud(kind: CloudKind, account: String, secret: String?, webDavUrl: String? = null, s3: S3Settings? = null) {
        if (kind == CloudKind.Nutstore && secret != null) saveCredentials(account, secret)
    }

    fun setEnabled(enabled: Boolean)

    /** 云端保留份数：3、7 或 30；下次备份时清理多出的旧备份 */
    fun setKeep(count: Int) {}

    /** 立即备份一次 */
    fun backupNow()

    /** 测试能否连上并有写入权限；成功返回简短说明 */
    suspend fun test(): Result<String>

    /** 云端的历史恢复点（新的在前） */
    suspend fun remoteBackups(): Result<List<RemoteBackup>> = Result.success(emptyList())

    /**
     * 用某一份备份恢复：先下载校验，再替换本机数据库和设置；成功后 App 自动重启。
     * 恢复前会先把本机当前数据做一份本地快照（保留到下次成功备份）。配置与密钥一并恢复；备份里没有的配置保留本机已有值。
     */
    suspend fun restore(name: String, passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** 备份加密：设了口令就加密之后的备份（AES，口令不上传、忘了无法恢复）；null 关闭。云端和本机共用 */
    fun setEncryption(passphrase: String?) {}

    // ---------- 按天分卷（卡 037）：恢复全部 ----------

    /** 云端按天分卷的概况；还没有分卷备份时为 null */
    suspend fun cloudSummary(): Result<BackupSummary?> = Result.success(null)

    /** 用云端全部分卷恢复：记录、设置、密钥、账号全部还原；成功后 App 自动重启 */
    suspend fun restoreAllFromCloud(passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** 本机文件夹里按天分卷的概况 */
    suspend fun localSummary(): Result<BackupSummary?> = Result.success(null)

    /** 用本机文件夹里的全部分卷恢复 */
    suspend fun restoreAllFromLocal(passphrase: String? = null): Result<Unit> = Result.failure(UnsupportedOperationException())

    // ---------- 本机备份 ----------

    val local: StateFlow<LocalBackupState>
        get() = kotlinx.coroutines.flow.MutableStateFlow(LocalBackupState(null, false, 7, false, null, null, null, null))

    /** 用户在系统选择器里选的文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的地址），要长期保留访问权限 */
    fun setLocalFolder(tree: android.net.Uri) {}

    /**
     * 卡 123：不用系统文件夹选择器，直接存到“下载/涛声备份/”（MediaStore，不需要存储权限）。
     * 给选文件夹那一套用不了的手机（如鸿蒙的安卓兼容层）；之后的自动备份、保留份数、恢复照常。
     * 选了它就替换掉之前选的文件夹；[LocalBackupState.folder] 显示“下载/涛声备份”。
     */
    fun useDownloadsFolder() {}

    fun setLocalAuto(enabled: Boolean) {}

    /** 只保留最新几份：3、7 或 30，默认 7（兼容旧配置 1 份） */
    fun setLocalKeep(count: Int) {}

    /** 立即备份一份到本机文件夹 */
    fun backupLocalNow() {}

    /** 本机文件夹里的历史恢复点（新的在前） */
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

/**
 * 按天分卷备份的概况（卡 037）：云端或本机文件夹里一共存了多少天的记录。
 * 每天一个分卷 + 一个“当前状态”分卷（设置、密钥、账号、技能、人物等），每晚只传变了的分卷。
 */
data class BackupSummary(
    /** 存了多少天的记录 */
    val days: Int,
    /** 全部分卷合计大小（字节） */
    val bytes: Long,
    /** 最近一次成功备份的时间 */
    val lastAt: Long?,
    /** 上一次备份传了多大（字节） */
    val lastUploadBytes: Long?,
    /** 备份里含密钥和账号 */
    val includesSecrets: Boolean,
    /** 分卷是加密的 */
    val encrypted: Boolean,
)

/** 本机备份：存到手机里用户选的文件夹（系统文件选择器授权），不用坚果云也能备份和恢复 */
data class LocalBackupState(
    /** 选的文件夹（给人看的名字，如“Download/Resound”）；还没选为 null */
    val folder: String?,
    /** 每晚自动备份到这个文件夹 */
    val auto: Boolean,
    /** 保留最近几个恢复点（3 / 7 / 30，默认 7），多的自动删；保留点共用未变化的数据对象 */
    val keep: Int,
    val running: Boolean,
    val lastAt: Long?,
    val lastBytes: Long?,
    /** 文件夹里现有的备份份数 */
    val count: Int?,
    val lastError: String?,
    /** 已保存授权丢失/探测被拒绝为 NeedsReauthorize；位置不支持写入为 Unsupported。 */
    val folderAccess: LocalFolderAccess? = null,
    /** 仅本次进程可用；重启后必须重新选择文件夹。 */
    val sessionOnly: Boolean = false,
)

/** null 状态表示尚未选文件夹；Ok 也可能仅为当前进程授权，见 sessionOnly。 */
enum class LocalFolderAccess { Ok, NeedsReauthorize, Unsupported }
