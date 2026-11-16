package app.resound.ui.today

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.digest.MemoryRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard


@Composable
internal fun EmptyTimeline(memory: MemoryRepository) {
    val c = Rs.colors
    val digest by memory.state.collectAsStateWithLifecycle()
    RsCard(padding = PaddingValues(vertical = 32.dp, horizontal = 20.dp)) {
        Text(
            if (digest.pendingLines > 0) "有 ${digest.pendingLines} 句原话等着整理" else "这一天还没有整理出事件",
            style = RsType.subhead,
            color = c.textSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "录到人声 → 转写成原话 → 说完约 10 分钟后整理成一件件事",
            style = RsType.caption,
            color = c.textTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
