package app.resound.envsound

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 070 环境音识别：界面与底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。只分析已录下的人声片段（录音逻辑不变），在转写前判断一次。
 */

/** 跳过纯音乐和环境音：关 / 只标记（照常转写，只在原话页标出“可跳过”）/ 开（不转写） */
enum class SkipMode(val title: String) { Off("关"), MarkOnly("只标记"), On("开") }

/** 疑似影视外放（有人声 + 强配乐 + 没认出“我”） */
enum class MediaMode(val title: String) { Keep("照常记录"), MarkMedia("标记为媒体"), SkipTranscribe("不转写") }

data class EnvSoundSettings(
    /** 总开关；打开后才下载模型 */
    val enabled: Boolean = false,
    val skip: SkipMode = SkipMode.MarkOnly,
    val media: MediaMode = MediaMode.Keep,
    /** 在事件里记下环境（标签） */
    val tags: Boolean = true,
    /** 记录对话中的特别声音 */
    val specialSounds: Boolean = true,
    /** 在摘要、回顾和卡片里显示环境；关掉后数据照存、搜索照用 */
    val showInSummaries: Boolean = true,
)

/** 已验证的 9 类环境标签（title 即给用户看的写法） */
enum class EnvTag(val title: String) {
    Music("音乐"), Keyboard("键盘声"), Rain("雨"), Pet("宠物"), Water("流水"),
    Appliance("家电"), Footsteps("脚步"), AlarmClock("闹钟"), Traffic("附近有车流"),
}

/** 对话中出现的特别声音：只记录、不提醒 */
enum class SpecialSound(val title: String) { BabyCry("婴儿哭声"), Knock("敲门"), DogBark("狗叫"), Siren("警笛") }

/** 一段录音的判断结果 */
enum class SegmentVerdict {
    /** 正常（含人声），照常转写 */
    Keep,
    /** 只标记模式下判为可跳过：仍转写，原话页标出 */
    Skippable,
    /** 开模式下已跳过：未转写，可找回 */
    Skipped,
    /** 疑似影视外放（按 MediaMode 处理：照常 / 标记为媒体 / 不转写） */
    Media,
}

data class SegmentSound(
    val segmentId: Long,
    val startAt: Long,
    val endAt: Long,
    val verdict: SegmentVerdict,
    /** 给用户看的原因，如“音乐”“环境音”“疑似影视”；Keep 为 null */
    val reason: String?,
    val tags: Set<EnvTag>,
    val sounds: Set<SpecialSound>,
    /** 用户点过“转写这段”找回 */
    val restored: Boolean = false,
    /** 用户点过“判断错了” */
    val reportedWrong: Boolean = false,
    /** 录音文件还在（保留期内）才能试听、找回 */
    val audioAvailable: Boolean,
)

/** 一件事期间（按时间范围合并其录音段）出现的环境 */
data class EventSounds(val eventId: Long, val tags: Set<EnvTag>, val sounds: Set<SpecialSound>, val media: Boolean)

/** 某小时说话时的主要环境，给节奏色带用；没有录音的小时不出现 */
data class HourSound(val hour: Int, val tag: EnvTag?, val sounds: Set<SpecialSound>, val speechMinutes: Int)

/** 只标记/开 模式的统计，如“过去 7 天标记 86 段，你找回 0 段，约省 42 分钟转写” */
data class EnvStats(val days: Int, val marked: Int, val restored: Int, val reportedWrong: Int, val savedMinutes: Int)

sealed interface EnvModelState {
    data object NotDownloaded : EnvModelState
    data class Downloading(val progress: Float) : EnvModelState
    data object Ready : EnvModelState
    data class Failed(val message: String) : EnvModelState
}

interface EnvSoundRepository {
    val settings: StateFlow<EnvSoundSettings>
    fun update(settings: EnvSoundSettings)

    val model: StateFlow<EnvModelState>
    /** 打开总开关时界面调用；已下载则立即 Ready */
    fun downloadModel()

    /** 某天被判为 可跳过 / 已跳过 / 疑似影视 的录音段（原话页“已跳过 N 段”用），按时间排 */
    fun flagged(date: LocalDate): Flow<List<SegmentSound>>

    /** 某天各件事的环境；没有记录的事不在表里 */
    fun eventSounds(date: LocalDate): Flow<Map<Long, EventSounds>>

    /** 带某个标签 / 特别声音的事件 id，新的在前（点标签看同类） */
    fun eventsWith(tag: EnvTag?, sound: SpecialSound?): Flow<List<Long>>

    fun hourly(date: LocalDate): Flow<List<HourSound>>

    fun stats(days: Int = 7): Flow<EnvStats>

    /** 找回：把这段送去转写（Skipped / 不转写的 Media）；保留期过了返回失败 */
    suspend fun restore(segmentId: Long): Result<Unit>

    /** 判断错了：本机记下这段的分数用于以后调阈值；若是 Skippable/Skipped 同时视为 Keep */
    fun reportWrong(segmentId: Long)

    /** 试听用：录音文件还在就返回，过期为 null */
    fun audio(segmentId: Long): File?
}
