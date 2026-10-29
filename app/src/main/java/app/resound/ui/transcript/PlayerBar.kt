package app.resound.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val total = LinePlayer.timeline?.total ?: 1L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = dragging ?: (LinePlayer.elapsed.toFloat() / total).coerceIn(0f, 1f)
    // 拖进度条时，字幕和“第几段”跟着手指走；松手才真正跳过去
    val dragAt = dragging?.let { f -> LinePlayer.timeline?.locate((f * total).toLong()) }
    val idx = if (dragAt != null) {
        val (seg, at) = dragAt
        lines.indexOfLast { it.segmentId == seg && it.startAt <= at }.takeIf { it >= 0 } ?: lines.indexOfFirst { it.segmentId == seg }.coerceAtLeast(0)
    } else lines.indexOfFirst { it.id == LinePlayer.currentLineId }.coerceAtLeast(0)
    fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    val segs = LinePlayer.segmentOrder.size
    val segIdx = dragAt?.let { LinePlayer.segmentOrder.indexOf(it.first).coerceAtLeast(0) } ?: LinePlayer.segmentIndex
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
        // 阴影加深一点 + 一圈细描边：压在白色卡片上也能分出层次
        Modifier.fillMaxWidth().shadow(if (c.isDark) 0.dp else 22.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(RoundedCornerShape(24.dp)).background(c.card)
            .border(1.dp, if (c.isDark) Color.White.copy(alpha = 0.10f) else c.separator, RoundedCornerShape(24.dp)).animateContentSize(),
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
                    Text((if (segs > 1) "第 ${segIdx + 1}/$segs 段 · " else "") + "${mmss((frac * total).toLong())} / ${mmss(total)}",
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
            // 展开（三行）：顶行 段数 · 回到来源 · 收起 · 关闭；三行字幕；进度条；控制行 下一段 · −15 播放 +15 · 倍速
            Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (segs > 1) "第 ${segIdx + 1}/$segs 段录音" else "正在播放原声", style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                            color = c.textTertiary, maxLines = 1)
                        sourceLabel?.let {
                            Text(" · ", style = RsType.caption, color = c.textTertiary)
                            Text(it, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent, maxLines = 1,
                                modifier = Modifier.clip(CircleShape).springClick { goBack() }.padding(vertical = 4.dp))
                        }
                    }
                    IconCircle(c.background, onClick = { expanded = false }) { ChevronDownIcon(c.textTertiary) }
                    Spacer(Modifier.width(6.dp))
                    IconCircle(c.background, onClick = { LinePlayer.stop() }) { CloseIcon(c.textTertiary) }
                }
                Column(Modifier.fillMaxWidth().height(92.dp), verticalArrangement = Arrangement.Center) {
                    // 三行一直排满：开头没有上一句时当前句往上顶，结尾没有下一句时往下沉
                    val first = (idx - 1).coerceAtMost(lines.size - 3).coerceAtLeast(0)
                    (first until first + 3).forEach { k ->
                        val main = k == idx
                        Text(lines.getOrNull(k)?.text ?: "", maxLines = if (main) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = if (main) RsType.body.copy(fontWeight = FontWeight.SemiBold) else RsType.caption,
                            color = if (main) c.text else c.textTertiary, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                }
                SeekBar(frac, onDrag = { dragging = it }, onDone = { f -> LinePlayer.seekFraction(f); dragging = null })
                Row {
                    Text(mmss((frac * total).toLong()), style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                    Text(mmss(total), style = RsType.caption, color = c.textTertiary)
                }
                Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    // 左：下一段（图标 + 文字）；只有一段录音时不显示
                    if (segs > 1 && LinePlayer.segmentIndex < LinePlayer.segmentOrder.lastIndex)
                        Row(Modifier.align(Alignment.CenterStart).height(32.dp).clip(CircleShape).background(c.accentSoft)
                            .springClick { LinePlayer.skipSegment() }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            NextIcon(c.accent)
                            Spacer(Modifier.width(5.dp))
                            Text("下一段", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        SkipCircle(back = true) { LinePlayer.skipBy(-15_000) }
                        Spacer(Modifier.width(14.dp))
                        PlayButton(50.dp)
                        Spacer(Modifier.width(14.dp))
                        SkipCircle(back = false) { LinePlayer.skipBy(15_000) }
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) { SpeedChip() }
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
