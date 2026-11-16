package app.resound.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsToggle

/** 「外放声音时不转写」和「外放内容也参与整理」，放在原话设置的外放区域。 */
@Composable
internal fun PlaybackSkipCard(local: Boolean) {
    if (!app.resound.AppGraph.isPlaybackSkipReady) return
    val skip by app.resound.AppGraph.playbackSkip.enabled.collectAsStateWithLifecycle()
    val c = Rs.colors
    if (!local) Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("外放声音时不转写", style = RsType.body, color = c.text)
            Text(
                if (skip) "手机扬声器在放视频、音乐时录到的先不转写，原话里可单段选择只转写或转写并整理；戴耳机不受影响"
                else "外放时录到的也照常转写，视频里的声音可能混进原话",
                style = RsType.caption, color = c.textSecondary,
            )
        }
        RsToggle(skip) { app.resound.AppGraph.playbackSkip.setEnabled(it) }
    }
    if (!app.resound.AppGraph.isPlaybackDigestReady) return
    val includeDigest by app.resound.AppGraph.playbackDigestSettings.includePlaybackDigest.collectAsStateWithLifecycle()
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("外放内容也参与整理", style = RsType.body, color = c.text)
            Text(
                if (includeDigest) "外放片段转写后也会整理成事件；新录的才生效"
                else "外放片段默认不进整理，原话里可单段放行；新录的才生效",
                style = RsType.caption, color = c.textSecondary,
            )
        }
        RsToggle(includeDigest) { app.resound.AppGraph.playbackDigestSettings.setIncludePlaybackDigest(it) }
    }
}
