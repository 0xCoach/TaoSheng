package app.resound.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.resound.digest.HeaderEntry
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/** 卡 116：编辑中的一行请求头；值默认遮住，点“看”才显示 */
internal class HeaderRow(name: String, value: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
    var shown by mutableStateOf(false)
}

@Composable
internal fun rememberHeaderRows(load: () -> List<HeaderEntry>): SnapshotStateList<HeaderRow> = remember {
    mutableStateListOf<HeaderRow>().apply { addAll(runCatching(load).getOrDefault(emptyList()).map { HeaderRow(it.name, it.value) }) }
}

/** 填了名字的行才保存；名字和值的合法性由底层校验，不合法时保存会给出原因 */
internal fun SnapshotStateList<HeaderRow>.entries() = filter { it.name.isNotBlank() }.map { HeaderEntry(it.name.trim(), it.value.trim()) }

/**
 * 折叠的“自定义请求头（可选）”区域。有的服务要求带特定请求头（比如别的认证写法）。
 * [onEdit] 每次改动都回调，调用方据此清掉过期的测试结果。
 */
@Composable
internal fun HeadersEditor(rows: SnapshotStateList<HeaderRow>, onEdit: () -> Unit = {}) {
    val c = Rs.colors
    var open by remember { mutableStateOf(false) }
    val count = rows.count { it.name.isNotBlank() }
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when { open -> "自定义请求头 ⌄"; count > 0 -> "自定义请求头：$count 项 ›"; else -> "自定义请求头（可选）›" },
            style = RsType.subhead, color = c.accent,
            modifier = Modifier.springClick { open = !open; if (open && rows.isEmpty()) rows += HeaderRow("", "") })
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { i, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(0.42f)) { HeaderField(row.name, "名称") { row.name = it.trim(); onEdit() } }
                        Box(Modifier.weight(0.58f)) { HeaderField(row.value, "值", secret = !row.shown) { row.value = it; onEdit() } }
                        Text(if (row.shown) "藏" else "看", style = RsType.caption, color = c.textSecondary,
                            modifier = Modifier.springClick { row.shown = !row.shown }.padding(4.dp))
                        Text("✕", style = RsType.subhead, color = c.textTertiary,
                            modifier = Modifier.springClick { rows.removeAt(i); onEdit() }.padding(4.dp))
                    }
                }
                Text("+ 添加一项", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick { rows += HeaderRow("", "") })
                Text("值里可能有密钥：只存在手机安全区，跟着备份一起恢复。填了 Authorization 就不再自动加密钥。",
                    style = RsType.caption, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun HeaderField(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(
        value, { v -> onChange(v.replace("\n", "").replace("\r", "")) }, singleLine = true,
        textStyle = RsType.subhead.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = RsType.subhead, color = c.textTertiary); inner() } },
    )
}

/** 转写服务的请求头：按服务（豆包 / 自定义）分开存，改完点保存 */
@Composable
internal fun TranscribeHeaders(source: app.resound.transcribe.TranscribeSource) {
    val c = Rs.colors
    val settings = if (app.resound.AppGraph.isTranscribeReady) app.resound.AppGraph.transcribeSettings else return
    key(source) {
        val rows = rememberHeaderRows { settings.headers(source.name) }
        var note by remember { mutableStateOf<Result<Unit>?>(null) }
        var dirty by remember { mutableStateOf(false) }
        HeadersEditor(rows) { dirty = true; note = null }
        if (dirty) Text("保存请求头", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick {
            note = runCatching { settings.saveHeaders(source.name, rows.entries()) }
            if (note?.isSuccess == true) dirty = false
        })
        note?.let { r -> Text(r.fold({ "已保存" }, { it.message ?: "没保存成功" }), style = RsType.caption, color = if (r.isSuccess) c.good else c.critical) }
    }
}
