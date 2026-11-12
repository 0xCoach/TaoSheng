package app.resound.transcribe

import kotlinx.coroutines.flow.StateFlow

/** 卡 073：课堂模式当前状态；结束时刻持久化，重启后仍按时判断。 */
data class ClassModeState(
    val enabled: Boolean = false,
    /** 绝对时间（毫秒）；开启且为 null 表示直到手动关闭。 */
    val endsAtMillis: Long? = null,
)

/** 界面由 Claude 实现；通过 AppGraph.asrSpeed 使用本约定。 */
interface AsrSpeedSettings {
    val classMode: StateFlow<ClassModeState>

    /** 正数分钟；默认入口传 120，null 表示直到手动关闭。 */
    fun startClassMode(durationMinutes: Int?)

    fun stopClassMode()

    /** 长期总开关，默认开；课堂模式不改写此值。 */
    val separateSpeakers: StateFlow<Boolean>

    fun setSeparateSpeakers(on: Boolean)

    /**
     * 按录音段开始时刻的历史设置判断，包含当时的课堂模式。
     * 课堂模式区间为 [开启时刻, 结束时刻)，结束后再转写也不改变历史判定。
     * 返回 false 时不区分说话人、不认人，原话 speakerTag/name/score 均为空。
     */
    fun effectiveSeparateSpeakers(segmentStartMillis: Long): Boolean
}
