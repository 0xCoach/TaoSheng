package app.resound.voiceprint

import kotlinx.coroutines.flow.StateFlow

/*
 * 声纹（认出“我”）与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 *
 * 认出的结果写在转写句的 speakerName（"我"）和 speakerScore（0..1 相似度）上；
 * 界面按 speakerScore 显示“我”或“可能是我？”。
 */

data class VoiceprintState(
    /** 已录入的“我”的样本条数 */
    val samples: Int,
    /** 样本总时长（秒） */
    val seconds: Int,
    /** 样本够不够用来认人（总时长 ≥ 30 秒且 ≥ 3 条） */
    val ready: Boolean,
    /** 已比对过声纹的录音段数 / 还在排队的段数 */
    val processedSegments: Int,
    val pendingSegments: Int,
    val lastError: String?,
)

data class VoiceprintPerson(val id: Long, val name: String, val samples: Int, val seconds: Int, val ready: Boolean)

interface VoiceprintRepository {
    val people: StateFlow<List<VoiceprintPerson>>
    suspend fun addPerson(name: String): Result<Long>
    fun renamePerson(id: Long, name: String)
    fun deletePerson(id: Long)
    suspend fun nameAndAssign(lineId: Long, name: String): Result<Unit>
    fun assignPerson(lineId: Long, personId: Long)
    fun clearIdentity(lineId: Long)
    suspend fun enrollPerson(personId: Long, pcm: ShortArray): Result<Unit>

    val state: StateFlow<VoiceprintState>

    /** 用户确认“这句是我说的”：把这句（连同同一段录音里同一编号的其他句子）的声音加为“我”的样本，并重新比对 */
    fun markMe(lineId: Long)

    /** 用户纠正“这句不是我”：清掉这句（及同组）的“我”，以后不再自动标成我 */
    fun markNotMe(lineId: Long)

    /** 删除全部“我”的样本和已有的认人结果 */
    fun reset()

    /** 用录音机界面录下的一段音频作为样本（录入流程用，PCM16 单声道 16k） */
    suspend fun enrollPcm(pcm: ShortArray): Result<Unit>
}

/** 判断阈值。10-23 真机：本人约 0.40–0.89（多数 ≥0.6），电脑合成音和视频 ≤0.25；样本还少，之后按“不是我”的纠正再调 */
object VoiceprintThresholds {
    /** 不低于这个分数，直接显示“我” */
    const val SURE = 0.65f

    /** 介于 MAYBE 与 SURE 之间，显示“可能是我？” */
    const val MAYBE = 0.45f
}
