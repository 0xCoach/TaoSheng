package app.resound.topic

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.resound.ui.topic.*
import app.resound.ui.theme.*
import app.resound.skills.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class TopicDesignShotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val at = LocalDate.now().atTime(14,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun topic(id: Long, title: String, status: TopicStatus, start: Long = at) = Topic(id, TopicKind.Meeting, title, status, start,
        null, null, emptySet(), listOf(TopicSpan(id, start, start + 92*60_000)), emptyList(), null, null,
        if (status == TopicStatus.Failed) "模型回答格式不符合要求" else null, 0, false)
    @Test fun layoutsAndRetryEntry() {
        val target = System.getenv("TOPIC_SHOTS") ?: File(compose.activity.cacheDir, "topic-design").path
        val dir = File(target).apply { mkdirs() }
        val rows = listOf(topic(1,"产品讨论会",TopicStatus.Done),topic(2,"项目周会",TopicStatus.Failed,at-4*3600_000),topic(3,"团队培训笔记",TopicStatus.Done,at-86400_000).copy(kind = TopicKind.Training))
        val partial = TopicProgress("整理纪要",12,18,mapOf("one" to "本周优先完成登录流程与问题反馈入口。发布前需要核对真实使用路径。"),"第 13 段回答格式不符合要求，已完成内容保留。")
        val card = SkillCard(1,"import_meeting","会议纪要","meeting",kotlinx.datetime.LocalDate(2026,11,12),at,at+92*60_000,"产品讨论会",
            "明确本周交付范围，并确认长会议的整理与恢复流程。",
            listOf(SkillField("points","讨论要点",FieldType.Bullets,""),SkillField("conclusion","会议结论",FieldType.Paragraph,""),SkillField("actions","后续行动",FieldType.Checklist,"")),
            mapOf("points" to FieldValue(items = listOf("专题入口沿用首页现有按钮，记录按日期排列。","长会议分段整理，遇到中断保留已完成的内容。")),
                "conclusion" to FieldValue(text = "先完成专题体验与长会议可靠性改造，再进行真机验证。"),
                "actions" to FieldValue(items = listOf("核对一场两小时会议的原话与纪要","验证退出应用后可以继续整理"))), emptyList(),at,true)
        var page by mutableIntStateOf(0); var theme by mutableStateOf(ThemeMode.Light); var clicked = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(theme) {
                Column(Modifier.fillMaxSize().background(Rs.colors.elevated).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text(when(page) { 2 -> "项目周会"; 3 -> "产品讨论会"; else -> "专题" },style=RsType.title,color=Rs.colors.text)
                    if(page < 2) TopicTabs(page == 1,1,false) { page = if(it) 1 else 0 }
                    when(page) {
                        0 -> TopicStartPanel(onStarted = {})
                        1 -> TopicHistoryContent(rows,"",{},false,{},30,{}, { if(it.id==2L) partial else TopicProgress() },emptySet(),{}, { clicked = true })
                        2 -> {
                            Text("会议 · 今天 10:00–11:32",style=RsType.subhead,color=Rs.colors.textSecondary)
                            TopicProgressContent(partial,partial.error,false) { clicked = true }
                        }
                        else -> {
                            Text("会议 · 今天 14:00–15:32",style=RsType.subhead,color=Rs.colors.textSecondary)
                            TopicCompletedContent(card)
                        }
                    }
                }
            }
        }
        listOf(ThemeMode.Light,ThemeMode.Dark).forEach { mode -> theme = mode
            (0..3).forEach { index -> page = index
                repeat(4) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
                val bitmap = compose.runOnUiThread {
                    val view = compose.activity.window.decorView
                    Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
                }
                File(dir,"${mode.name}-${listOf("开始专题","专题记录","部分完成","会议纪要")[index]}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        page = 2
        repeat(4) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        compose.onNodeWithText("继续整理").performClick()
        Assert.assertTrue(clicked)
    }
}
