package app.resound.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.resound.update.AppUpdater
import app.resound.update.UpdateInfo
import app.resound.update.UpdateState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatBytes
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick

/** 设置 → 关于 → 版本更新：当前版本、新版本内容、下载 / 安装。下载和安装都要用户点了才做 */
@Composable
fun UpdatePage(updater: AppUpdater, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val state by updater.state.collectAsState()
    // 从系统设置“允许安装应用”回来时重新判断
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose {} }
    val canInstall = remember(resumed, state) { updater.canInstall() }
    LaunchedEffect(Unit) { if (state is UpdateState.Idle || state is UpdateState.UpToDate) updater.check(manual = false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RsCard(Modifier.animateContentSize()) {
            Text("当前版本", style = RsType.subhead, color = c.textSecondary)
            Text("涛声 ${updater.currentVersion}", style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                when (val s = state) {
                    UpdateState.Idle, UpdateState.Checking -> "正在检查…"
                    is UpdateState.UpToDate -> "已是最新 · ${formatClock(s.checkedAt)} 检查"
                    is UpdateState.Failed -> if (s.info == null) s.message else "有新版本 ${s.info.versionName}"
                    else -> "有新版本"
                },
                style = RsType.subhead,
                color = if (state is UpdateState.Failed && (state as UpdateState.Failed).info == null) c.warning else c.textTertiary,
            )
        }

        AnimatedContent(state.info(), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "update") { info ->
            if (info != null) NewVersionCard(info, state, canInstall, updater) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill(if (state is UpdateState.Checking) "正在检查…" else "检查更新", filled = false) {
                if (state !is UpdateState.Checking && !state.busy()) updater.check(manual = true)
            }
        }

        val auto by updater.autoCheck.collectAsState()
        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("自动检查更新", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(if (auto) "每 6 小时和打开涛声时检查，有新版发通知" else "只在点“检查更新”时检查",
                        style = RsType.caption, color = c.textTertiary)
                }
                app.resound.ui.skills.Toggle(auto) { updater.setAutoCheck(it) }
            }
        }
    }
}

@Composable
private fun NewVersionCard(info: UpdateInfo, state: UpdateState, canInstall: Boolean, updater: AppUpdater, allowInstall: () -> Unit) {
    val c = Rs.colors
    RsCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.versionName, style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
            Text((if (info.incremental) "增量更新 · " else "完整安装包 · ") + formatBytes(info.downloadBytes),
                style = RsType.subhead, color = c.textTertiary)
        }
        if (info.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(info.notes.trim(), style = RsType.body, color = c.textSecondary)
        }
        Spacer(Modifier.height(16.dp))
        when (state) {
            is UpdateState.Available -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("下载", filled = true) { updater.download() }
                    Pill("稍后", filled = false) { updater.later() }
                    Spacer(Modifier.weight(1f))
                    Text("跳过这个版本", style = RsType.subhead, color = c.textTertiary,
                        modifier = Modifier.clip(CircleShape).springClick { updater.skip() }.padding(horizontal = 6.dp, vertical = 9.dp))
                }
            }
            is UpdateState.Downloading -> {
                Progress(if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f)
                Spacer(Modifier.height(8.dp))
                Text("已下载 ${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}", style = RsType.caption, color = c.textTertiary)
            }
            is UpdateState.Assembling -> Text("正在准备安装包…", style = RsType.subhead, color = c.textSecondary)
            is UpdateState.Ready -> if (canInstall) {
                Pill("安装", filled = true) { updater.install() }
                Spacer(Modifier.height(8.dp))
                Text("安装时录音会暂停十几秒，装好后自动继续", style = RsType.caption, color = c.textTertiary)
            } else {
                Text("需要先允许涛声安装应用", style = RsType.subhead, color = c.warning)
                Spacer(Modifier.height(8.dp))
                Pill("去允许", filled = true, onClick = allowInstall)
            }
            is UpdateState.Installing -> Text(if (state.backingUp) "正在备份数据…" else "请在系统弹窗里点“安装”",
                style = RsType.subhead, color = c.textSecondary)
            is UpdateState.Failed -> {
                Text(state.message, style = RsType.subhead, color = c.warning)
                Spacer(Modifier.height(8.dp))
                Pill("重试", filled = false) { updater.download() }
            }
            else -> Unit
        }
    }
}

/** 细长进度条，进度变化时平滑过渡 */
@Composable
private fun Progress(fraction: Float) {
    val c = Rs.colors
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "progress")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.accentSoft)) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(c.accent))
    }
}

private fun UpdateState.info(): UpdateInfo? = when (this) {
    is UpdateState.Available -> info
    is UpdateState.Downloading -> info
    is UpdateState.Assembling -> info
    is UpdateState.Ready -> info
    is UpdateState.Installing -> info
    is UpdateState.Failed -> info
    else -> null
}

private fun UpdateState.busy() = this is UpdateState.Downloading || this is UpdateState.Assembling || this is UpdateState.Installing

/** 设置首页“版本更新”右侧那一小段 */
fun updateSummary(current: String, state: UpdateState): Pair<String, Boolean> = when (state) {
    is UpdateState.Available, is UpdateState.Ready, is UpdateState.Failed ->
        state.info()?.let { "有新版本 ${it.versionName}" to true } ?: (current to false)
    is UpdateState.Downloading -> "正在下载 ${if (state.totalBytes > 0) state.doneBytes * 100 / state.totalBytes else 0}%" to true
    is UpdateState.Assembling, is UpdateState.Installing -> "正在更新" to true
    is UpdateState.UpToDate -> "$current · 已是最新" to false
    else -> current to false
}
