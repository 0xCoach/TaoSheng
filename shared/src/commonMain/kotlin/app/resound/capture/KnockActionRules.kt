package app.resound.capture

/**
 * 敲敲手势 → 录音命令 的纯判定：隐私暂停是硬锁，任何手势都不能解除；
 * 只有"已录中"才暂停，"手动暂停"才恢复，"未录"才启动。
 */
object KnockActionRules {

    enum class Action { Start, Resume, Pause, Blocked }

    fun decide(serviceAlive: Boolean, enabled: Boolean, manualPaused: Boolean, privacyPaused: Boolean): Action = when {
        // 隐私暂停：敲敲一律无效（恢复只能走 App 内明确的隐私开关）。
        privacyPaused -> Action.Blocked
        !serviceAlive || !enabled -> Action.Start
        manualPaused -> Action.Resume
        else -> Action.Pause
    }
}
