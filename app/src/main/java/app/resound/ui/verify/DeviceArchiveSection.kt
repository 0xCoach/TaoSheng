package app.resound.ui.verify

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.devices.*
import app.resound.devices.android.DeviceInputs
import app.resound.devices.driver.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DeviceArchiveSection(address: String, inputs: DeviceInputs, status: DeviceStatusModel?, enabled: Boolean) {
    val repository = inputs.archives ?: return
    val identity = inputs.stableIdentity(address) ?: return
    val records by repository.records.collectAsStateWithLifecycle()
    val configs by repository.options.collectAsStateWithLifecycle()
    val messages by repository.status.collectAsStateWithLifecycle()
    var options by remember(identity) { mutableStateOf(repository.configuration(identity)) }
    LaunchedEffect(configs[identity]) { configs[identity]?.let { options = it } }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmAuto by remember { mutableStateOf(false) }
    var pendingCleanupDelay by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    var restoreIndependent by remember { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(10) }
    val scope = rememberCoroutineScope();val c = Rs.colors
    fun run(block: suspend () -> Unit) {
        if (busy || !enabled) return
        scope.launch {
            busy = true;error = null
            try { block() }
            catch (e: CancellationException) { repository.operationStopped(identity,cancelled = true);throw e }
            catch (e: Exception) {
                repository.operationStopped(identity,cancelled = false)
                error = e.message ?: "操作未完成，请查看各项结果"
            }
            finally { busy = false }
        }
    }
    fun configure(value: DeviceArchiveOptions) = run { repository.configure(identity,value);options = value }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(if(expanded) "收起完整原声与存储管理" else "完整原声与设备存储",filled = false) { expanded = !expanded }
        if (expanded) {
            Text("开启后，下次补传也会归档已被实时收音覆盖的完整录音，可能占用较多空间。完整归档不随人声片段保留期自动删除；隐私删除对应时间或全部本机录音时会一并清理，保护开关不阻止隐私删除。独立备份需在原目的地另行删除。",style = RsType.caption,color = c.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保存完整原声",style = RsType.subhead,color = c.text,modifier = Modifier.weight(1f))
                Switch(checked = options.archiveEnabled,enabled = enabled && !busy && !options.retention.enabled,
                    onCheckedChange = { configure(options.copy(archiveEnabled = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动清理设备原件",style = RsType.subhead,color = c.text,modifier = Modifier.weight(1f))
                Switch(checked = options.retention.enabled,enabled = enabled && !busy,onCheckedChange = { checked ->
                    if (checked) confirmAuto = true else configure(options.copy(retention = options.retention.copy(enabled = false)))
                })
            }
            if (confirmAuto) {
                Text("开启后会保存整段原声，并自动复制到当前配置的独立备份。只有完整解码、导入及备份读回校验通过，才按所选时机删除豆内原件。${cleanupTimingText(options.retention)} 已有历史文件需另行纳入。手机和独立备份中的完整原声会保留。",style = RsType.caption,color = c.warning)
                if (repository.backupDestination() == null) Text("请先在备份设置中配置 USB 电脑或云端目的地；仅有手机内副本、文字或普通人声片段备份不满足条件。",style = RsType.caption,color = c.warning)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("确认开启",filled = true) {
                        configure(options.copy(archiveEnabled = true,retention = options.retention.copy(enabled = true,enabledAtMillis = System.currentTimeMillis())))
                        confirmAuto = false
                    }
                    Pill("取消",filled = false) { confirmAuto = false }
                }
            }
            Text("清理时机（从完整备份校验通过计时）",style = RsType.subhead,color = c.text)
            listOf(listOf(0 to "校验后清理",24 to "再留24小时"),listOf(72 to "再留3天",168 to "再留7天")).forEach { choices ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { (hours,label) -> Pill(label,filled = options.retention.cleanupDelayHours == hours) {
                        if (options.retention.cleanupDelayHours != hours) pendingCleanupDelay = hours
                    } }
                }
            }
            pendingCleanupDelay?.let { hours ->
                val next = options.retention.copy(cleanupDelayHours = hours)
                val count = records.count { record -> record.deviceIdentity == identity &&
                    deviceRetentionBlock(record,next,System.currentTimeMillis(),identity,repository.installation,
                        repository.backupDestination(),status?.recording == RecordingState.Stopped,
                        status?.batteries?.firstOrNull { it.part == 1 }?.percent,automatic = false) == DeviceRetentionBlock.None }
                Text("${cleanupTimingText(next)} 当前有 $count 条归档满足清理预览条件；执行前仍会重新核验原件和独立备份。",style = RsType.caption,color = c.warning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("确认清理时机",filled = true) { configure(options.copy(retention = next));pendingCleanupDelay = null }
                    Pill("取消",filled = false) { pendingCleanupDelay = null }
                }
            }
            Text("${cleanupTimingText(options.retention)} 录音中、低电量、受保护、备份不可达或身份不符时跳过；完整原声在手机和独立备份中保留。",style = RsType.caption,color = c.textTertiary)
            messages[identity]?.let { Text(it,style = RsType.caption,color = c.textSecondary) }
            Pill("从独立备份找回归档清单",filled = false) { run { repository.discoverBackup(identity) } }
            if (busy) Text("正在处理，请稍候…",style = RsType.caption,color = c.textSecondary)
            error?.let { Text(it,style = RsType.caption,color = c.warning) }
            val own = records.filter { it.deviceIdentity == identity }.sortedByDescending { it.startedAtMillis ?: it.firstSeenClosedAtMillis }
            Text("完整归档 ${own.size} 条 · 以下为清理条件预览，执行前会重新核验",style = RsType.subhead,color = c.text)
            own.take(limit).forEach { record ->
                val reason = deviceRetentionBlock(record,options.retention,System.currentTimeMillis(),identity,repository.installation,
                    repository.backupDestination(),status?.recording == RecordingState.Stopped,
                    status?.batteries?.firstOrNull { it.part == 1 }?.percent,automatic = false)
                val title = android.text.format.DateFormat.format("MM-dd HH:mm",record.startedAtMillis ?: record.firstSeenClosedAtMillis)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$title · ${record.durationMillis/1000} 秒 · ${formatBytes(record.archiveBytes)}",style = RsType.subhead,color = c.text)
                    Text("${if(record.imported) "导入已确认" else "导入待确认"} · ${if((record.backup?.verifiedAtMillis ?: 0)>0) "独立备份已校验" else if(record.backup != null) "独立备份待完整读回" else "尚无独立备份凭证"}",style = RsType.caption,color = c.textSecondary)
                    Text(reason.text(),style = RsType.caption,color = if(reason == DeviceRetentionBlock.None) c.good else c.textTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if(record.protected) "取消保护" else "保护原件",filled = record.protected) { run { repository.protect(record.id,!record.protected) } }
                        Pill("独立备份并校验",filled = false) { run { repository.backUp(record.id) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("从本机归档导入",filled = false) { restoreIndependent = false;confirmRestore = record.id }
                        if (record.id !in options.retention.adoptedHistory && reason == DeviceRetentionBlock.HistoricalNotAdopted)
                            Pill("纳入历史清理",filled = false) { configure(options.copy(retention = options.retention.copy(adoptedHistory = options.retention.adoptedHistory+record.id))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if(record.backup != null) Pill("从独立备份导入",filled = false) { restoreIndependent = true;confirmRestore = record.id }
                        Pill("核对设备原件",filled = false) { run { inputs.withDevice(address) { repository.verifyDeviceSource(record.id,it) } } }
                    }
                    if (confirmRestore == record.id) {
                        Text((if(restoreIndependent) "将从当前电脑或云端完整下载并读回校验，" else "将使用本机完整归档，")+"重新导入时间线可能与现有人声片段重复；不会恢复到录音豆。",style = RsType.caption,color = c.warning)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("确认导入",filled = true) { val independent=restoreIndependent;run { repository.restore(record.id,independent) };confirmRestore = null }
                            Pill("取消",filled = false) { confirmRestore = null }
                        }
                    }
                    if (reason == DeviceRetentionBlock.None) Pill("清理这条设备原件",filled = false) { confirmDelete = record.id }
                    if (confirmDelete == record.id) {
                        Text("只删除这条豆内原件，不能恢复到录音豆；本机完整归档及独立备份会保留。",style = RsType.caption,color = c.warning)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("确认清理",filled = true) { run { inputs.withDevice(address) { repository.cleanup(it,automatic = false,selectedIds = setOf(record.id)) } };confirmDelete = null }
                            Pill("取消",filled = false) { confirmDelete = null }
                        }
                    }
                }
            }
            if (own.size > limit) Pill("再显示10条",filled = false) { limit += 10 }
        }
    }
}

private fun DeviceRetentionBlock.text(): String = when (this) {
    DeviceRetentionBlock.None -> "保留条件已满足，执行前仍须核验设备原件和独立备份"
    DeviceRetentionBlock.Disabled -> "自动清理未开启"
    DeviceRetentionBlock.Protected -> "已保护，不自动清理"
    DeviceRetentionBlock.HistoricalNotAdopted -> "历史录音尚未纳入清理范围"
    DeviceRetentionBlock.IncompleteArchive -> "完整归档或解码凭证不足"
    DeviceRetentionBlock.ImportPending -> "导入覆盖尚未完整确认，可从归档重新导入"
    DeviceRetentionBlock.IndependentBackupRequired -> "等待完整原声的独立备份及读回校验"
    DeviceRetentionBlock.BackupChanged -> "独立备份目的地变化，需要重新备份校验"
    DeviceRetentionBlock.WrongDevice -> "设备身份不匹配"
    DeviceRetentionBlock.RecordingOrUnknown -> "设备需停止保存并读取状态"
    DeviceRetentionBlock.LowBatteryOrUnknown -> "设备电量需明确且至少30%"
    DeviceRetentionBlock.RetentionPeriod -> "设备保留期尚未满足"
    DeviceRetentionBlock.ObservationPeriod -> "备份校验后的等待期尚未满足，或校验时间晚于当前时间"
    DeviceRetentionBlock.AlreadyGone -> "豆内原件已不在，完整归档仍保留"
    DeviceRetentionBlock.ResultNeedsReconciliation -> "上次清理结果待核对，不盲目重发删除"
    DeviceRetentionBlock.DifferentInstallation -> "来自另一安装环境，需要重新核验归档和设备"
}

private fun cleanupTimingText(settings: DeviceRetentionSettings): String = when(val hours = settings.cleanupDelayHours) {
    null -> "沿用旧策略：设备保留 ${settings.keepDays} 天，备份校验后等待 ${settings.observationHours} 小时。"
    0 -> "完整备份校验通过后，在设备停止的下一次空闲维护时清理，无需等待转写。"
    24 -> "完整备份校验通过后再留24小时，然后在设备停止的空闲维护时清理。"
    else -> "完整备份校验通过后再留${hours/24}天，然后在设备停止的空闲维护时清理。"
}
