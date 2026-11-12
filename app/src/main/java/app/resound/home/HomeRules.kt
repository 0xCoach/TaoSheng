package app.resound.home

/*
 * 卡 148：首页主卡的纯规则（随改随搬，为 iOS 复用）。
 * 不引用 android.* / java.*；时间一律由参数传入。界面只负责照着画。
 */

/** 带单位数字的一段：“2小时13分”拆成 2 / 小时 / 13 / 分，界面数字大、单位小 */
data class NumberPart(val text: String, val isUnit: Boolean)

/** 主卡关心的录音状态（由界面从 CaptureState 映射过来） */
enum class HomeCapture { Active, Paused, InCall, MicTaken, Stopped, NoPermission }

/** 主卡状态行右侧放哪些按钮 */
enum class HomeControls {
    /** 「◉ 专题」+ 暂停键 */
    TopicAndPause,
    /** 「◉ 专题」+ 恢复键（实心，更醒目） */
    TopicAndResume,
    /** 只有「开始聆听」 */
    StartListening,
    /** 只有「去开启」（缺权限） */
    GrantPermission,
    /** 不放按钮（通话中，系统占着麦克风） */
    None,
    /** 专题进行中：★ 记一下 + 暂停/继续 + 结束 */
    TopicRunning,
}

/** 「模型」按钮：显示当前服务商名，点了去整理模型页 */
data class ModelButton(val label: String, val emphasize: Boolean, val alertDot: Boolean)

object HomeRules {
    /** “2小时13分”“38分钟”“45秒”，拆成数字和单位 */
    fun speechParts(ms: Long): List<NumberPart> {
        val m = ms / 60_000
        return when {
            m >= 60 -> listOf(NumberPart("${m / 60}", false), NumberPart("小时", true), NumberPart("${m % 60}", false), NumberPart("分", true))
            m >= 1 -> listOf(NumberPart("$m", false), NumberPart("分钟", true))
            else -> listOf(NumberPart("${(ms / 1000).coerceAtLeast(0)}", false), NumberPart("秒", true))
        }
    }

    fun controls(capture: HomeCapture, topicRunning: Boolean): HomeControls = when {
        topicRunning -> HomeControls.TopicRunning
        else -> when (capture) {
            HomeCapture.Active, HomeCapture.MicTaken -> HomeControls.TopicAndPause
            HomeCapture.Paused -> HomeControls.TopicAndResume
            HomeCapture.Stopped -> HomeControls.StartListening
            HomeCapture.NoPermission -> HomeControls.GrantPermission
            HomeCapture.InCall -> HomeControls.None
        }
    }

    /** 服务商名超过 [maxChars] 个字截断；没设模型时写“设置模型”并强调；上次整理因余额/密钥失败时加小红点 */
    fun modelButton(providerName: String?, hasKey: Boolean, lastRunFailed: Boolean, maxChars: Int = 8): ModelButton {
        if (!hasKey || providerName.isNullOrBlank()) return ModelButton("设置模型", emphasize = true, alertDot = false)
        val name = providerName.trim().let { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
        return ModelButton(name, emphasize = false, alertDot = lastRunFailed)
    }

    /** 时间线上的时间是否用橙色：只给标了星的、刚发生的（结束不到 [recentMinutes] 分钟） */
    fun timeAccent(starred: Boolean, endAt: Long, now: Long, recentMinutes: Int = 30): Boolean =
        starred || (now - endAt in 0..recentMinutes * 60_000L)

    /** 暂停时长的显示：“30 分钟”“1 小时” */
    fun pauseLabel(minutes: Int): String = if (minutes < 60) "$minutes 分钟" else if (minutes % 60 == 0) "${minutes / 60} 小时" else "${minutes / 60} 小时 ${minutes % 60} 分"
}
