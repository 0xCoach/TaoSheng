package app.resound.ui.today

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.resumeAt
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

private val PAUSE_MINUTES = listOf(15, 30, 60, 120)
private val FORGET_MINUTES = listOf(10, 30, 60)

/** 点主卡上的“正在聆听”：暂停（选时长）/ 恢复 / 开始；删除最近一段（点两次才删） */
@Composable
fun ListenSheet(capture: CaptureController, startOnForget: Boolean = false, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by capture.status.collectAsState()
    var pauseFor by remember { mutableIntStateOf(30) }
    var forgetFor by remember { mutableIntStateOf(10) }
    var confirm by remember { mutableStateOf(startOnForget) }
    var busy by remember { mutableStateOf(false) }
    var classFor by remember { mutableIntStateOf(120) }
    val listening = status.state == CaptureState.Listening || status.state == CaptureState.Recording

    RsSheet(
        when (status.state) {
            CaptureState.Paused -> status.quietName?.let { "${it}中" } ?: "已暂停"
            CaptureState.Stopped, CaptureState.NoPermission -> "没在聆听"
            else -> "正在聆听"
        },
        when {
            status.state == CaptureState.Paused -> resumeAt(status.pausedUntil)?.let { "到 ${app.resound.ui.verify.formatUpcoming(it)} 自动恢复" }
            listening -> "只在有人说话时录音"
            else -> null
        },
        onDismiss = onDismiss,
    ) {
        when {
            listening -> {
                Label("暂停多久")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAUSE_MINUTES.forEach { m -> Choice(if (m < 60) "$m 分钟" else "${m / 60} 小时", m == pauseFor, Modifier.weight(1f)) { pauseFor = m } }
                }
                ActionButton("暂停", ActionKind.Primary) { capture.pause(pauseFor); onDismiss() }
            }
            status.state == CaptureState.Paused -> ActionButton("现在恢复", ActionKind.Primary) { capture.resume(); onDismiss() }
            else -> ActionButton("开始聆听", ActionKind.Primary) { capture.start(); onDismiss() }
        }

        if (AppGraph.isAsrSpeedReady) {
            val speed = AppGraph.asrSpeed
            val separate by speed.separateSpeakers.collectAsState()
            val classMode by speed.classMode.collectAsState()
            if (separate) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(c.separator))
                Label("课堂模式")
                Text(app.resound.ui.transcript.classModeLine(classMode, System.currentTimeMillis()), style = RsType.caption,
                    color = if (classMode.enabled) c.accent else c.textTertiary)
                if (classMode.enabled) ActionButton("结束课堂模式", ActionKind.Secondary) { speed.stopClassMode(); onDismiss() }
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CLASS_MINUTES.forEach { m -> Choice(if (m == 0) "手动关" else "${m / 60} 小时", m == classFor, Modifier.weight(1f)) { classFor = m } }
                    }
                    ActionButton("开始课堂模式", ActionKind.Secondary) { speed.startClassMode(classFor.takeIf { it > 0 }); onDismiss() }
                }
            }
        }

        if (AppGraph.isPrivacyReady) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(c.separator))
            Label("刚才的话不想留")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FORGET_MINUTES.forEach { m -> Choice("最近 $m 分钟", m == forgetFor, Modifier.weight(1f)) { forgetFor = m; confirm = false } }
            }
            ActionButton(
                when { busy -> "正在删除…"; confirm -> "确定删除最近 $forgetFor 分钟？再点一次"; else -> "删除最近 $forgetFor 分钟" },
                ActionKind.Danger, enabled = !busy,
            ) {
                if (!confirm) { confirm = true; return@ActionButton }
                busy = true
                scope.launch {
                    val r = runCatching { AppGraph.privacy.deleteRecent(forgetFor) }
                    busy = false
                    Toast.makeText(context, r.fold({ if (it.segments == 0) "最近 $forgetFor 分钟没有录音" else "已删除 ${it.segments} 段录音和相关记录" }, { "没删成功，请再试一次" }),
                        Toast.LENGTH_SHORT).show()
                    if (r.isSuccess) onDismiss()
                }
            }
            Text("录音、原话和由它们整理出的内容一起彻底删除，无法恢复。", style = RsType.caption, color = c.textTertiary)
        }
    }
}

/** 课堂模式时长；0 = 直到手动关 */
private val CLASS_MINUTES = listOf(60, 120, 180, 0)

@Composable
private fun Label(text: String) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary)
}

@Composable
private fun Choice(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.background, spring(stiffness = Spring.StiffnessMediumLow), label = "listenChoice")
    val fg by animateColorAsState(if (on) Color.White else c.textSecondary, label = "listenChoiceFg")
    Box(modifier.springClick(onClick).clip(CircleShape).background(bg).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}
