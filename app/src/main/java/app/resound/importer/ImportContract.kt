package app.resound.importer

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/*
 * 导入录音与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。
 *
 * 最常见的用法：在录音机等 App 里点“分享 → 回响”，把音频推过来转写和整理。
 * 回响不保存这些录音：只在转写期间临时保留，转写成功后立即删除。
 */

/** 文件信息：名字、时长、建议的开始时间 */
data class ImportInfo(
    val displayName: String,
    val durationMillis: Long,
    /** 从文件里读到的录制时间（元数据或修改时间）；读不到为 null，界面让用户自己选 */
    val suggestedStartAt: Long?,
)

/** 收到但还没开始的导入（等用户确认开始时间） */
data class PendingImport(val id: String, val info: ImportInfo)

/** 导入进度：解码 → 切段 → 入库；之后片段进入正常转写队列 */
data class ImportProgress(
    val running: Boolean,
    val fileName: String?,
    val percent: Int,
    /** 排在后面还没开始的文件数 */
    val queued: Int,
    val segments: Int,
    val error: String?,
)

interface AudioImporter {
    /**
     * 收到文件（别的 App 分享过来，或在回响里选的）：立刻复制到缓存（分享来的读权限是临时的），读出信息，
     * 放进 [pending] 等用户确认。读不出声音的返回失败（给人看的原因），不进 pending。
     */
    suspend fun receive(uris: List<Uri>): List<Result<PendingImport>>

    /** 等确认的导入（界面看到不为空就弹确认面板） */
    val pending: StateFlow<List<PendingImport>>

    /** 用户确认开始时间后开始；多个文件排队依次导入 */
    fun start(id: String, startAt: Long)

    /** 卡 071：指定这次用哪种方式转写（null = 设置里当前用的）；只对这次导入生效，不改设置 */
    fun start(id: String, startAt: Long, asr: app.resound.transcribe.TranscribeSource?) = start(id, startAt)

    /** 不导入了：删掉缓存副本 */
    fun discard(id: String)

    val progress: StateFlow<ImportProgress>
}
