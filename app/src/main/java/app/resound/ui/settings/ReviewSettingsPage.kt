package app.resound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.review.ReviewRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import java.time.LocalDate

/** 每日回顾设置：每天自动生成的时间、是否推送通知；手动生成今天 / 昨天 / 前天 */
@Composable
fun ReviewSettingsPage(repo: ReviewRepository, bottomPadding: Dp) {
    val c = Rs.colors
    val s by repo.state.collectAsState()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Text("每天几点生成", style = RsType.body, color = c.text)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
                listOf(21 * 60, 22 * 60, 23 * 60).forEach { m ->
                    val on = s.dailyAtMinutes == m
                    val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), label = "t")
                    Box(Modifier.clip(CircleShape).background(bg).springClick { repo.setDailyAt(m) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                        Text("${m / 60}:00", style = RsType.subhead, color = if (on) c.text else c.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("推送通知", style = RsType.body, color = c.text)
                    Text("生成后发一条简洁版通知，点开看详细版", style = RsType.caption, color = c.textSecondary)
                }
                val track by animateColorAsState(if (s.pushEnabled) c.accent else c.separator, label = "track")
                val x by animateDpAsState(if (s.pushEnabled) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
                Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { repo.setPushEnabled(!s.pushEnabled) }.padding(2.dp)) {
                    Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
        // 手动生成：选今天 / 昨天 / 前天；更早的日子去“记忆 → 按天”里选
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            var day by remember { mutableStateOf(0L) }
            Text("手动生成", style = RsType.body, color = c.text)
            Text("覆盖这天已有的回顾，不推送通知", style = RsType.caption, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
                listOf(0L to "今天", 1L to "昨天", 2L to "前天").forEach { (d, label) ->
                    val on = day == d
                    val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), label = "d")
                    Box(Modifier.clip(CircleShape).background(bg).springClick { day = d }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                        Text(label, style = RsType.subhead, color = if (on) c.text else c.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Pill(if (s.generating) "生成中…" else "生成${listOf("今天", "昨天", "前天")[day.toInt()]}的回顾", filled = !s.generating) {
                if (!s.generating) repo.generateNow(LocalDate.now().minusDays(day))
            }
            // 生成中显示进行到哪一步；结束后显示没生成或不完整的原因（“已用现有内容生成”只是提醒，用灰色）
            val note = if (s.generating) s.stage else s.lastError
            note?.let { Spacer(Modifier.height(8.dp)); Text(it, style = RsType.caption,
                color = if (s.generating || it.startsWith("已用现有内容生成")) c.textSecondary else c.critical) }
            Spacer(Modifier.height(8.dp))
            Text("更早的日子：在“记忆 → 按天”选中那天，点“生成回顾”。", style = RsType.caption, color = c.textTertiary)
        }
    }
}
