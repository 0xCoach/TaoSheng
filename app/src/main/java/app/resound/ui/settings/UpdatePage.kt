package app.resound.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
    val state by updater.state.collectAsStateWithLifecycle()
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
            Text("声声不息 ${displayVersion(updater.currentVersion)}", style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                when (val s = state) {
                    UpdateState.Idle, UpdateState.Checking -> "正在检查…"
                    is UpdateState.UpToDate -> "已是最新 · ${app.resound.ui.verify.formatStamp(s.checkedAt)} 检查"
                    is UpdateState.Failed -> if (s.info == null) s.message else "有新版本 ${displayVersion(s.info.versionName)}"
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

        val auto by updater.autoCheck.collectAsStateWithLifecycle()
        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("自动检查更新", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(if (auto) "每 6 小时和打开声声不息时检查，有新版发通知" else "只在点“检查更新”时检查",
                        style = RsType.caption, color = c.textTertiary)
                }
                app.resound.ui.skills.Toggle(auto) { updater.setAutoCheck(it) }
            }
        }

        // 更新日志：装好以后也能回看每一版改了什么（读安装包里的 CHANGELOG.md，不用联网）
        ChangelogCard(updater.currentVersion)
    }
}

@Composable
private fun NewVersionCard(info: UpdateInfo, state: UpdateState, canInstall: Boolean, updater: AppUpdater, allowInstall: () -> Unit) {
    RsCard(Modifier.animateContentSize()) { NewVersionBody(info, state, canInstall, updater, allowInstall) }
}

/** 设置更新页和首页新版本弹窗共用：同一套下载、安装、失败处理 */
@Composable
internal fun NewVersionBody(info: UpdateInfo, state: UpdateState, canInstall: Boolean, updater: AppUpdater, allowInstall: () -> Unit,
                            previewLines: Int = 10, showVersion: Boolean = true, onLater: () -> Unit = { updater.later() }, onSkip: () -> Unit = { updater.skip() }) {
    val c = Rs.colors
    val context = LocalContext.current
    Column {
        val size = (if (info.incremental) "增量更新 · " else "完整安装包 · ") + formatBytes(info.downloadBytes)
        // 首页弹窗标题已写版本号，这里只留大小
        if (showVersion) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayVersion(info.versionName), style = RsType.title, color = c.text, modifier = Modifier.weight(1f))
            Text(size, style = RsType.subhead, color = c.textTertiary)
        } else Text(size, style = RsType.subhead, color = c.textTertiary)
        if (info.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            // 更新说明是 Markdown（标题、列表、粗体）；长的先显示开头，点一下展开全部
            var open by remember(info.versionName) { mutableStateOf(false) }
            val notes = info.notes.trim()
            val long = notes.lines().size > previewLines + 2
            app.resound.ui.ask.AnswerText(if (open || !long) notes else notes.lines().take(previewLines).joinToString("\n"))
            if (long) Text(if (open) "收起" else "展开全部更新内容", style = RsType.subhead, color = c.accent,
                modifier = Modifier.padding(top = 6.dp).springClick { open = !open })
        }
        Spacer(Modifier.height(16.dp))
        // 卡 210：点了“立即更新”（或“重试”“去允许”）且还停在这里，下载好、权限到位就自动接着安装，不用再点“安装”
        var proceed by rememberSaveable(info.versionName) { mutableStateOf(false) }
        LaunchedEffect(state, canInstall, proceed) {
            if (proceed && state is UpdateState.Ready && canInstall) { proceed = false; updater.install() }
        }
        when (state) {
            is UpdateState.Available -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("立即更新", filled = true) { proceed = true; updater.download() }
                    Pill("稍后", filled = false, onClick = onLater)
                    Spacer(Modifier.weight(1f))
                    Text("跳过这个版本", style = RsType.subhead, color = c.textTertiary,
                        modifier = Modifier.clip(CircleShape).springClick(onSkip).padding(horizontal = 6.dp, vertical = 9.dp))
                }
            }
            is UpdateState.Downloading -> {
                Progress(if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f)
                Spacer(Modifier.height(8.dp))
                Text("已下载 ${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}" + if (proceed) " · 下载完自动安装" else "", style = RsType.caption, color = c.textTertiary)
            }
            is UpdateState.Assembling -> Text("正在准备安装包…", style = RsType.subhead, color = c.textSecondary)
            is UpdateState.Ready -> if (canInstall) {
                Pill("安装", filled = true) { updater.install() }
                Spacer(Modifier.height(8.dp))
                Text("数据在下载完后已自动备份。点安装后系统会弹一次确认，请点“更新”或“继续安装”。安装时录音会暂停十几秒，装好后自动继续", style = RsType.caption, color = c.textTertiary)
            } else {
                Text("需要先允许声声不息安装应用", style = RsType.subhead, color = c.warning)
                Spacer(Modifier.height(8.dp))
                Pill("去允许", filled = true) { proceed = true; allowInstall() }
            }
            is UpdateState.Installing -> if (state.backingUp) {
                // 备份分步显示：本机快照很快，本机文件夹 / 坚果云备份最多等 3 分钟
                Text(state.step ?: "正在备份数据…", style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(8.dp))
                Progress(state.progress ?: 0f)
                Spacer(Modifier.height(6.dp))
                Text("备份好后会自动进入安装，不用再点", style = RsType.caption, color = c.textTertiary)
            } else {
                Text("请在系统弹窗里点“更新”或“继续安装”", style = RsType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.accent)
                Spacer(Modifier.height(6.dp))
                Text("这一步是系统的安全确认，声声不息没法代点。没看到弹窗的话，下拉通知栏点“点这里完成安装”", style = RsType.caption, color = c.textTertiary)
            }
            is UpdateState.Failed -> {
                Text(state.message, style = RsType.subhead, color = c.warning)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("重试", filled = false) { proceed = true; updater.download() }
                    Pill("用浏览器下载", filled = false) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            else -> Unit
        }
        InstallFallback(info, state)
    }
}

/**
 * 卡 206：系统安装确认没弹出来（小米等会拦后台弹窗）时的退路。
 * 用已经合成好的完整安装包交给系统安装器，不用重新下载；小米/澎湃再给两个权限直达按钮。
 */
@Composable
private fun InstallFallback(info: UpdateInfo, state: UpdateState) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { runCatching { app.resound.update.UpdateInstallActions.systemInstallerAvailable }.getOrNull() } ?: return
    val ready by flow.collectAsStateWithLifecycle()
    if (ready?.versionName != info.versionName) return
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() } ?: return
    val xiaomi = remember { runCatching { app.resound.update.UpdateInstallActions.showXiaomiInstallHint }.getOrDefault(false) }
    var handing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fill).padding(14.dp)) {
        Text("系统确认没弹出来？", style = RsType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(4.dp))
        Text("用已经下载好的安装包交给系统安装，不用重新下载，数据都在", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        Pill(if (handing) "正在打开…" else "用系统安装器安装", filled = true) {
            if (handing) return@Pill
            handing = true; note = null
            scope.launch {
                val ok = runCatching { app.resound.update.UpdateInstallActions.installWithSystem(activity) }.getOrDefault(false)
                note = if (ok) "已交给系统，按提示点“安装”即可" else "没能打开系统安装器，可以用浏览器下载完整安装包"
                handing = false
            }
        }
        note?.let { Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp)) }
        if (xiaomi) {
            Spacer(Modifier.height(12.dp))
            Text("小米、澎湃系统默认会拦后台弹出的安装确认。打开下面两项，下次就能直接弹出", style = RsType.caption, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("后台弹出界面", filled = false) { app.resound.update.UpdateInstallActions.openXiaomiPermissions(activity) }
                Pill("安装未知应用", filled = false) { app.resound.update.UpdateInstallActions.openUnknownApps(activity) }
            }
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

internal fun UpdateState.info(): UpdateInfo? = when (this) {
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
        state.info()?.let { "有新版本 ${displayVersion(it.versionName)}" to true } ?: (displayVersion(current) to false)
    is UpdateState.Downloading -> "正在下载 ${if (state.totalBytes > 0) state.doneBytes * 100 / state.totalBytes else 0}%" to true
    is UpdateState.Assembling, is UpdateState.Installing -> "正在更新" to true
    is UpdateState.UpToDate -> "${displayVersion(current)} · 已是最新" to false
    else -> displayVersion(current) to false
}

/** 下载失败时浏览器回退入口：指向自有发布仓的 Releases 页（/release/<file> 实际 302 到此）。 */
internal const val RELEASES_PAGE = "https://github.com/gzamon/soundsound-releases/releases"
