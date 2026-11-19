package app.resound.digest

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/**
 * 安静自动整理设置区（挂到设置 → AI 整理 → 智能整理一行即可：<QuietDigestSection/>）。
 * 与 Interval / TimePoints 并存；显示真实等待原因（uiState），不显示假成功。
 */
@Composable
fun QuietDigestSection(onDigestNow: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("digest", android.content.Context.MODE_PRIVATE) }
    val state = remember { context.getSharedPreferences("digest-state", android.content.Context.MODE_PRIVATE) }
    val watcher = (context.applicationContext as? app.resound.ResoundApp)?.let { app.resound.AppGraph.quietDigest }
    val uiState by (watcher?.uiState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(QuietDigestRules.State.Disabled) })
        .collectAsState()
    var enabled by remember { mutableStateOf(prefs.getBoolean("quietDigestEnabled", false)) }
    var threshold by remember { mutableStateOf(prefs.getInt("quietThresholdMin", 20)) }
    var cooldown by remember { mutableStateOf(prefs.getInt("quietCooldownMin", 60)) }
    val lastRun = remember { state.getLong("lastQuietDigestAt", 0L) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("环境安静后自动整理", style = MaterialTheme.typography.titleMedium)
                Text("连续一段时间无人说话，自动整理刚才的记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, {
                enabled = it
                prefs.edit().putBoolean("quietDigestEnabled", it).apply()
            })
        }
        if (enabled) {
            Text("连续安静时长", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 60).forEach { v ->
                    FilterChip(threshold == v, { threshold = v; prefs.edit().putInt("quietThresholdMin", v).apply() },
                        label = { Text("$v 分钟") })
                }
            }
            Text("两次自动整理最小间隔", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240).forEach { v ->
                    FilterChip(cooldown == v, { cooldown = v; prefs.edit().putInt("quietCooldownMin", v).apply() },
                        label = { Text("$v 分钟") })
                }
            }
            Text("当前状态：" + when (uiState) {
                QuietDigestRules.State.WaitingForSilence -> "聆听中，等待安静满 $threshold 分钟"
                QuietDigestRules.State.WaitingForTranscript -> "等待转写完成"
                QuietDigestRules.State.WaitingForCooldown -> "冷却中（距上次自动整理不足 $cooldown 分钟）"
                QuietDigestRules.State.Running -> "整理中"
                QuietDigestRules.State.Trigger -> "即将开始整理"
                QuietDigestRules.State.NotListening -> "未在录音"
                QuietDigestRules.State.Paused -> "录音已暂停"
                QuietDigestRules.State.PrivacyPaused -> "隐私暂停中"
                QuietDigestRules.State.MicInterrupted -> "麦克风被占用/服务异常"
                QuietDigestRules.State.NoContent -> "没有待整理的新内容"
                QuietDigestRules.State.AuthRequired -> "需要登录授权"
                QuietDigestRules.State.AiNotConfigured -> "未配置 AI 服务"
                QuietDigestRules.State.Disabled -> "已关闭"
            }, style = MaterialTheme.typography.bodySmall)
            if (lastRun > 0) Text("上次自动整理：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastRun)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onDigestNow) { Text("现在整理") }
    }
}
