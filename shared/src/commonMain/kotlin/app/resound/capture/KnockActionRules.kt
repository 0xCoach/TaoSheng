package app.resound.capture

/**
 * 敲敲手势 → 录音命令 的纯判定：隐私暂停是硬锁，任何手势都不能解除；
 * 只有"已录中"才暂停，"手动暂停"才恢复，"未录"才启动。
 * 专题暂停时同样拦截：恢复要走"继续专题"，避免敲敲再叠一层手动暂停。
 */
object KnockActionRules {

    enum class Action { Start, Resume, Pause, Blocked }

    fun decide(serviceAlive: Boolean, enabled: Boolean, manualPaused: Boolean, privacyPaused: Boolean,
        topicPaused: Boolean = false): Action = when {
        // 隐私暂停：敲敲一律无效（恢复只能走 App 内明确的隐私开关）。
        privacyPaused -> Action.Blocked
        // 专题暂停：录音随专题冻结，敲敲不再叠加手动暂停、也不单独解冻。
        topicPaused -> Action.Blocked
        !serviceAlive || !enabled -> Action.Start
        manualPaused -> Action.Resume
        else -> Action.Pause
    }
}
