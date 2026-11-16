package app.resound.ui.screentime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.diagnostics.FailureNotes
import app.resound.screentime.ScreenTimeSettings
import app.resound.ui.ShellNav
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsToggle
import app.resound.ui.verify.springClick

/** 采集开或「记录播放标题」开时，才显示整理隐私开关。 */
internal fun showIncludeAppPlaybackSwitch(screenTimeOn: Boolean, playbackTitleOn: Boolean): Boolean =
    screenTimeOn || playbackTitleOn

/**
 * 设置 → 屏幕时间：上面是采集开关和两项权限（没授权的直达系统设置），中间是显示在哪，底部是整理隐私开关。
 * 这些默认都开着，用户可以主动关。
 */
@Composable
fun ScreenTimePage(settings: ScreenTimeSettings, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val showOnHome by settings.showOnHome.collectAsStateWithLifecycle()
    val eventInUse by settings.eventInUse.collectAsStateWithLifecycle()
    val titleOn = if (AppGraph.isPlaybackTitleReady) AppGraph.playbackTitleSettings.enabled.collectAsStateWithLifecycle().value else false
    val (usage, notice) = rememberScreenPermissions()
    var saveError by remember { mutableStateOf(false) }
    fun save(tag: String, block: () -> Unit) {
        saveError = false
        try { block() } catch (e: IllegalStateException) { FailureNotes.record("ui/screentime-$tag", e); saveError = true }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RsCard {
            SwitchRow("记录屏幕时间", "记下每天用了哪些 App、各用多久", enabled) { on -> save("enabled") { settings.setEnabled(on) } }
            if (enabled) {
                Divider()
                PermissionRow("使用情况访问", usage) { ScreenPermissions.openUsageAccess(context) }
            }
            if (AppGraph.isPlaybackTitleReady) {
                Divider()
                SwitchRow("记录播放标题", "记下手机在播放的节目名，比如播客单集、歌名；不读通知内容", titleOn) { on ->
                    save("title") { AppGraph.playbackTitleSettings.setEnabled(on) }
                }
                if (titleOn) {
                    Divider()
                    PermissionRow("通知使用权", notice) { ScreenPermissions.openNotificationAccess(context) }
                }
            }
        }
        if (saveError) Text("保存失败，请重试", style = RsType.subhead, color = c.warning, modifier = Modifier.padding(horizontal = 4.dp))
        if (enabled) {
            Label("显示在哪")
            RsCard {
                SwitchRow("首页显示屏幕用时", "录音卡里显示今天用了多久，节奏图下加一条手机使用条", showOnHome) { on -> save("home") { settings.setShowOnHome(on) } }
                Divider()
                SwitchRow("事件上显示“当时在用”", "某件事发生时用了某个 App 较久，就在事件下面标一行", eventInUse) { on -> save("event") { settings.setEventInUse(on) } }
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日推送", style = RsType.body, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text("在推送通道里订阅“每日屏幕时间”", style = RsType.caption, color = c.textSecondary)
                    }
                    Text("去设置 ›", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick { ShellNav.openSettings(SettingsPage.Push) })
                }
            }
        }
        if (showIncludeAppPlaybackSwitch(enabled, titleOn) && AppGraph.isDigestSettingsReady) {
            Label("隐私")
            IncludeAppPlaybackCard()
        }
        Text("数据只存在手机里。“这段别记”和隐私删除会一起清掉这段时间的 App 和播放记录。",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        RsToggle(on, onChange)
    }
}

/** 一项权限：开了是绿点“已授权”，没开是橙点“还没授权”加“去开启 ›”，点了直达系统设置。 */
@Composable
internal fun PermissionRow(name: String, granted: Boolean, onOpen: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick(onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (granted) c.good else c.accent))
        Text(if (granted) "  已授权「$name」" else "  还没授权「$name」", style = RsType.subhead, color = c.text, modifier = Modifier.weight(1f))
        Text(if (granted) "系统设置 ›" else "去开启 ›", style = RsType.subhead.copy(fontWeight = if (granted) FontWeight.Normal else FontWeight.SemiBold),
            color = if (granted) c.textTertiary else c.accent)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
}

@Composable
private fun Label(text: String) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun IncludeAppPlaybackCard() {
    val c = Rs.colors
    val includeApp by AppGraph.digestSettings.includeAppPlayback.collectAsStateWithLifecycle()
    var appSaveError by remember { mutableStateOf(false) }
    RsCard {
        SwitchRow("整理时带上 App 与播放信息",
            "整理、问问时把当时在用的 App 和节目名发给你设置的大模型，分场景更准；关掉就不发，仍会标“手机在外放”", includeApp) { on ->
            appSaveError = false
            try {
                AppGraph.digestSettings.setIncludeAppPlayback(on)
            } catch (e: IllegalStateException) {
                FailureNotes.record("ui/include-app-playback-save", e)
                appSaveError = true
            }
        }
        if (appSaveError) Text("保存失败，请重试", style = RsType.subhead, color = c.warning)
    }
}
