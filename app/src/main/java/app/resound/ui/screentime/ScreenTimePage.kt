package app.resound.ui.screentime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.R
import app.resound.capture.MicHolderGuess
import app.resound.diagnostics.FailureNotes
import app.resound.screentime.ScreenTimeSettings
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick

/** 采集开或「记录播放标题」开时，才显示整理隐私开关。 */
internal fun showIncludeAppPlaybackSwitch(screenTimeOn: Boolean, playbackTitleOn: Boolean): Boolean =
    screenTimeOn || playbackTitleOn

/** 设置 → 屏幕时间：采集开关与使用情况访问权引导；底部是整理隐私开关。 */
@Composable
fun ScreenTimePage(settings: ScreenTimeSettings, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val guess = remember { MicHolderGuess(context.applicationContext) }
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val titleOn = if (AppGraph.isPlaybackTitleReady) {
        AppGraph.playbackTitleSettings.enabled.collectAsStateWithLifecycle().value
    } else {
        false
    }
    var permitted by remember { mutableStateOf(guess.permitted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = guess.permitted()
    }
    var saveError by remember { mutableStateOf(false) }
    var askUsage by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记录屏幕时间", style = RsType.headline, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打开后，" + stringResource(R.string.app_name) + "每小时从系统读取 App 使用记录，用来回答“那段时间在用什么”、生成每日屏幕时间，并在整理时标注当时在用的 App。关闭后不再采集，已记录的数据保留但不会被问问、推送或整理用到；要删除请用隐私删除。",
                        style = RsType.subhead,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Toggle(enabled) { on ->
                    saveError = false
                    try {
                        settings.setEnabled(on)
                        if (on && !guess.permitted()) askUsage = true
                    } catch (e: IllegalStateException) {
                        FailureNotes.record("ui/screentime-save", e)
                        saveError = true
                    }
                }
            }
        }
        if (saveError) {
            Text("保存失败，请重试", style = RsType.subhead, color = c.warning, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (enabled) {
            RsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "使用情况访问：" + if (permitted) "已开启" else "未开启",
                        style = RsType.headline,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (!permitted) {
                        Text(
                            "去开启",
                            style = RsType.headline,
                            color = c.accent,
                            modifier = Modifier.springClick { guess.openSettings() },
                        )
                    }
                }
            }
        }
        if (showIncludeAppPlaybackSwitch(enabled, titleOn) && AppGraph.isDigestSettingsReady) {
            IncludeAppPlaybackCard()
        }
    }
    if (askUsage) {
        RsSheet("需要“使用情况访问”权限才能读取 App 使用记录", onDismiss = { askUsage = false }) {
            ActionButton("去开启", ActionKind.Primary) {
                guess.openSettings()
                askUsage = false
            }
            ActionButton("稍后", ActionKind.Secondary) { askUsage = false }
        }
    }
}

@Composable
private fun IncludeAppPlaybackCard() {
    val c = Rs.colors
    val includeApp by AppGraph.digestSettings.includeAppPlayback.collectAsStateWithLifecycle()
    var appSaveError by remember { mutableStateOf(false) }
    RsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("整理时带上 App 与播放信息", style = RsType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "管整理和重新整理时的 App 名、节目名，以及问问里查 App 使用情况。关掉后这些都不发给大模型；仍会标「手机在外放」。",
                    style = RsType.subhead,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Toggle(includeApp) { on ->
                appSaveError = false
                try {
                    AppGraph.digestSettings.setIncludeAppPlayback(on)
                } catch (e: IllegalStateException) {
                    FailureNotes.record("ui/include-app-playback-save", e)
                    appSaveError = true
                }
            }
        }
        if (appSaveError) {
            Spacer(Modifier.height(8.dp))
            Text("保存失败，请重试", style = RsType.subhead, color = c.warning)
        }
    }
}
