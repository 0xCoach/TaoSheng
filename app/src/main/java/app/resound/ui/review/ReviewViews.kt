package app.resound.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.review.DailyReview
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 回顾列表里的一行：日期 + 一句话简洁版 */
@Composable
fun ReviewRow(review: DailyReview, onOpen: () -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().springClick(onOpen).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(dayTitle(review.date), style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        Spacer(Modifier.height(4.dp))
        Text(review.brief, style = RsType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 回顾详细版：按栏目排列，“一句话”放大做导语；文字可长按复制 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(review: DailyReview, onClose: () -> Unit) {
    val c = Rs.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator)) },
    ) {
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
                Text("${dayTitle(review.date)} · 回顾", style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                val lead = review.sections.firstOrNull { it.title == "一句话" }?.lines?.firstOrNull() ?: review.brief
                Text(lead, style = RsType.title, color = c.text)
                review.sections.filter { it.title != "一句话" && it.lines.isNotEmpty() }.forEach { section ->
                    Spacer(Modifier.height(22.dp))
                    Text(section.title, style = RsType.section, color = c.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    RsCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            section.lines.forEach { line ->
                                Row {
                                    Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape)
                                        .background(if (section.title == "要紧的") c.accent else c.textTertiary))
                                    Spacer(Modifier.size(10.dp))
                                    Text(line, style = RsType.body, color = c.text)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("生成于 ${formatClock(review.generatedAt)}", style = RsType.caption, color = c.textTertiary)
            }
        }
    }
}

private fun dayTitle(date: LocalDate): String {
    val rel = when (ChronoUnit.DAYS.between(date, LocalDate.now())) { 0L -> "今天"; 1L -> "昨天"; 2L -> "前天"; else -> null }
    val d = date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    return if (rel != null) "$rel · $d" else d
}
