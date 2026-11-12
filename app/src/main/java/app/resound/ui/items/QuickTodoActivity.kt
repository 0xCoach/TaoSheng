package app.resound.ui.items

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.ItemOrigin
import app.resound.digest.TodoDrafts
import app.resound.digest.VoiceNoteResult
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.BreathingWave
import app.resound.ui.verify.RsSheet
import kotlinx.coroutines.launch

/**
 * 快速加待办：从通知栏或桌面长按菜单打开，浮在当前界面上，不进入涛声主界面。
 * 一打开就开始听；点“说完了”识别并生成待办卡，确认后自动关闭，回到原来的界面。也可改为打字。
 */
class QuickTodoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = runCatching { ThemeMode.valueOf(getSharedPreferences("ui", MODE_PRIVATE).getString("theme", "System")!!) }
            .getOrDefault(ThemeMode.System)
        setContent { ResoundTheme(mode) { QuickTodoFlow(onClose = ::finish) } }
    }
}

private enum class QuickPhase { Starting, Listening, Recognizing, Drafting, Typing }

@Composable
private fun QuickTodoFlow(onClose: () -> Unit) {
    val context = LocalContext.current
    if (!AppGraph.isManualTodosReady || !AppGraph.isTodoVoiceReady) {
        LaunchedEffect(Unit) { Toast.makeText(context, "涛声还在启动，请稍后再试", Toast.LENGTH_SHORT).show(); onClose() }
        return
    }
    val voice = AppGraph.todoVoice
    val todos = AppGraph.manualTodos
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(QuickPhase.Starting) }
    var result by remember { mutableStateOf<Triple<TodoDrafts, ItemOrigin, String>?>(null) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun draft(text: String, spoken: Boolean) {
        phase = QuickPhase.Drafting
        scope.launch {
            val drafts = runCatching { todos.draft(text) }.getOrNull()
            if (drafts == null || drafts.items.isEmpty()) { toast("没能生成待办，请再试一次"); onClose() }
            else result = Triple(drafts, if (spoken) ItemOrigin.Voice else ItemOrigin.Typed, text)
        }
    }

    // 一打开就开始听；麦克风用不了时直接改为打字
    LaunchedEffect(Unit) {
        if (voice.start()) phase = QuickPhase.Listening
        else { toast("麦克风暂时用不了，改为打字"); phase = QuickPhase.Typing }
    }
    // 窗口被关掉时（返回键、切走）丢掉没说完的录音，恢复日常录音
    DisposableEffect(Unit) { onDispose { if (phase == QuickPhase.Listening || phase == QuickPhase.Starting) voice.cancel() } }

    result?.let { (drafts, origin, source) ->
        DraftSheet(drafts, source, onDismiss = onClose) { confirmed ->
            todos.add(confirmed, origin, source)
            toast("已添加 ${confirmed.size} 条待办")
            onClose()
        }
        return
    }
    if (phase == QuickPhase.Typing) {
        TypeSheet(AddWords("待办", "", "添加待办", "像平时说话一样写，几件事写在一起也行。",
            "例如：明天下午三点给张总回电话，周五前把报销单交了", "生成待办卡"), onDismiss = onClose) { text -> draft(text, false) }
        return
    }
    val c = Rs.colors
    RsSheet("说一下要做的事", "几件事一起说也行，说完点“说完了”", onDismiss = { voice.cancel(); onClose() }) {
        val level by voice.level.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                QuickPhase.Listening -> BreathingWave(level, active = true, recording = true, modifier = Modifier.fillMaxWidth().height(40.dp), tint = c.accent)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(when (phase) {
                        QuickPhase.Starting -> "正在打开麦克风…"
                        QuickPhase.Recognizing -> "正在识别…"
                        else -> "正在生成待办…"
                    }, style = RsType.subhead, color = c.textSecondary)
                }
            }
        }
        ActionButton("说完了", ActionKind.Primary, enabled = phase == QuickPhase.Listening) {
            phase = QuickPhase.Recognizing
            scope.launch {
                when (val r = voice.finish()) {
                    is VoiceNoteResult.Text -> draft(r.text, true)
                    VoiceNoteResult.Empty -> { toast("没听清，改为打字"); phase = QuickPhase.Typing }
                    is VoiceNoteResult.Failed -> { toast(r.message); phase = QuickPhase.Typing }
                }
            }
        }
        ActionButton("改为打字", ActionKind.Secondary, enabled = phase == QuickPhase.Listening || phase == QuickPhase.Starting) {
            voice.cancel(); phase = QuickPhase.Typing
        }
    }
}
