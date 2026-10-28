package app.resound.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.R
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard

const val HOMEPAGE_URL = "https://github.com/0xCoach/TaoSheng"

/** README 的摘要；内容写在 App 里，没网也能看 */
private val HIGHLIGHTS = listOf(
    "🎙" to "全天在听，只录人声，安静时不录",
    "📴" to "可用本地模型离线转写，声音不出手机",
    "🧑‍🤝‍🧑" to "声纹认人，分得清谁在说",
    "✅" to "原话里答应的事自动变成待办",
    "💬" to "打字问一句就能找回，答案附原话出处",
    "⌨️" to "语音输入时连同聊天上下文一起记下",
    "🌙" to "每天、每周自动回顾",
    "☁️" to "每天自动备份到坚果云和本机",
)

/** 设置 → 关于 → 关于涛声 */
@Composable
fun AboutPage(version: String?, bottom: Dp) {
    val c = Rs.colors
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(20.dp))) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text("涛声", style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("替你听，替你记，替你想。", style = RsType.subhead, color = c.textSecondary)
            if (version != null) {
                Spacer(Modifier.height(4.dp))
                Text("版本 $version", style = RsType.caption, color = c.textTertiary)
            }
        }

        AboutCard("涛声是什么") {
            Text("一部安卓手机就够：全天收音 → 自动转写 → 认出谁在说 → AI 整理成待办、约定和记忆。",
                style = RsType.body, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text("不用买硬件，不用注册账号，数据只在你自己手机里。", style = RsType.body, color = c.textSecondary)
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

        AboutCard("开源") {
            Text("涛声以 MIT 许可开源，代码、更新记录和问题反馈都在项目主页。", style = RsType.body, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            HomepageLink()
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

/** 点一下用浏览器打开；长按复制（国内打不开时方便发到电脑上） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomepageLink() {
    val c = Rs.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HOMEPAGE_URL))) } },
            onLongClick = {
                clipboard.setText(AnnotatedString(HOMEPAGE_URL))
                Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
            },
        ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("项目主页", style = RsType.body, color = c.text)
            Text(HOMEPAGE_URL.removePrefix("https://"), style = RsType.subhead, color = c.accent)
        }
        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary, textAlign = TextAlign.End)
    }
    Spacer(Modifier.height(4.dp))
    Text("长按可复制链接", style = RsType.caption, color = c.textTertiary)
}
