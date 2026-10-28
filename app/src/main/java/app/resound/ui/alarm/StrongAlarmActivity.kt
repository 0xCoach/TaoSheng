package app.resound.ui.alarm

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.push.AlarmPlayer
import app.resound.push.StrongReminders
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 强提醒整屏页：锁屏时亮屏盖在锁屏上，像闹钟一样。
 * 按钮“5 分钟后 / 已完成 / 不再响”；音量键 = 5 分钟后，电源键 = 不再响（在 AlarmPlayer 里处理）。
 * 不响了（别处点了按钮、超时停了）就自己关掉。
 */
class StrongAlarmActivity : ComponentActivity() {
    private var itemId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        itemId = intent.getLongExtra(StrongReminders.EXTRA_ID, -1)
        val text = intent.getStringExtra(StrongReminders.EXTRA_TEXT).orEmpty()
        setContent {
            ResoundTheme {
                val ringing by AlarmPlayer.ringing.collectAsState()
                // 只要这条不再响（被按掉或一分钟到了），整屏页就收起
                var seen by remember { mutableStateOf(false) }
                LaunchedEffect(ringing) { if (ringing == itemId) seen = true else if (seen) finish() }
                LaunchedEffect(Unit) { delay(3_000); if (AlarmPlayer.ringing.value != itemId) finish() }
                AlarmScreen(text,
                    onSnooze = { StrongReminders.snooze(this, itemId); finish() },
                    onDone = { StrongReminders.done(this, itemId); finish() },
                    onDismiss = { StrongReminders.dismiss(this, itemId); finish() })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            StrongReminders.snooze(this, itemId); finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun AlarmScreen(text: String, onSnooze: () -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1_000) } }
    val pulse = rememberInfiniteTransition(label = "alarm")
    val swing by pulse.animateFloat(-14f, 14f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "swing")
    val halo by pulse.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo")
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF59A5B), Color(0xFFE8582A), Color(0xFFC2410C))))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White,
                style = RsType.largeTitle.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Light))
            Text("到点了", color = Color.White.copy(alpha = 0.85f), style = RsType.subhead)
            Spacer(Modifier.height(48.dp))
            // 呼吸的光圈 + 摇摆的铃铛
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).scale(halo).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)))
                Box(Modifier.size(108.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
                Canvas(Modifier.size(56.dp).rotate(swing)) { bell(Color.White) }
            }
            Spacer(Modifier.height(40.dp))
            Text(text, color = Color.White, textAlign = TextAlign.Center, maxLines = 4,
                style = RsType.title.copy(fontSize = 24.sp, lineHeight = 32.sp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AlarmButton("5 分钟后", Modifier.weight(1f), filled = false, onClick = onSnooze)
                AlarmButton("已完成", Modifier.weight(1f), filled = true, onClick = onDone)
            }
            Spacer(Modifier.height(14.dp))
            Text("不再响", color = Color.White.copy(alpha = 0.9f), style = RsType.body.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.clip(CircleShape).springClick(onDismiss).padding(horizontal = 24.dp, vertical = 10.dp))
            Spacer(Modifier.height(8.dp))
            Text("音量键 = 5 分钟后 · 电源键 = 不再响", color = Color.White.copy(alpha = 0.65f), style = RsType.caption)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AlarmButton(label: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.2f)).springClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Color(0xFFE8582A) else Color.White, style = RsType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp))
    }
}

/** 实心铃铛 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.bell(color: Color) {
    val w = size.width
    val body = Path().apply {
        moveTo(w * 0.5f, w * 0.1f)
        cubicTo(w * 0.24f, w * 0.1f, w * 0.2f, w * 0.34f, w * 0.2f, w * 0.52f)
        lineTo(w * 0.2f, w * 0.64f); lineTo(w * 0.1f, w * 0.76f); lineTo(w * 0.9f, w * 0.76f); lineTo(w * 0.8f, w * 0.64f)
        lineTo(w * 0.8f, w * 0.52f)
        cubicTo(w * 0.8f, w * 0.34f, w * 0.76f, w * 0.1f, w * 0.5f, w * 0.1f)
        close()
    }
    drawPath(body, color)
    drawLine(color, Offset(w * 0.5f, w * 0.02f), Offset(w * 0.5f, w * 0.1f), w * 0.08f, StrokeCap.Round)
    drawCircle(color, w * 0.09f, Offset(w * 0.5f, w * 0.88f))
}
