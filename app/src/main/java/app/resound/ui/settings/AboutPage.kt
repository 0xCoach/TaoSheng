package app.resound.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.resound.AppGraph
import app.resound.ui.verify.Pill
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.R
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard

/** 功能摘要；内容写在 App 里，没网也能看 */
private val HIGHLIGHTS = listOf(
    "🎙" to "全天在听，只录人声，安静时不录",
    "📴" to "可用本地模型离线转写，声音不出手机",
    "🧑‍🤝‍🧑" to "声纹认人，分得清谁在说",
    "✅" to "原话里答应的事自动变成待办",
    "📥" to "网页、文字、截图都能分享进收集箱，图片在本地识字",
    "💡" to "灵感收件箱：标记、分类、合并，成熟后转待办或项目",
    "⚖️" to "决定留版本历史，生效、否决、替代都附原话证据",
    "🔁" to "说过要做却没下文的事主动提醒，周复盘单列反复未推进项",
    "💬" to "打字问一句就能找回，答案附原话出处",
    "🤖" to "授权后把灵感派给外部 AI 做研究，结果回写到来源",
    "⌨️" to "语音输入时连同聊天上下文一起记下",
    "🌙" to "每天、每周自动回顾",
    "☁️" to "每天自动备份到坚果云和本机",
)

/** 设置 → 关于 → 关于声声不息 */
@Composable
fun AboutPage(version: String?, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var devUnlocked by remember { mutableStateOf(prefs.getBoolean(DEV_UNLOCKED, false)) }
    var taps by remember { mutableIntStateOf(0) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(20.dp))) {
                Image(painterResource(R.drawable.ic_fg), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text("声声不息", style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("替你听，替你记，替你想。", style = RsType.subhead, color = c.textSecondary)
            if (version != null) {
                Spacer(Modifier.height(4.dp))
                // 连点 7 下打开开发者选项（预览版开关）；普通用户看不到
                Text("版本 ${displayVersion(version)}", style = RsType.caption, color = c.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(interactionSource = null, indication = null) {
                        if (devUnlocked) return@clickable
                        taps++
                        when {
                            taps >= 7 -> { devUnlocked = true; prefs.edit().putBoolean(DEV_UNLOCKED, true).apply()
                                Toast.makeText(context, "已打开开发者选项", Toast.LENGTH_SHORT).show() }
                            taps >= 4 -> Toast.makeText(context, "再点 ${7 - taps} 下打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }

        if (devUnlocked && AppGraph.isUpdaterReady) PreviewCard()

        AboutCard("声声不息是什么") {
            Text("一部安卓手机就够：全天收音 → 自动转写 → 认出谁在说 → AI 整理成待办、约定和记忆；分享进来的链接和截图也统一收进收集箱。",
                style = RsType.body, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text("说过的、决定过的、要做的事，都能查得到、推得动。不用买硬件，不用注册账号，数据只在你自己手机里。", style = RsType.body, color = c.textSecondary)
        }

        AboutCard("主要能做什么") {
            HIGHLIGHTS.forEachIndexed { i, (icon, text) ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(icon, style = RsType.body, modifier = Modifier.width(28.dp))
                    Text(text, style = RsType.body, color = c.text)
                }
            }
        }

        AboutCard("隐私") {
            Text("不用注册账号，没有我们的服务器；密钥存在系统安全区；录音转写后 24 小时自动删除。",
                style = RsType.body, color = c.text)
        }

        AboutCard("开源许可") {
            var show by remember { mutableStateOf(false) }
            val notices = remember(show) {
                if (show) runCatching {
                    context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                }.getOrNull() else null
            }
            Text("基于上游开源项目涛声 TaoSheng 开发；包含 sherpa-onnx、ONNX Runtime、Silero VAD 等第三方组件。",
                style = RsType.body, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Pill(if (show) "收起声明" else "查看完整声明", filled = false) { show = !show }
            if (notices != null) {
                Spacer(Modifier.height(10.dp))
                Text(notices, style = RsType.caption, color = c.textTertiary)
            }
        }

        AboutCard("问题反馈") {
            Text("遇到闪退或功能不对，在“高级与诊断”导出问题记录发给开发。",
                style = RsType.body, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Pill("去高级与诊断", filled = false) { app.resound.ui.ShellNav.openSettings(SettingsPage.Advanced) }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text(title, style = RsType.section, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

private const val DEV_UNLOCKED = "devOptionsUnlocked"

/** “0.2.12-preview.1” 显示成“0.2.12 预览版 1”；正式版原样 */
fun displayVersion(version: String): String =
    Regex("""^(.+)-preview\.(\d+)$""").matchEntire(version)?.let { "${it.groupValues[1]} 预览版 ${it.groupValues[2]}" } ?: version

/** 开发者选项：接收预览版 */
@Composable
private fun PreviewCard() {
    val c = Rs.colors
    val updater = AppGraph.updater
    val on by updater.previewChannel.collectAsStateWithLifecycle()
    AboutCard("开发者选项") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("接收预览版", style = RsType.body, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text("给开发测试用的新版本，可能不稳定。关掉后等下一个正式版自动换回。",
                    style = RsType.caption, color = c.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            app.resound.ui.skills.Toggle(on) { updater.setPreviewChannel(it) }
        }
        if (updater.isPreviewBuild) {
            Spacer(Modifier.height(8.dp))
            Text("当前装的是预览版", style = RsType.caption, color = c.accent)
        }
    }
}

/** 导出问题记录并交给系统分享；底层没装配时不显示 */
@Composable
internal fun ExportProblemPill(label: String = "导出问题记录", onDone: () -> Unit = {}) {
    if (!AppGraph.isProblemReportsReady) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Pill(if (busy) "正在导出…" else label, filled = false) {
        if (busy) return@Pill
        busy = true
        scope.launch {
            runCatching {
                val file = AppGraph.problemReports.export()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "发送问题记录"))
                AppGraph.problemReports.dismissCrashNotice()
                onDone()
            }.onFailure { Toast.makeText(context, "没能导出，请稍后再试", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }
}

/** 今天页顶部：上次闪退了，提示一次导出问题记录 */
@Composable
fun CrashNotice(topGap: Dp = 0.dp) {
    if (!AppGraph.isProblemReportsReady) return
    val c = Rs.colors
    val at by AppGraph.problemReports.lastCrashAt.collectAsStateWithLifecycle()
    if (at == null) return
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text("声声不息上次意外退出了", style = RsType.body, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text("导出问题记录发给开发，方便查原因。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExportProblemPill()
            Text("知道了", style = RsType.subhead, color = c.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { AppGraph.problemReports.dismissCrashNotice() }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
