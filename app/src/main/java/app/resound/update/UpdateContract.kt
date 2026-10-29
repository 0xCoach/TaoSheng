package app.resound.update

import kotlinx.coroutines.flow.StateFlow

/**
 * 卡 051 · 应用内更新的约定（Claude 定，Codex 实现，签名不要自行修改）。
 *
 * 只更新正式版；先查魔搭，失败再查 GitHub。有新版先通知，用户点了才下载（流量下也照样下），
 * 下载好后用户点了才安装；安装前先做一次数据备份。能增量就下差异包，拼不成自动改下整包。
 */
interface AppUpdater {
    /** 当前装着的版本，如 "0.2.10" */
    val currentVersion: String
    val state: StateFlow<UpdateState>

    /** 查一次新版本。manual = 用户在更新页点了“检查更新”（失败要显示原因；自动检查失败静默） */
    fun check(manual: Boolean)

    /** 用户点了“下载”：优先差异包，失败改整包；断点续传 */
    fun download()

    /** 用户点了“安装”：先备份，再交给系统安装 */
    fun install()

    /** 用户点了“稍后”：这个版本今天不再通知 */
    fun later()

    /** 用户点了“跳过这个版本”：自动检查和通知都不再提这一版，手动检查仍会显示 */
    fun skip()

    /** 自动检查（每 6 小时 + 打开 App 时）；关掉后只在手动点“检查更新”时查 */
    val autoCheck: StateFlow<Boolean>
    fun setAutoCheck(enabled: Boolean)

    /** 系统设置里是否已允许涛声安装应用；false 时界面引导用户去打开 */
    fun canInstall(): Boolean

    // ---- 卡 052：预览版通道 ----

    /** 是否接收预览版。开着时正式版、预览版都查，取版本号大的；关掉后只等下一个比当前大的正式版 */
    val previewChannel: StateFlow<Boolean> get() = NO_PREVIEW
    fun setPreviewChannel(enabled: Boolean) {}

    /** 当前装着的是预览版（版本名形如 0.2.12-preview.1；界面显示为“0.2.12 预览版 1”） */
    val isPreviewBuild: Boolean get() = false
}

private val NO_PREVIEW: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

/** 版本说明文件里给用户看的部分 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** 更新内容，多行中文 */
    val notes: String,
    /** 这次实际要下载的字节数（增量时是差异包大小） */
    val downloadBytes: Long,
    val incremental: Boolean,
    /** 卡 052：这一版是预览版 */
    val preview: Boolean = false,
)

sealed interface UpdateState {
    /** 还没查过 */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val doneBytes: Long, val totalBytes: Long) : UpdateState
    /** 差异包已下好，正在和手机里的安装包拼成新版 */
    data class Assembling(val info: UpdateInfo) : UpdateState
    /** 下载好、校验通过，等用户点安装 */
    data class Ready(val info: UpdateInfo) : UpdateState
    /** 正在备份或已交给系统安装 */
    /** [step]/[progress]：备份时正在做哪一步、整体进度 0..1（只在 backingUp 时有） */
    data class Installing(val info: UpdateInfo, val backingUp: Boolean, val step: String? = null, val progress: Float? = null) : UpdateState
    /** message 是给用户看的一句话，只说结果（如“网络不通，稍后再试”“手机空间不够，还差 120 MB”） */
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/** 通知点开后跳到更新页：MainActivity 收到这个 extra 就打开设置 → 版本更新 */
const val EXTRA_OPEN_UPDATE = "app.resound.open_update"
