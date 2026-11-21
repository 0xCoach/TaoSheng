package app.resound.ui.verify

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.resound.devices.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

internal fun DeviceActivity.text(): String = when (this) {
    DeviceActivity.Disconnected -> "未连接"
    DeviceActivity.Connecting -> "正在连接蓝牙"
    DeviceActivity.ConnectedIdle -> "蓝牙已连接，声声不息未收音"
    DeviceActivity.WaitingForRecording -> "蓝牙已连接，等待设备开始录音"
    DeviceActivity.StartingAudio -> "已连接，等待声音数据"
    DeviceActivity.ReceivingAudio -> "正在接收设备声音"
    DeviceActivity.Backfilling -> "正在补传离线录音"
    DeviceActivity.WaitingReconnect -> "连接已断开，等待重连"
    DeviceActivity.Disabled -> "驱动已停用"
}

private fun durationText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(1)
    return if (seconds < 60) "$seconds 秒" else if (seconds < 3600) "${seconds / 60} 分 ${seconds % 60} 秒" else "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}

@Composable
internal fun DeviceSyncDetailView(value: DeviceSyncDetail) {
    val c = Rs.colors
    val title = when (value.phase) {
        DeviceSyncPhase.Waiting -> "等待传输通道"
        DeviceSyncPhase.Listing -> "正在读取已保存的录音"
        DeviceSyncPhase.Downloading -> "正在下载录音"
        DeviceSyncPhase.Verifying -> "正在校验完整录音"
        DeviceSyncPhase.Importing -> "正在导入声声不息"
        DeviceSyncPhase.Completed -> "离线录音检查完成"
        DeviceSyncPhase.Paused -> "补传已暂停，进度已保留"
        DeviceSyncPhase.Failed -> "部分录音尚未完成，可再次检查"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title,style = RsType.subhead,color = if (value.phase == DeviceSyncPhase.Failed) c.warning else c.text)
        if (value.tasks > 0) {
            Text("${value.sourceFiles} 个录音文件 · 已处理 ${value.doneTasks}/${value.tasks} 项任务",style = RsType.caption,color = c.textSecondary)
            val total = value.totalBytes?.let(::formatBytes) ?: "总量待确定"
            Text("已取得 ${formatBytes(value.receivedBytes)} / $total",style = RsType.caption,color = c.textSecondary)
        }
        if (value.phase in setOf(DeviceSyncPhase.Downloading,DeviceSyncPhase.Verifying,DeviceSyncPhase.Importing)) {
            value.currentName?.let { Text("当前录音：$it",style = RsType.caption,color = c.textTertiary) }
            value.currentTotal?.takeIf { it > 0 }?.let { total ->
                val fraction = (value.currentBytes.toDouble()/total).coerceIn(0.0,1.0)
                Text("${formatBytes(value.currentBytes)} / ${formatBytes(total)} · ${(fraction*100).toInt()}%",style = RsType.caption,color = c.textSecondary)
                LinearProgressIndicator(progress = { fraction.toFloat() },color = c.accent,trackColor = c.accentSoft,modifier = Modifier.fillMaxWidth())
            }
            if (value.fromCache) Text("复用已完整校验的下载缓存",style = RsType.caption,color = c.good)
            if (value.remainingAudioMillis > 0) Text("待处理录音约 ${durationText(value.remainingAudioMillis)}",style = RsType.caption,color = c.textTertiary)
            value.downloadEtaMillis?.let { Text("按当前速度，下载还需约 ${durationText(it)}；不含导入和转写",style = RsType.caption,color = c.textTertiary) }
        }
    }
}

@Composable
internal fun DeviceEventView(value: DeviceExperience) {
    if (value.events.isEmpty()) return
    val c = Rs.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("最近连接事件（本次运行）",style = RsType.subhead,color = c.text)
        value.events.takeLast(5).asReversed().forEach { event ->
            val time = android.text.format.DateFormat.format("HH:mm:ss",event.atMillis)
            Text("$time · ${event.activity.text()}",style = RsType.caption,color = c.textSecondary)
        }
    }
}
