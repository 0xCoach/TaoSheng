package app.resound.ui.today

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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

/** 按系统类型给“保活”提示换说法；界面只说要做什么 */
internal fun keepAliveHint(context: Context): String =
    when ((context.applicationContext as? app.resound.ResoundApp)?.pipelineDiagnostics?.system) {
        BackgroundSystem.Type.Container -> "你在卓易通里用涛声，要在鸿蒙设置里给卓易通开放后台权限，才能全天收音、按时整理。"
        BackgroundSystem.Type.Huawei, BackgroundSystem.Type.Honor, BackgroundSystem.Type.Harmony ->
            "华为、荣耀手机要把涛声的“应用启动管理”改成手动、三项全开，才能全天收音、按时整理。"
        else -> "小米等手机还要打开“自启动”、把电池设为“无限制”，才能全天收音不断。"
    }
