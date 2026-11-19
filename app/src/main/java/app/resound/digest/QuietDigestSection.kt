package app.resound.digest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsToggle
import app.resound.ui.verify.springClick

/**
 * 安静自动整理设置区（挂到设置 → AI 整理 → 智能整理一行即可：<QuietDigestSection/>）。
 * 与 Interval / TimePoints 并存；显示真实等待原因（uiState），不显示假成功。
 */
@Composable
fun QuietDigestSection(onDigestNow: () -> Unit = {}) {
    val c = Rs.colors
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

    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("环境安静后自动整理", style = RsType.body, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("连续一段时间无人说话，自动整理刚才的记录", style = RsType.caption, color = c.textTertiary)
            }
            Spacer(Modifier.width(12.dp))
            RsToggle(enabled) {
                enabled = it
                prefs.edit().putBoolean("quietDigestEnabled", it).apply()
            }
        }
        if (enabled) {
            Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            Text("连续安静时长", style = RsType.subhead, color = c.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 60).forEach { v ->
                    QuietChip("$v 分钟", threshold == v) { threshold = v; prefs.edit().putInt("quietThresholdMin", v).apply() }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("两次自动整理最小间隔", style = RsType.subhead, color = c.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240).forEach { v ->
                    QuietChip("$v 分钟", cooldown == v) { cooldown = v; prefs.edit().putInt("quietCooldownMin", v).apply() }
                }
            }
            Spacer(Modifier.height(14.dp))
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
            }, style = RsType.caption, color = c.textSecondary)
            if (lastRun > 0) {
                Spacer(Modifier.height(4.dp))
                Text("上次自动整理：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(lastRun)), style = RsType.caption, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Pill("现在整理", filled = false, onClick = onDigestNow)
    }
}

/** 与 DigestTimingCard 的 MinuteChip 同款：卡内未选中用页面底色 */
@Composable
private fun QuietChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.selected else c.fill, spring(stiffness = Spring.StiffnessMediumLow), label = "quietChip")
    val fg by animateColorAsState(if (on) c.onSelected else c.textSecondary, label = "quietChipFg")
    Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg,
        modifier = Modifier.springClick(onClick).clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp))
}
