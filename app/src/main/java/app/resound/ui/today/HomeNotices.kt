package app.resound.ui.today

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.scheduling.BackgroundSystem
import app.resound.ui.ShellNav
import app.resound.ui.settings.NewVersionBody
import app.resound.ui.settings.SettingsPage
import app.resound.ui.settings.displayVersion
import app.resound.ui.settings.info
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import kotlinx.coroutines.launch
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import app.resound.update.UpdatePopupRule
import app.resound.update.UpdateState

/**
 * 首页新版本提示：打开涛声发现新版本就弹出面板，在面板里直接下载、安装；
 * 点“稍后”这一版 24 小时内不再弹，首页留一条小横幅随时点开。
 */
@Composable
fun UpdatePrompt(topGap: Dp = 0.dp) {
    if (!AppGraph.isUpdaterReady) return
    val updater = AppGraph.updater
    val state by updater.state.collectAsStateWithLifecycle()
    // 卡 210：后台还在下载、备份时首页不出横幅也不弹窗，准备好了再说
    val prepared by updater.prepared.collectAsStateWithLifecycle()
    if (!prepared) return
    val info = state.info() ?: return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("update-popup", Context.MODE_PRIVATE) }
    var open by rememberSaveable(info.versionCode) {
        mutableStateOf((state is UpdateState.Available || state is UpdateState.Ready) &&
            UpdatePopupRule.autoOpen(info.versionCode, prefs.getInt("version", 0), prefs.getLong("at", 0), System.currentTimeMillis()))
    }
    fun later() {
        prefs.edit().putInt("version", info.versionCode).putLong("at", System.currentTimeMillis()).apply()
        updater.later()
        open = false
    }

    Spacer(Modifier.height(topGap))
    UpdateBanner(displayVersion(info.versionName), state) { open = true }

    if (open) {
        var resumed by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose {} }
        val canInstall = remember(resumed, state) { updater.canInstall() }
        // 下载、安装进行中划掉面板只是收起；还没开始时划掉等于“稍后”
        RsSheet("涛声 ${displayVersion(info.versionName)} 来了", onDismiss = { if (state is UpdateState.Available) later() else open = false }) {
            NewVersionBody(info, state, canInstall, updater, previewLines = 5, showVersion = false,
                allowInstall = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onLater = { later() },
                onSkip = { updater.skip(); open = false })
        }
    }
}

@Composable
internal fun UpdateBanner(version: String, state: UpdateState, onClick: () -> Unit) {
    val c = Rs.colors
    val status = when (state) {
        is UpdateState.Downloading -> if (state.totalBytes > 0) "正在下载 ${state.doneBytes * 100 / state.totalBytes}%" else "正在下载"
        is UpdateState.Assembling -> "正在准备安装包"
        is UpdateState.Ready -> "已下载好，点这里安装"
        is UpdateState.Installing -> "正在安装"
        is UpdateState.Failed -> "没装上，点这里查看"
        else -> "点这里更新"
    }
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(c.accentSoft).springClick(onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.width(10.dp))
        Text("新版本 $version", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
        Spacer(Modifier.width(8.dp))
        Text(status, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
    }
}

/** 系统在压着后台：按间隔整理、录音一直开着，系统却好几个小时没给后台机会。关掉后 3 天内不再出现。 */
@Composable
fun BackgroundLimitNotice(topGap: Dp = 0.dp) {
    val diagnostics = (LocalContext.current.applicationContext as? app.resound.ResoundApp)?.pipelineDiagnostics ?: return
    val health by diagnostics.backgroundHealth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home-notices", Context.MODE_PRIVATE) }
    var hiddenAt by remember { mutableStateOf(prefs.getLong("backgroundLimitHiddenAt", 0)) }
    val now = System.currentTimeMillis()
    if (!health.suspectedSystemRestriction || (now >= hiddenAt && now - hiddenAt < 3 * 24 * 60 * 60_000L)) return
    val body = when (diagnostics.system) {
        BackgroundSystem.Type.Container -> "你在卓易通里用涛声。在鸿蒙设置里给卓易通开放后台权限，整理就能按时进行。"
        BackgroundSystem.Type.Android -> "录音一直在，但系统好几个小时没让涛声在后台干活。按清单改一下设置，整理就能按时进行。"
        else -> "把涛声的“应用启动管理”改成手动、三项全开，整理就能按时进行。"
    }
    Spacer(Modifier.height(topGap))
    BackgroundLimitCard(body, onSettings = { ShellNav.openSettings(SettingsPage.Capture) }) {
        hiddenAt = System.currentTimeMillis()
        prefs.edit().putLong("backgroundLimitHiddenAt", hiddenAt).apply()
    }
}

@Composable
internal fun BackgroundLimitCard(body: String, onSettings: () -> Unit, onHide: () -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("⏰", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("系统在推迟涛声的后台整理", style = RsType.body, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(body, style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("去设置", filled = true, onClick = onSettings)
                    Pill("过几天再说", filled = false, onClick = onHide)
                }
            }
        }
    }
}

/** 唤醒芯片不能和录音同时工作的品牌多见于这些厂商；只用来决定是否在首页提示一次 */
internal fun wakeWordLikelyBlocked(brand: String = android.os.Build.MANUFACTURER): Boolean =
    brand.lowercase() in setOf("xiaomi", "redmi", "poco", "meizu", "oppo", "oneplus", "realme", "vivo", "iqoo")

internal const val WAKE_WORD_TIP = "部分手机（如小米、魅族）在涛声录音时，喊“小爱同学”等唤醒词会没反应：手机不支持同时听唤醒词和录音。可以改用长按电源键唤醒语音助手。"

/** 录音时语音助手唤不醒：只对常见受影响品牌、在开始收音后提示一次，点“知道了”不再出现 */
@Composable
fun WakeWordNotice(listening: Boolean, topGap: Dp = 0.dp) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home-notices", Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean("wakeWordTipSeen", false)) }
    if (seen || !listening || !wakeWordLikelyBlocked()) return
    val c = Rs.colors
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("🎙", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("录音时语音助手可能唤不醒", style = RsType.body, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("这台手机不支持一边录音一边听唤醒词。想用小爱同学等语音助手时，改用长按电源键唤醒。",
                    style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Pill("知道了", filled = false) {
                    seen = true
                    prefs.edit().putBoolean("wakeWordTipSeen", true).apply()
                }
            }
        }
    }
}

/**
 * 更新后第一次打开：首页一张小卡“已更新到 X · 这次更新了什么”，点开看这一版说明；看过或关掉不再出现。
 * 全新安装不提示（安装时间等于更新时间，且从没记过）。
 */
@Composable
fun WhatsNewNotice(topGap: Dp = 0.dp) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home-notices", Context.MODE_PRIVATE) }
    val current = remember { app.resound.ui.settings.Changelog.currentVersion(context) }
    val entries = remember { app.resound.ui.settings.Changelog.entries(context) }
    var seen by remember {
        val stored = prefs.getString("whatsNewSeen", null)
        val fresh = stored == null && runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
        // 全新安装：直接记成已看过，免得下次误判
        if (fresh) prefs.edit().putString("whatsNewSeen", current).apply()
        mutableStateOf(!app.resound.update.ChangelogRules.shouldShowWhatsNew(stored, current, fresh, entries))
    }
    var open by remember { mutableStateOf(false) }
    val entry = app.resound.update.ChangelogRules.entry(entries, current)
    if (seen || entry == null) return
    fun done() { seen = true; prefs.edit().putString("whatsNewSeen", current).apply() }
    if (open) app.resound.ui.settings.ChangelogSheet(entry) { open = false; done() }
    val c = Rs.colors
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("✨", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("已更新到 ${app.resound.ui.settings.displayVersion(current)}", style = RsType.body, color = c.text)
                if (entry.title.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.title, style = RsType.caption, color = c.textSecondary)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Pill("看看更新了什么", filled = true) { open = true }
                    Spacer(Modifier.width(8.dp))
                    Pill("知道了", filled = false) { done() }
                }
            }
        }
    }
}

/** 远程通知（魔搭 / GitHub 下发、已验签）：标题、正文、可选图片（如二维码）和链接；最多同时两条 */
@Composable
fun RemoteNoticeCards(topGap: Dp = 0.dp) {
    val context = LocalContext.current
    val notices = remember { app.resound.notice.RemoteNotices.get(context) }
    val file by notices.file.collectAsStateWithLifecycle()
    val dismissed by notices.dismissed.collectAsStateWithLifecycle()
    val versionCode = remember { runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L) }
    val shown = app.resound.notice.NoticeRules.visible(file, System.currentTimeMillis(), versionCode, android.os.Build.MANUFACTURER, dismissed)
    shown.forEach { n ->
        Spacer(Modifier.height(topGap))
        RemoteNoticeCard(n, notices.image(n)) { notices.dismiss(n.id) }
    }
}

@Composable
private fun RemoteNoticeCard(n: app.resound.notice.Notice, image: java.io.File?, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val bitmap = remember(image) { image?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("📣", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, style = RsType.body, color = c.text)
                if (n.body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(n.body, style = RsType.caption, color = c.textSecondary)
                }
            }
        }
        if (bitmap != null) {
            Spacer(Modifier.height(14.dp))
            // 二维码等图片：居中，最宽 220dp，白底圆角，夜间也看得清
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(bitmap, n.image?.caption ?: n.title,
                    Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(n.image?.ratio ?: (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)))
                        .clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color.White))
            }
            n.image?.caption?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = RsType.caption, color = c.textTertiary, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (image != null) Pill("保存到相册", filled = true) { saveToGallery(context, image, n.id) }
            n.link?.let { l -> Pill(l.label, filled = image == null) {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l.url))) }
            } }
            if (n.dismissible) Pill("知道了", filled = false, onClick = onDismiss)
        }
    }
}

/** 存进相册“Pictures/涛声”，方便用微信扫一扫 → 相册识别二维码；Android 10 起不需要存储权限 */
private fun saveToGallery(context: Context, image: java.io.File, id: String) {
    val ok = runCatching {
        val ext = image.extension.lowercase().let { if (it == "jpg") "jpeg" else it }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "涛声-$id.${image.extension}")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/$ext")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/涛声")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out).write(image.readBytes()) }
    }.onFailure { app.resound.diagnostics.FailureNotes.record("ui/today/HomeNotices.kt:saveToGallery", it) }.isSuccess
    android.widget.Toast.makeText(context, if (ok) "已保存到相册，可用微信扫一扫 → 相册识别" else "没保存成功，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
}

/** 按系统类型给“保活”提示换说法；界面只说要做什么 */
internal fun keepAliveHint(context: Context): String =
    when ((context.applicationContext as? app.resound.ResoundApp)?.pipelineDiagnostics?.system) {
        BackgroundSystem.Type.Container -> "你在卓易通里用涛声，要在鸿蒙设置里给卓易通开放后台权限，才能全天收音、按时整理。"
        BackgroundSystem.Type.Huawei, BackgroundSystem.Type.Honor, BackgroundSystem.Type.Harmony ->
            "华为、荣耀手机要把涛声的“应用启动管理”改成手动、三项全开，才能全天收音、按时整理。"
        else -> "小米等手机还要打开“自启动”、把电池设为“无限制”，才能全天收音不断。"
    }

/**
 * 屏幕时间默认开着，但要用户在系统里亲手给两项权限才真正记录。缺哪项就在首页提醒一次，
 * 每项后面“去开启”直达系统设置；回到涛声自动重查，都开好卡片就消失。
 * 点“暂不需要”就把缺权限的那几项功能关掉，卡片不再出现，不让功能一直悬着。
 */
@Composable
fun ScreenTimePermissionNotice(topGap: Dp = 0.dp) {
    if (!AppGraph.isScreenTimeReady || !AppGraph.isPlaybackTitleReady) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home-notices", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("screenPermissionDismissed", false)) }
    val screenOn by AppGraph.screenTimeSettings.enabled.collectAsStateWithLifecycle()
    val titleOn by AppGraph.playbackTitleSettings.enabled.collectAsStateWithLifecycle()
    val (usage, notice) = app.resound.ui.screentime.rememberScreenPermissions()
    val needUsage = screenOn && !usage
    val needNotice = titleOn && !notice
    if (dismissed || (!needUsage && !needNotice)) return
    val c = Rs.colors
    val missing = listOf(needUsage, needNotice).count { it }
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("开启屏幕时间 · 还差 $missing 项权限", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(4.dp))
        Text("看每天用了哪些 App、播放了什么，和说话、事件放在一起。这两项要在系统设置里亲手打开。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        if (screenOn) app.resound.ui.screentime.PermissionRow("使用情况访问", usage) { app.resound.ui.screentime.ScreenPermissions.openUsageAccess(context) }
        if (titleOn) app.resound.ui.screentime.PermissionRow("通知使用权", notice) { app.resound.ui.screentime.ScreenPermissions.openNotificationAccess(context) }
        Text("开启后，整理时会参考你在用的 App 和播放的节目（会发给你设置的大模型），可在“设置 → 屏幕时间”里关闭。数据只存在手机里。",
            style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("更多设置", filled = false) { ShellNav.openSettings(SettingsPage.ScreenTime) }
            Pill("暂不需要", filled = false) {
                try {
                    if (needUsage) AppGraph.screenTimeSettings.setEnabled(false)
                    if (needNotice) AppGraph.playbackTitleSettings.setEnabled(false)
                } catch (e: IllegalStateException) { app.resound.diagnostics.FailureNotes.record("ui/screen-permission-skip", e) }
                dismissed = true
                prefs.edit().putBoolean("screenPermissionDismissed", true).apply()
            }
        }
    }
}


/** 卡 205：外放默认也转写整理；某天外放转写超过 30 分钟时提示一次，可按 App 关。看过或关掉不再出现 */
@Composable
fun PlaybackHintNotice(topGap: Dp = 0.dp) {
    val context = LocalContext.current
    val notices = remember { app.resound.notice.RemoteNotices.get(context) }
    val show by notices.showPlaybackHint.collectAsStateWithLifecycle(initialValue = false)
    if (!show) return
    val c = Rs.colors
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("🔊", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("外放的声音也在转写和整理", style = RsType.body, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("刷视频多的 App 可以单独设成不记", style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Row {
                    Pill("按 App 设置", filled = true) { notices.playbackHintShown(); ShellNav.openSettings(SettingsPage.Transcript) }
                    Spacer(Modifier.width(8.dp))
                    Pill("知道了", filled = false) { notices.playbackHintShown() }
                }
            }
        }
    }
}

/** 卡 214：开着推录音、到期录音推不上去被扣满 7 天时提醒；“不等了，直接删”要再点一次 */
@Composable
fun HeldRecordingsNotice(topGap: Dp = 0.dp) {
    if (!AppGraph.isOriginalAudioReady) return
    val audio = AppGraph.originalAudio
    val held by audio.heldSummary.collectAsStateWithLifecycle()
    if (!held.remind || held.segments == 0) return
    val c = Rs.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("📦", style = RsType.headline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("有 ${held.days} 天录音还没推上去", style = RsType.body, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("到期了但还没推到网盘或文件夹，先留在手机里（${app.resound.ui.verify.formatBytes(held.bytes)}）。去看看是哪里出错，或者不要了直接删。",
                    style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("去看看", filled = true) { ShellNav.openSettings(SettingsPage.Push) }
                    Pill(if (busy) "正在删除…" else if (armed) "找不回来，再点一次" else "不等了，直接删", filled = false) {
                        if (busy) return@Pill
                        if (!armed) { armed = true; scope.launch { kotlinx.coroutines.delay(3_000); armed = false }; return@Pill }
                        busy = true
                        scope.launch { runCatching { audio.releaseHeld() }; busy = false; armed = false }
                    }
                }
            }
        }
    }
}
