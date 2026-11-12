package app.resound.onboarding

import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 132：新手上手（开箱向导 + 按需提示）的底层信号。界面（Claude）只依赖这里；底层（Codex）实现。
 * 全部只在手机本地，不上传。
 */

/** 按需提示：到了合适时机才出现，用户关掉后不再出现 */
enum class OnboardingTip {
    /** 涛声第一次被系统关掉过（收到过“已停止，点此恢复”那类中断）→ 建议设置自启动等后台保活 */
    KeepAlive,
    /** 没开区分说话人，但最近的录音里明显有多个人在说话 → 建议打开区分说话人 */
    Speakers,
    /** 用了 3 天以上还没有任何备份（本机或云端都没有成功过）→ 建议设置备份 */
    Backup,
}

interface Onboarding {
    /** 装好（或开箱向导完成）后第一句转写出来的时间；还没有为 null */
    val firstTranscriptAt: StateFlow<Long?>

    /** 当前该显示的按需提示（已关掉的不含），按重要程度排 */
    val tips: StateFlow<List<OnboardingTip>>

    fun dismiss(tip: OnboardingTip)

    /**
     * 记一笔本地统计（只计数和耗时，不含内容）：如 "wizard_shown"、"mic_granted"、"mic_denied"、
     * "key_saved"、"key_skipped"、"key_from_clipboard"、"wizard_done"。重复调用累加次数。
     */
    fun record(event: String)

    /** 开箱向导完成（或跳过）的时间，用于算“首次转写用了多久”；问题记录里导出这些统计 */
    fun wizardFinished()
}
