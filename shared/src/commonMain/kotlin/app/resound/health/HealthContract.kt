package app.resound.health

import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 147：设置页顶部“体检”状态卡的平台无关约定。
 * 规则（什么算问题、先后顺序、文案）写成纯 Kotlin，安卓只负责收集输入。
 * 本文件不引用 android.* / java.*，以后 iOS 直接复用。
 */

enum class HealthSeverity { Warning, Critical }

/** 点“去处理”后去哪里；界面按它跳转或直接执行 */
enum class HealthFix {
    /** 关闭系统省电策略（电池优化） */
    DisableBatteryOptimization,
    /** 授予麦克风等必需权限 */
    GrantPermissions,
    /** 打开聆听 */
    StartListening,
    /** 打开“录音与后台”页 */
    OpenCapture,
    /** 打开“原话与转写”页 */
    OpenTranscript,
    /** 打开“整理模型”页 */
    OpenModel,
    /** 去充值（服务商有充值页时界面直接打开网页，否则同 OpenModel） */
    TopUp,
    /** 打开“数据备份”页 */
    OpenBackup,
}

/** 一件要处理的事。title 一句话说影响（如“录音可能被系统中断”），detail 说原因（如“省电策略还没关”） */
data class HealthIssue(
    val id: String,
    val severity: HealthSeverity,
    val title: String,
    val detail: String?,
    val fix: HealthFix,
    /** 按钮文字，如“去关闭”“去充值”“去设置” */
    val fixLabel: String,
)

/** 一切正常时显示的三个健康数字；拿不到的为 null，界面不显示那一格 */
data class HealthFacts(
    /** 连续稳定运行天数（从最近一次异常中断算起，按自然日） */
    val stableDays: Int?,
    /** 今天录音被中断的次数（被系统杀掉、麦克风长时间被占等，不含用户自己暂停和停录时段） */
    val interruptionsToday: Int?,
    /** 下次自动备份时刻（毫秒）；没开备份为 null */
    val nextBackupAt: Long?,
)

data class HealthReport(
    /** 按严重程度排好，Critical 在前；最多 3 条 */
    val issues: List<HealthIssue>,
    val facts: HealthFacts,
    val checkedAt: Long,
)

interface HealthCheck {
    val report: StateFlow<HealthReport>
    /** 立即重新检查（进入设置页、从系统设置返回时调用） */
    fun refresh()
}
