package app.resound.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.transcribe.ReviewFlag
import app.resound.transcribe.ReviewItem
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/**
 * 低置信转写复核：语速异常/重试过/声纹存疑/同音纠正过的原话行。
 * “已读”只是确认看过；听不准的到原话里重转或改。
 */
@Composable
fun TranscriptReviewScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val repo = AppGraph.transcriptReview
    val scope = rememberCoroutineScope()
    val rows by repo.pending().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("复核", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            if (rows.isEmpty()) item {
                RsCard {
                    Text("没有需要复核的原话", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text("语速异常、重试过多次、说话人存疑或同音纠正过的句子会出现在这里；确认看过点“已读”即不再列出。", style = RsType.subhead, color = c.textSecondary)
                }
            }
            items(rows, key = { it.lineId }) { item -> ReviewCard(item) { scope.launch { repo.markReviewed(item.lineId) } } }
        }
    }
}

@Composable
private fun ReviewCard(item: ReviewItem, onRead: () -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatStamp(item.startAt), style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            Pill("已读", filled = false, onClick = onRead)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.text.ifBlank { "（空）" }, style = RsType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
        if (!item.speakerTag.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(item.speakerTag, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.flags.forEach { flag ->
                Box(Modifier.clip(CircleShape).background(if (flag != ReviewFlag.CorrectedText) c.accentSoft else c.track).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(flag.label, style = RsType.caption, color = if (flag != ReviewFlag.CorrectedText) c.accent else c.textSecondary)
                }
            }
        }
    }
}
