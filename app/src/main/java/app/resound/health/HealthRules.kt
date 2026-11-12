package app.resound.health

import app.resound.quick.ListenState
import kotlin.math.roundToLong

/** 时刻均为 epoch 毫秒；本地自然日由调用方按设备时区换成 epochDay，兼容夏令时。 */
data class HealthInputs(
    val now: Long,
    val today: Long,
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val listen: ListenState,
    val inQuietPeriod: Boolean,
    val transcriptReady: Boolean,
    val transcriptFailures: Int,
    val modelReady: Boolean,
    val providerName: String,
    val balance: Double?,
    val balanceAvailable: Boolean?,
    val balanceCurrency: String,
    val balanceThreshold: Double,
    val cloudBackupEnabled: Boolean,
    val cloudBackupFailed: Boolean,
    val localBackupEnabled: Boolean,
    val localBackupFailed: Boolean,
    val nextBackupAt: Long?,
    val interruptionsToday: Int?,
    val lastInterruptionDay: Long?,
    /** 冷启动时服务状态尚未发布，但已保存的手动暂停仍有效。 */
    val manualPausedUntil: Long? = null,
)

object HealthRules {
    fun report(input: HealthInputs): HealthReport = with(input) {
        val issues = buildList {
            fun issue(id: String, severity: HealthSeverity, title: String, detail: String, fix: HealthFix, label: String) {
                add(HealthIssue(id, severity, title, detail, fix, label))
            }
            if (!microphoneGranted || !notificationsGranted) issue("permissions", HealthSeverity.Critical, "录不了音",
                if (!microphoneGranted) "缺少麦克风权限" else "缺少通知权限", HealthFix.GrantPermissions, "去开启")
            if ((listen == ListenState.Stopped || listen == ListenState.Unavailable) && !inQuietPeriod && (manualPausedUntil == null || manualPausedUntil <= now))
                issue("listening", HealthSeverity.Critical, "录音没开", "现在不会录任何声音", HealthFix.StartListening, "打开")
            if (!batteryUnrestricted) issue("battery", HealthSeverity.Critical, "录音可能被系统中断", "省电策略还没关", HealthFix.DisableBatteryOptimization, "去关闭")
            if (!modelReady) issue("model", HealthSeverity.Critical, "不会自动整理", "还没设置整理模型", HealthFix.OpenModel, "去设置")
            val emptyBalance = balanceAvailable == false || (balance?.isFinite() == true && balance <= 0)
            if (emptyBalance) issue("balance-empty", HealthSeverity.Critical, "整理会失败", "$providerName 余额已用完", HealthFix.TopUp, "去充值")
            // 提醒线沿用现有人民币口径；未知/查询失败不冒充余额为零。
            else if (balanceCurrency == "CNY" && balance != null && balance.isFinite() && balanceThreshold > 0 && balance < balanceThreshold)
                issue("balance-low", HealthSeverity.Warning, "$providerName 余额不多", "剩 ¥${money(balance)}", HealthFix.TopUp, "去充值")
            if (!transcriptReady) issue("transcript", HealthSeverity.Warning, "原话转不出来", "转写还没设置好", HealthFix.OpenTranscript, "去设置")
            if (transcriptFailures > 0) issue("transcript-failed", HealthSeverity.Warning, "有 $transcriptFailures 段录音没转成", "点开重试", HealthFix.OpenTranscript, "去看看")
            if ((cloudBackupEnabled && cloudBackupFailed) || (localBackupEnabled && localBackupFailed))
                issue("backup", HealthSeverity.Warning, "备份没成功", "最近一次备份失败", HealthFix.OpenBackup, "去看看")
        }
        val stableDays = if ((interruptionsToday ?: 0) > 0) 0 else lastInterruptionDay?.takeIf { it <= today }
            ?.let { (today - it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        HealthReport(issues.sortedBy { if (it.severity == HealthSeverity.Critical) 0 else 1 }.take(3),
            HealthFacts(stableDays, interruptionsToday,
                nextBackupAt.takeIf { cloudBackupEnabled || localBackupEnabled }), now)
    }

    private fun money(amount: Double): String {
        val cents = (amount * 100).roundToLong()
        return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
}

/** 平台将本地日期的 03:10 换算为时刻；规则只选下一个候选，不用固定 24 小时跨夏令时。 */
object HealthBackupRules {
    fun nextAt(now: Long, todayAt: Long, tomorrowAt: Long): Long? = listOf(todayAt, tomorrowAt).filter { it > now }.minOrNull()
}
