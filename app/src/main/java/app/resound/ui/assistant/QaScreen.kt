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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.qa.QaMode
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

/** 有出处问答：回答里的 [#n] 对应下方编号来源；两种模式切换，无证据/未配置如实显示。 */
@Composable
fun QaScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val c = Rs.colors
    val qa = AppGraph.groundedQA
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    val answer by qa.last.collectAsStateWithLifecycle()
    val busy by qa.busy.collectAsStateWithLifecycle()
    val mode by qa.mode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RsType.largeTitle, color = c.textSecondary, modifier = Modifier.springClick(onBack).padding(end = 10.dp))
            Text("问答", style = RsType.largeTitle, color = c.text, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QaMode.entries.forEach { m ->
                val on = m == mode
                Box(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.track).springClick { qa.mode.value = m }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(m.label, style = RsType.subhead, color = if (on) c.accent else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.track).padding(horizontal = 14.dp, vertical = 10.dp)) {
                BasicTextField(question, { question = it }, Modifier.fillMaxWidth(),
                    textStyle = RsType.body.copy(color = c.text), singleLine = true,
                    decorationBox = { inner ->
                        if (question.isEmpty()) Text("问你的记录：哪些产品研究过又没推进？", style = RsType.body, color = c.textTertiary)
                        inner()
                    })
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (busy) "…" else "问", filled = true) {
                if (!busy && question.isNotBlank()) scope.launch { qa.ask(question) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            answer?.let { a ->
                RsCard(padding = PaddingValues(16.dp)) {
                    when {
                        a.needsService -> Badge("等待配置服务", c, warn = true)
                        a.noEvidence -> Badge("记录里没有找到", c, warn = true)
                        !a.grounded -> Badge("含补充内容（非全部来自你的记录）", c, warn = true)
                        else -> Badge("全部出自你的记录", c)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(a.text, style = RsType.body, color = c.text)
                }
                if (a.sources.isNotEmpty()) {
                    Text("来源（回答里的 [#n] 对应这里）", style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    a.sources.forEach { s ->
                        RsCard(padding = PaddingValues(12.dp)) {
                            Text("[#${s.no}] ${s.kind} · ${formatStamp(s.at)}", style = RsType.caption, color = c.textTertiary)
                            Spacer(Modifier.height(4.dp))
                            Text("${s.title}：${s.quote}", style = RsType.subhead, color = c.textSecondary, maxLines = 3)
                        }
                    }
                }
            } ?: RsCard(padding = PaddingValues(16.dp)) {
                Text("跨你的原话、事件、灵感和决策检索后回答，每条结论带来源编号。", style = RsType.body, color = c.text)
                Spacer(Modifier.height(6.dp))
                Text("“仅查我的记录”模式只用你的资料；“记录+通用知识”会先查记录、不足再补充并标注。没找到证据时直说，不编造。", style = RsType.subhead, color = c.textSecondary)
            }
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun Badge(text: String, c: app.resound.ui.theme.RsColors, warn: Boolean = false) {
    Box(Modifier.clip(CircleShape).background(if (warn) c.track else c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = RsType.caption, color = if (warn) c.warning else c.accent)
    }
}
