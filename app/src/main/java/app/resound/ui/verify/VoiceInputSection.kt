package app.resound.ui.verify

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.capture.VoiceInputCapture
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 录音与后台 → 语音输入：用输入法语音输入时，把说出的文字和当时屏幕上的对话记下来 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceInputSection(capture: VoiceInputCapture) {
    val c = Rs.colors
    val context = LocalContext.current
    val s by capture.settings.collectAsState()
    val on by capture.serviceEnabled.collectAsState()
    val today by capture.todayCount.collectAsState()
    val apps by capture.seenApps.collectAsState()
    fun openSettings() = runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Section("语音输入") {
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Toggle("记下语音输入的文字", "用输入法语音输入时，系统会把麦克风交给输入法，回响录不到。打开后改为记下你说出的文字。", s.enabled) {
                capture.update(s.copy(enabled = it)); if (it && !on) openSettings()
            }
            if (s.enabled) {
                if (!on) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warning.copy(alpha = 0.1f)).padding(12.dp)) {
                    Text("语音输入记录没在运行", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.warning)
                    Text("在系统设置 → 无障碍里找到“回响语音输入记录”：没开就打开；显示开着的，先关掉再打开一次。小米有时开着却没真正启动，或者更新后需要重开。", style = RsType.caption, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    Pill("去打开", filled = true) { openSettings() }
                }
                Divider()
                Toggle("记当时的上下文", "同时截下屏幕上正在进行的对话（最近二十来行），帮整理看懂你那句话", s.context) { capture.update(s.copy(context = it)) }
                if (s.context) {
                    Divider()
                    Toggle("记发出后的回复", "发出后 2 分钟内，同一界面里新出现的回复（对方消息、AI 回答、终端输出）补记一次", s.replies) { capture.update(s.copy(replies = it)) }
                }
                Divider()
                Toggle("读不到时截屏识字", "微信、终端这类不让读取界面的应用，改为截屏在手机上识别文字。图片不保存、不上传，识完就丢", s.screenOcr) { capture.update(s.copy(screenOcr = it)) }
                if (apps.isNotEmpty()) {
                    Divider()
                    Text("这些应用不记", style = RsType.body, color = c.text)
                    Text("点一下变成不记；密码框始终不读", style = RsType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        apps.forEach { a ->
                            val off = a.packageName in s.excludedPackages
                            Text(if (off) "${a.label} · 不记" else a.label, style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                                color = if (off) Color.White else c.textSecondary,
                                modifier = Modifier.clip(CircleShape).background(if (off) c.critical else c.background).springClick {
                                    capture.update(s.copy(excludedPackages = if (off) s.excludedPackages - a.packageName else s.excludedPackages + a.packageName))
                                }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
        if (s.enabled) Text(
            (if (today > 0) "今天记下了 $today 条。" else "") + "上下文里会有别人的消息，和原话一样存在手机里，并随备份、同步和推送一起走。",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
    }
}

@Composable
private fun Divider() = androidx.compose.foundation.layout.Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = RsType.body, color = c.text)
            Text(sub, style = RsType.caption, color = c.textTertiary)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}
