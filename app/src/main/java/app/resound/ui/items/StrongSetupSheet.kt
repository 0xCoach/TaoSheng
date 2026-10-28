package app.resound.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.push.StrongSetup
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick

/** 强提醒需要打开的功能：逐项打勾，没开的点“去打开”；从系统设置回来自动重查 */
@Composable
internal fun StrongSetupSheet(onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val items = remember(revision) { StrongSetup.items(context) }
    val missing = items.count { it.ok == false }
    RsSheet("强提醒需要打开的功能",
        if (missing == 0) "能查到的都开好了；标“请确认”的，各家手机放在自己的设置里，点进去看一眼。"
        else "还有 $missing 项没打开，强提醒可能不响、不弹出。点“去打开”，开好回来会自动打勾。",
        onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.background)) {
            items.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(item.ok)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = RsType.body, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text(item.why, style = RsType.caption, color = c.textSecondary)
                    }
                    Spacer(Modifier.width(10.dp))
                    when (item.ok) {
                        true -> Text("已开", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.good)
                        false -> Text("去打开", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                            modifier = Modifier.clip(CircleShape).background(c.accent).springClick { item.open(context) }
                                .padding(horizontal = 14.dp, vertical = 7.dp))
                        null -> Text("去看看", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                            modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { item.open(context) }
                                .padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
                if (i != items.lastIndex) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
        Text("强提醒会用系统闹钟铃声，音量跟着闹钟音量走；勿扰模式下也会响。",
            style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** 绿勾 = 已开，橙色空心 = 没开，灰色问号 = 查不到 */
@Composable
private fun StatusDot(ok: Boolean?) {
    val c = Rs.colors
    when (ok) {
        true -> Box(Modifier.size(22.dp).clip(CircleShape).background(c.good), contentAlignment = Alignment.Center) {
            Text("✓", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
        false -> Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, c.accent, CircleShape))
        null -> Box(Modifier.size(22.dp).clip(CircleShape).background(c.separator), contentAlignment = Alignment.Center) {
            Text("?", style = RsType.caption.copy(fontWeight = FontWeight.Bold), color = c.textSecondary)
        }
    }
}

/** 打开强提醒后、设置还没开全时显示的一行提示，点了打开清单 */
@Composable
internal fun StrongSetupHint(onOpen: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val missing = remember(revision) { StrongSetup.missing(context) }
    if (missing == 0) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).springClick(onOpen).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("还有 $missing 项设置没打开，可能响不了", style = RsType.caption, color = c.warning, modifier = Modifier.weight(1f))
        Text("去设置 ›", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent)
    }
}
