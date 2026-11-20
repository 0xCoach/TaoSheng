package app.resound.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.transcribe.PlaybackMode
import app.resound.transcribe.PlaybackOverride
import app.resound.ui.screentime.AppIcon
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.topic.ChoicePill
import app.resound.ui.verify.springClick

private val modeTitle = mapOf(PlaybackMode.Digest to "转写并整理", PlaybackMode.Transcribe to "只转写", PlaybackMode.Skip to "不记")
private val ruleTitle = mapOf(PlaybackOverride.Default to "跟默认", PlaybackOverride.Digest to "转写并整理",
    PlaybackOverride.Transcribe to "只转写", PlaybackOverride.Skip to "不记", PlaybackOverride.NotPlayback to "不算外放")

/**
 * 卡 205：外放的声音。默认三选一（默认转写并整理，免得漏话），下面按 App 例外，点一行原地展开选项。
 * 只标记不过滤：外放期间的句子在原话里带“外放”，整理时写成“看/听 ×× 时听到”。
 */
@Composable
internal fun PlaybackSkipCard(local: Boolean, topDivider: Boolean = true) {
    val settings = remember { runCatching { app.resound.AppGraph.playbackSettings }.getOrNull() } ?: return
    val prefs by settings.preferences.collectAsStateWithLifecycle()
    val c = Rs.colors
    if (!local && topDivider) Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    Text("外放的声音", style = RsType.body, color = c.text)
    Text(
        when (prefs.mode) {
            PlaybackMode.Digest -> "手机外放时录到的也照常转写、整理；视频里的话标成“外放”，不会当成你说的"
            PlaybackMode.Transcribe -> "外放时录到的转写进原话，但不整理成事件；原话里可单段放行"
            PlaybackMode.Skip -> "外放时录到的先不转写，原话里可单段选择转写；外放时有人说话可能会漏"
        },
        style = RsType.caption, color = c.textSecondary,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaybackMode.entries.forEach { m -> ChoicePill(modeTitle.getValue(m), prefs.mode == m, Modifier.weight(1f)) { settings.setDefault(m) } }
    }

    // 最近两周外放过的 App（按时长），加上已经设过例外的
    val recent by produceState(emptyList<Pair<String, String>>(), prefs.apps.keys) {
        val now = System.currentTimeMillis()
        val played = runCatching { app.resound.AppGraph.screenTimeReader?.sessions(now - 14 * 86_400_000L, now)?.devicePlayback }.getOrNull().orEmpty()
        val byTime = played.groupBy { it.pkg }.map { (pkg, rows) -> Triple(pkg, rows.first().name, rows.sumOf { it.until - it.from }) }.sortedByDescending { it.third }
        val names = byTime.associate { it.first to it.second }
        value = (byTime.map { it.first } + prefs.apps.keys).distinct().take(12).map { it to (names[it] ?: it.substringAfterLast('.')) }
    }
    if (recent.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("按 App", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
        var open by remember { mutableStateOf<String?>(null) }
        recent.forEach { (pkg, name) ->
            val rule = prefs.apps[pkg] ?: PlaybackOverride.Default
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                .background(if (open == pkg) c.fill else androidx.compose.ui.graphics.Color.Transparent)) {
                Row(Modifier.fillMaxWidth().springClick { open = if (open == pkg) null else pkg }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, name, 24)
                    Spacer(Modifier.width(10.dp))
                    Text(name, style = RsType.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(if (rule == PlaybackOverride.Default) "跟默认 · ${modeTitle.getValue(prefs.mode)}" else ruleTitle.getValue(rule),
                        style = RsType.subhead, color = if (rule == PlaybackOverride.Default) c.textTertiary else c.accent)
                }
                AnimatedVisibility(open == pkg) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaybackOverride.entries.forEach { r ->
                            ChoicePill(ruleTitle.getValue(r), rule == r, Modifier.width(if (r == PlaybackOverride.Digest) 96.dp else 74.dp)) {
                                settings.setApp(pkg, r); open = null
                            }
                        }
                    }
                }
            }
        }
        Text("短视频之类刷得多的 App 可以设成“不记”。“不算外放”用于某个 App 总被误当成在外放",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
    }
    Text("外放内容转写也按时长计费、整理也会多用一些额度",
        style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
}
