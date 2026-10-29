package app.resound.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

private val SPEEDS = listOf(1f, 1.5f, 2f)

/**
 * 迷你播放条：播放/暂停 · 播到的时刻 · 倍速 · 关闭。用独立的浮层窗口，详情面板打开时也盖在最上面；
 * 每次新开始播放重建一次，保证在最新打开的面板之上。不抢触摸，面板照常能滑。
 */
@Composable
fun PlayerBar(bottomPadding: Dp) {
    val context = LocalContext.current
    LinePlayer.notice?.let { msg ->
        LaunchedEffect(msg) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show(); LinePlayer.notice = null }
    }
    if (!LinePlayer.active) return
    key(LinePlayer.session) {
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp)) {
                Bar()
            }
        }
    }
}

@Composable
private fun Bar() {
    val c = Rs.colors
    var expanded by remember { mutableStateOf(false) }
    val lines = LinePlayer.allLines
    val idx = lines.indexOfFirst { it.id == LinePlayer.currentLineId }.coerceAtLeast(0)
    val total = LinePlayer.timeline?.total ?: 1L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = dragging ?: (LinePlayer.elapsed.toFloat() / total).coerceIn(0f, 1f)
    fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    val segs = LinePlayer.segmentOrder.size
    val sourceLabel = when (LinePlayer.source) { is PlaySource.Event -> "回到这件事 ›"; is PlaySource.Transcript -> "回到原话 ›"; null -> null }
    fun goBack() {
        when (val s = LinePlayer.source) {
            is PlaySource.Event -> PlayerNav.openEvent = s.event
            is PlaySource.Transcript -> { PlayerNav.transcriptFocus = s.date to (lines.getOrNull(idx)?.segmentId ?: 0L); app.resound.ui.ShellNav.openSettings(app.resound.ui.settings.SettingsPage.Transcript) }
            null -> Unit
        }
        expanded = false
    }
    Column(
        Modifier.fillMaxWidth().shadow(if (c.isDark) 0.dp else 16.dp, RoundedCornerShape(24.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(24.dp)).background(c.card).animateContentSize(),
    ) {
        if (!expanded) {
            // 收起：顶部细进度线 + 一行字幕；点字幕展开成完整卡片
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.accentSoft)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.accent))
            }
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).springClick { expanded = true }) {
                    Text(lines.getOrNull(idx)?.text ?: "正在播放原声", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text((if (segs > 1) "第 ${LinePlayer.segmentIndex + 1}/$segs 段 · " else "") + "${mmss(LinePlayer.elapsed)} / ${mmss(total)}",
                        style = RsType.caption, color = c.textTertiary, maxLines = 1)
                }
                SpeedChip()
                Spacer(Modifier.width(6.dp))
                // 下一段、关闭：同尺寸的浅底圆钮，线条图标
                if (segs > 1 && LinePlayer.segmentIndex < LinePlayer.segmentOrder.lastIndex) {
                    IconCircle(c.accentSoft, onClick = { LinePlayer.skipSegment() }) { NextIcon(c.accent) }
                    Spacer(Modifier.width(6.dp))
                }
                IconCircle(c.background, onClick = { LinePlayer.stop() }) { CloseIcon(c.textTertiary) }
            }
        } else {
            // 展开：三行字幕 · 可拖进度条 · −15 播放 +15 · 倍速；底部段数、下一段、回到来源
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在播放原声", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.weight(1f))
                    Text("收起 ⌄", style = RsType.caption, color = c.textTertiary, modifier = Modifier.clip(CircleShape).springClick { expanded = false }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Column(Modifier.fillMaxWidth().height(78.dp), verticalArrangement = Arrangement.Center) {
                    listOf(idx - 1, idx, idx + 1).forEach { k ->
                        val main = k == idx
                        Text(lines.getOrNull(k)?.text ?: "", maxLines = if (main) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = if (main) RsType.body.copy(fontWeight = FontWeight.SemiBold) else RsType.caption,
                            color = if (main) c.text else c.textTertiary, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                SeekBar(frac, onDrag = { dragging = it }, onDone = { f -> LinePlayer.seekFraction(f); dragging = null })
                Row(Modifier.padding(top = 2.dp)) {
                    Text(mmss((frac * total).toLong()), style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                    Text(mmss(total), style = RsType.caption, color = c.textTertiary)
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        SkipCircle(back = true) { LinePlayer.skipBy(-15_000) }
                        Spacer(Modifier.width(18.dp))
                        PlayButton(50.dp)
                        Spacer(Modifier.width(18.dp))
                        SkipCircle(back = false) { LinePlayer.skipBy(15_000) }
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) { SpeedChip() }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (segs > 1) {
                        Text("第 ${LinePlayer.segmentIndex + 1}/$segs 段", style = RsType.caption, color = c.textTertiary)
                        if (LinePlayer.segmentIndex < LinePlayer.segmentOrder.lastIndex) {
                            Spacer(Modifier.width(8.dp))
                            IconCircle(c.accentSoft, onClick = { LinePlayer.skipSegment() }) { NextIcon(c.accent) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    sourceLabel?.let { Text(it, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                        modifier = Modifier.clip(CircleShape).springClick { goBack() }.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    Text("关闭", style = RsType.caption, color = c.textTertiary, modifier = Modifier.clip(CircleShape).springClick { LinePlayer.stop() }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayButton(size: androidx.compose.ui.unit.Dp) = PlayCircle(size, LinePlayer.playing) { LinePlayer.toggle() }

@Composable
private fun SpeedChip() {
    val c = Rs.colors
    val s = LinePlayer.speed
    Text(if (s == 1f) "1×" else if (s == 1.5f) "1.5×" else "2×", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.height(32.dp).clip(CircleShape).background(c.accentSoft).springClick { LinePlayer.changeSpeed(SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.size]) }
            .padding(horizontal = 11.dp).wrapContentHeight(Alignment.CenterVertically))
}
