package app.resound.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.diagnostics.FailureNotes
import app.resound.update.ChangelogRules
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick

/** 安装包里的 CHANGELOG.md（打包时从仓库根目录复制）；读一次缓存起来 */
internal object Changelog {
    @Volatile private var cached: List<ChangelogRules.Entry>? = null
    fun entries(context: Context): List<ChangelogRules.Entry> = cached ?: runCatching {
        context.assets.open("CHANGELOG.md").bufferedReader().use { ChangelogRules.parse(it.readText()) }
    }.onFailure { FailureNotes.record("ui/settings/Changelog.kt:entries", it) }.getOrDefault(emptyList()).also { cached = it }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}

/** 版本更新页里的“更新日志”：每版一行，当前版本默认展开；先列 8 版，可往前看 */
@Composable
internal fun ChangelogCard(current: String) {
    val c = Rs.colors
    val context = LocalContext.current
    val all = remember { ChangelogRules.visible(Changelog.entries(context), current) }
    if (all.isEmpty()) return
    var shown by remember { mutableIntStateOf(8) }
    var open by remember { mutableStateOf(setOf(ChangelogRules.entry(all, current)?.version ?: all.first().version)) }
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text("更新日志", style = RsType.section, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        all.take(shown).forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            val expanded = e.version in open
            val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "logArrow")
            Column(Modifier.fillMaxWidth().springClick { open = if (expanded) open - e.version else open + e.version }.padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.resound.ui.settings.displayVersion(e.version), style = RsType.headline, color = c.text)
                    if (e.version == current) {
                        Spacer(Modifier.width(8.dp))
                        Text("当前", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                            modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    e.date?.let { Text(it, style = RsType.caption, color = c.textTertiary) }
                    Spacer(Modifier.width(6.dp))
                    Text("⌄", style = RsType.headline, color = c.textTertiary, modifier = Modifier.rotate(arrow))
                }
                if (!expanded && e.title.isNotBlank()) Text(e.title, style = RsType.subhead, color = c.textSecondary, maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp))
                AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Box(Modifier.padding(top = 8.dp)) { app.resound.ui.ask.AnswerText(e.body) }
                }
            }
        }
        if (all.size > shown) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("查看更早的版本", filled = false) { shown += 10 } }
        }
    }
}

/** 某一版的完整说明（首页“看看更新了什么”点开） */
@Composable
internal fun ChangelogSheet(entry: ChangelogRules.Entry, onDismiss: () -> Unit) {
    val c = Rs.colors
    RsSheet("涛声 ${displayVersion(entry.version)} 更新了什么", entry.date, onDismiss = onDismiss) {
        app.resound.ui.ask.AnswerText(entry.body)
        Spacer(Modifier.height(4.dp))
        Text("以后可在“设置 → 版本更新 → 更新日志”回看。", style = RsType.caption, color = c.textTertiary)
    }
}
