package app.resound.importer

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 077：导入录音按类型整理。界面（Claude）只依赖这里和 ImportContract；底层（Codex）实现。改签名需双方确认。
 */

enum class ImportRecordStatus { Transcribing, Summarizing, Done, Failed }

/** 一份导入的录音（一个文件一条），时间线上显示为一张卡，点开看纪要 + 整段原话 */
data class ImportRecord(
    val id: Long,
    val fileName: String,
    val kind: ImportKind,
    val startAt: Long,
    val endAt: Long,
    val status: ImportRecordStatus,
    /** 失败原因（给人看） */
    val error: String?,
    /** 纪要（技能卡格式，Normal 为 null）；可用现有技能卡界面显示、分享 */
    val cardId: Long?,
    val segmentIds: List<Long>,
    /** 双声道按声道分人 */
    val byChannel: Boolean,
)

interface ImportKinds {
    /** 给待确认的导入算预选：通话录音文件夹/文件名、双声道 → 通话；否则上一次选的；都没有 → 会议 */
    suspend fun suggest(pendingId: String): KindSuggestion
    val lastKind: StateFlow<ImportKind?>

    /** 带类型开始导入（同时记住这次的类型）；asr 同 ImportContract */
    fun start(pendingId: String, startAt: Long, asr: app.resound.transcribe.TranscribeSource?, kind: ImportKind)

    /** 一次选的多个文件：[merge] 为 true 时首尾相接（间隔 ≤5 分钟）的合成同一条记录，否则各自一条 */
    fun startBatch(pendingIds: List<String>, startAts: List<Long>, asr: app.resound.transcribe.TranscribeSource?, kind: ImportKind, merge: Boolean)

    fun records(date: LocalDate): Flow<List<ImportRecord>>
    fun record(id: Long): Flow<ImportRecord?>
    /** 改类型：按新类型重新区分说话人（如需）并重新生成纪要；旧纪要卡删除 */
    fun changeKind(recordId: Long, kind: ImportKind)
    /** 不改类型，重新生成纪要 */
    fun regenerate(recordId: Long)
}
