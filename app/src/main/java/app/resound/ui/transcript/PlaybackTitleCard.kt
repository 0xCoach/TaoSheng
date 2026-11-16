package app.resound.ui.transcript

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsToggle
import app.resound.ui.verify.springClick
import androidx.compose.material3.Text

/** 「记录播放标题」开关与通知使用权引导，放在原话设置的外放区域。 */
@Composable
internal fun PlaybackTitleCard() {
    if (!app.resound.AppGraph.isPlaybackTitleReady) return
    val titleOn by app.resound.AppGraph.playbackTitleSettings.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var listenerOn by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    var saveError by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerOn = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val c = Rs.colors
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("记录播放标题", style = RsType.body, color = c.text)
            Text(
                if (titleOn) "记下手机正在播放的 App 和节目标题，用来标注外放声音来自哪里；不读通知内容"
                else "关闭时不读取、不记录播放标题；外放片段整理时只标「手机在外放」。之前记下的标题会保留，可用隐私删除清掉",
                style = RsType.caption, color = c.textSecondary,
            )
        }
        RsToggle(titleOn) { on ->
            saveError = false
            try {
                app.resound.AppGraph.playbackTitleSettings.setEnabled(on)
            } catch (e: IllegalStateException) {
                app.resound.diagnostics.FailureNotes.record("ui/playback-title-save", e)
                saveError = true
            }
        }
    }
    if (saveError) {
        Text("保存失败，请重试", style = RsType.caption, color = c.warning)
    }
    if (titleOn && app.resound.AppGraph.isDigestSettingsReady) {
        val includeApp by app.resound.AppGraph.digestSettings.includeAppPlayback.collectAsStateWithLifecycle()
        var appSaveError by remember { mutableStateOf(false) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("整理时带上 App 与播放信息", style = RsType.body, color = c.text)
                Text(
                    if (includeApp) "整理时把正在播放的 App 和节目名发给大模型，用来标外放声音来自哪里"
                    else "关掉后整理不再把 App 和节目名发给大模型；仍会标「手机在外放」",
                    style = RsType.caption, color = c.textSecondary,
                )
            }
            RsToggle(includeApp) { on ->
                appSaveError = false
                try {
                    app.resound.AppGraph.digestSettings.setIncludeAppPlayback(on)
                } catch (e: IllegalStateException) {
                    app.resound.diagnostics.FailureNotes.record("ui/include-app-playback-save", e)
                    appSaveError = true
                }
            }
        }
        if (appSaveError) {
            Text("保存失败，请重试", style = RsType.caption, color = c.warning)
        }
    }
    if (titleOn && !listenerOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("需要通知使用权才能记下正在播放什么", style = RsType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(
                "去开启",
                style = RsType.body,
                color = c.accent,
                modifier = Modifier.springClick {
                    val intent = if (Build.VERSION.SDK_INT >= 30) {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(
                                context.packageName,
                                "app.resound.screentime.PlaybackListenerService",
                            ).flattenToString(),
                        )
                    } else {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            )
        }
    }
}
