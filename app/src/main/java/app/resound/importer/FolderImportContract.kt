package app.resound.importer

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 117 文件夹自动导入：界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 *
 * 用户选一个或几个文件夹（比如录音笔 App 存录音的地方），声声不息定时看一眼，
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
    /** 卡 199：加入时选的“已有的录音怎么处理”；老数据视为 OnlyNew */
    val history: FolderHistory = FolderHistory.OnlyNew,
    /** 卡 199：从这个文件夹列出但还没转写的录音数 */
    val listed: Int = 0,
)

/** 卡 199：选完文件夹、确认加入前的预览，给“已有的录音怎么处理”四个选项配数字 */
data class FolderPreview(
    /** 文件夹显示名 */
    val name: String,
    /** 全部录音数和总时长 */
    val total: Int, val totalMillis: Long,
    val last7: Int, val last7Millis: Long,
    val last30: Int, val last30Millis: Long,
    /** 最早一个录音的开始时间；没有录音为 null */
    val oldestAt: Long?,
    /** 文件名里认出号码或名字的录音数（判断是不是通话录音文件夹） */
    val callLike: Int,
    /** 路径或文件名看起来是通话录音文件夹 */
    val looksLikeCalls: Boolean,
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

    /**
     * 卡 199：读一遍文件夹给出预览（会先拿长期读权限；读时长较慢，几百个文件几秒内）。
     * 拿不到权限或读不了抛异常，message 给人看。不入库、不导入。
     */
    suspend fun preview(tree: Uri): FolderPreview

    /** 卡 199：加文件夹并按 [history] 处理已有录音（转写的从新到旧排队；更早的列出）。importExisting 版本保留给老调用，true 视为 Days30 */
    fun add(tree: Uri, kind: ImportKind, history: FolderHistory)

    /** 卡 199：改已有文件夹的处理方式；只会多转写/多列出，已导入的不删，已列出的不撤 */
    fun setHistory(id: String, history: FolderHistory)

    fun remove(id: String)

    fun setKind(id: String, kind: ImportKind)

    fun setEnabled(id: String, enabled: Boolean)

    /** 立即看一遍所有文件夹 */
    fun scanNow()
}

/* ---------------- 卡 199：列出的录音、通话对方 ---------------- */

/** 通话对方：全部来自文件名，有名字用名字，没有用号码 */
data class CallParty(
    val number: String?,
    /** 文件名里写的名字 */
    val fileName: String?,
    val direction: CallDirection?,
) {
    val display: String? get() = fileName ?: number
}

enum class ListedState {
    /** 只列出，没转写 */
    Listed,
    /** 排队等转写 */
    Queued,
    /** 已导入（转写整理走导入记录） */
    Imported,
    /** 原文件不在了或授权丢了，转不了 */
    Missing,
}

/** 文件夹里被发现的一个录音（列出的、排队的、已导入的都在） */
data class FolderRecording(
    val id: Long,
    val folderId: String,
    val fileName: String,
    val startAt: Long,
    val durationMillis: Long,
    /** 这个文件夹类型是通话时才有 */
    val call: CallParty?,
    val state: ListedState,
    /** 已导入时对应的导入记录 id */
    val importRecordId: Long?,
    /** 卡 210：点“转写”失败的原因（如“文件夹需要重新授权”“暂不支持这种格式”）；没失败为 null */
    val problem: String? = null,
)

data class BackfillState(
    /** 补转（历史录音转写）还剩几个 */
    val remaining: Int,
    /** 这一轮补转一共几个 */
    val total: Int,
    val paused: Boolean,
)

interface FolderRecordings {
    /** 某段时间里的录音（时间线用：界面只画 Listed/Queued/Missing；Imported 的由导入记录自己画） */
    fun between(start: Long, end: Long): Flow<List<FolderRecording>>

    /** 某条导入记录对应的录音（导入记录卡片显示对方用）；不是文件夹导入的为 null */
    fun byImport(importRecordId: Long): Flow<FolderRecording?>

    /** 按对方名字/号码搜（号码可只输后几位），新的在前，最多 200 条 */
    fun search(query: String): Flow<List<FolderRecording>>


    /** 把列出的录音排进转写（用户点了“转写”）；插在补转前面 */
    fun transcribe(ids: List<Long>)

    val backfill: StateFlow<BackfillState>
    fun pauseBackfill(paused: Boolean)
}

