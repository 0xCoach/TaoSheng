package app.resound.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.skills.*
import app.resound.ui.review.ReviewRow
import app.resound.ui.skills.*
import app.resound.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=android.app.Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class ReportPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun weeklyDetailsStartCollapsedAndExpandInBothThemes() {
        val date=LocalDate.of(2026,11,1)
        val at=date.atTime(21,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val card=SkillCard(1,"weekly","每周回顾","calendar_week",date,at,null,"本周回顾","完成项目讨论",
            listOf(SkillField("one_line","这周一句话",FieldType.Text,""),SkillField("env_sound_overview","本周环境",FieldType.Bullets,""),SkillField("env_sound_observations","各件事的环境",FieldType.Bullets,"")),
            mapOf("one_line" to FieldValue(text="这一周，逐步完成项目整理。"),"env_sound_overview" to FieldValue(items=listOf("记录到的环境：雨、键盘声")),"env_sound_observations" to FieldValue(items=listOf("讨论方案：环境：雨"))),emptyList(),0,false)
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent { ResoundTheme(mode) {
            Column(Modifier.fillMaxSize().background(Rs.colors.background).padding(16.dp)) {
                ReviewRow(app.resound.review.DailyReview(date,"今天完成了方案整理。",emptyList(),0)) {}
                Spacer(Modifier.height(12.dp));SkillCardRow(card)
                Spacer(Modifier.height(20.dp));SkillCardBody(card)
            }
        } }
        compose.onNodeWithText("讨论方案：环境：雨").assertDoesNotExist()
        for (theme in listOf(ThemeMode.Light,ThemeMode.Dark)) {
            compose.runOnIdle { mode=theme };compose.waitForIdle()
            val bitmap=compose.runOnUiThread { val view=compose.activity.window.decorView
                Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) } }
            val dir=File("build/report-previews").apply { mkdirs() }
            File(dir,"${theme.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNodeWithText("展开逐事件环境明细 · 1 条").performClick()
        compose.onNodeWithText("讨论方案：环境：雨").assertExists()
    }
}
