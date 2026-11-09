package app.resound.importer

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 117 文件夹自动导入：界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 *
 * 用户选一个或几个文件夹（比如录音笔 App 存录音的地方），涛声定时看一眼，
 * 有新录音就自动导入（开始时间取文件里的录制时间），走和“分享导入”同一条转写整理流程。
 * 只读不删：源文件永远不动。同一个文件只导入一次（改名、挪位置也认得出）。
 */

data class WatchedFolder(
    /** 系统文件选择器返回的地址（字符串形式），也是这一项的 id */
    val id: String,
    /** 给人看的名字，如“Recordings/录音笔” */
    val name: String,
    /** 这个文件夹里的录音按什么类型整理 */
    val kind: ImportKind,
    /** 暂停后不再自动导入，但记录保留 */
    val enabled: Boolean,
    /** 授权丢了（用户在系统里撤销、文件夹被删）为 false，界面提示重新选 */
    val accessible: Boolean,
    /** 上次看这个文件夹的时间；还没看过为 null */
    val lastScanAt: Long?,
    /** 从这个文件夹累计导入了几个文件 */
    val imported: Int,
    /** 最近一次出错的说明（给人看的）；没出错为 null */
    val lastError: String? = null,
)

data class FolderImportState(
    val folders: List<WatchedFolder>,
    /** 正在看文件夹或正在导入 */
    val running: Boolean,
    /** 最近一次自动导入了几个新文件、什么时候（给首页/设置页一句话提示）；没有为 null */
    val lastImported: Int? = null,
    val lastImportedAt: Long? = null,
)

interface FolderImports {
    val state: StateFlow<FolderImportState>

    /**
     * 加一个文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的地址），要长期保留读权限。
     * importExisting=false：只导入加入之后新出现的文件（默认，避免一下导入几百个旧录音）；true：连已有的一起导入。
     */
    fun add(tree: Uri, kind: ImportKind, importExisting: Boolean = false)

    fun remove(id: String)

    fun setKind(id: String, kind: ImportKind)

    fun setEnabled(id: String, enabled: Boolean)

    /** 立即看一遍所有文件夹 */
    fun scanNow()
}
