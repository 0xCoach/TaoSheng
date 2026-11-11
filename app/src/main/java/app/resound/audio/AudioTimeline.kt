package app.resound.audio

/**
 * 音源可选实现的“时间与来源”接口（卡 143，源自 PR #30 的 AudioTimeline）。
 *
 * 录音服务每收到一帧后读 [frameEpochMillis] / [frameArchiveId]（都是“刚刚发出的那一帧”的值），
 * 人声检测处理完这一帧后调用 [processed]，本次收音结束时调用 [finished]。
 * 没实现这个接口的音源（手机麦克风、普通外接麦）照旧用手机时间和音源编号。
 *
 * 平台无关：不引用 android.* / java.*（以后随录音链路搬进共用模块）。
 */
interface AudioTimeline {
    /** 刚发出的这一帧第一个采样的真实时间（UTC 毫秒）；null 表示用手机收到的时间 */
    val frameEpochMillis: Long?
    /** 刚发出的这一帧对应的存档来源；null 表示用音源编号 */
    val frameArchiveId: String?
    /** 人声检测已处理完刚发出的这一帧，共 [samples] 个采样 */
    fun processed(samples: Int)
    /** 本次收音结束；[discarded]=true 表示最后一段被丢弃 */
    fun finished(discarded: Boolean)
}
