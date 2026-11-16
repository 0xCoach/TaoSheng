package app.resound.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.resound.screentime.ScreenTimeReport
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsSheet
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun ScreenTimeSheet(date: LocalDate, report: ScreenTimeReport, onDismiss: () -> Unit) {
    val c = Rs.colors
    val day = date.format(DateTimeFormatter.ofPattern("M月d日"))
    RsSheet(
        title = "屏幕时间",
        subtitle = "$day · 共 ${screenTimeDurationText(report.totalMinutes)}",
        onDismiss = onDismiss,
    ) {
        SheetBlock("各 App 用时") {
            val maxMinutes = report.apps.firstOrNull()?.minutes ?: 0
            report.apps.forEach { app -> ScreenTimeAppRow(app.name, app.minutes, maxMinutes) }
        }
        SheetBlock("时间线") {
            report.result.sessions.sortedByDescending { it.from }.forEach { session ->
                val minutes = screenTimeMinutes(session.until - session.from)
                Text(
                    "${screenTimeClockRange(session.from, session.until)}  ${session.name}  $minutes 分",
                    style = RsType.subhead,
                    color = c.text,
                )
            }
        }
        if (report.result.devicePlayback.isNotEmpty()) {
            SheetBlock("设备播放记录") {
                Text("只表示手机在播放，不代表你在听", style = RsType.caption, color = c.textSecondary)
                report.result.devicePlayback.forEach { play ->
                    val title = play.title?.let { "${play.name}《$it》" } ?: play.name
                    Text(
                        "${screenTimeClockRange(play.from, play.until)}  $title",
                        style = RsType.subhead,
                        color = c.text,
                    )
                    val subtitle = play.subtitle
                    if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, style = RsType.caption, color = c.textSecondary)
                    }
                }
            }
        }
        if (report.result.gaps.isNotEmpty()) {
            SheetBlock("数据缺口") {
                report.result.gaps.forEach { gap ->
                    Text(
                        "${screenTimeClockRange(gap.from, gap.until)}  ${gap.fix}",
                        style = RsType.subhead,
                        color = c.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetBlock(title: String, content: @Composable () -> Unit) {
    val c = Rs.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = RsType.headline, color = c.text)
        content()
    }
    Spacer(Modifier.height(8.dp))
}
