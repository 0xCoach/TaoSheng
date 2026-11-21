package app.resound.transcribe

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.AppGraph
import app.resound.ui.preview.FakeTranscriptRepository
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.transcript.SpeakerLines
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34, 35])
class GroupedLineEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editMiddleOfGroup() = verifyEdit(listOf("0", "0", "0"), 1)
    @Test fun editEndOfGroup() = verifyEdit(listOf("0", "0", "0"), 2)
    @Test fun editSingleLineGroup() = verifyEdit(listOf("0", "1", "0"), 1)

    private fun verifyEdit(speakers: List<String>, index: Int) {
        val original = speakers.mapIndexed { i, speaker ->
            TranscriptLine(i + 1L, i * 1000L, (i + 1) * 1000L, "第${i + 1}句", speaker, segmentId = 1)
        }
        val rows = mutableStateOf(original)
        AppGraph.transcripts = object : TranscriptRepository by FakeTranscriptRepository() {
            override suspend fun editLine(lineId: Long, text: String): Boolean {
                rows.value = rows.value.map { if (it.id == lineId) it.copy(text = text, edited = true) else it }
                return true
            }
        }
        compose.setContent { ResoundTheme(ThemeMode.Light) { SpeakerLines(rows.value) } }
        val line = original[index]
        val multiline = line.text + "\n\n补充说明"
        fun openEditor() {
            compose.onNodeWithText(line.text).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.onNodeWithText("修改文字").performClick()
        }
        val editor = compose.onNode(hasSetTextAction())
        openEditor()
        editor.performTextReplacement(multiline)
        editor.assertTextEquals(multiline)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText(line.text).assertExists()
        assertEquals(original, rows.value)

        openEditor()
        editor.performTextReplacement(multiline)
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText(multiline, substring = true).assertExists()
        val expected = original.toMutableList().apply { set(index, line.copy(text = multiline, edited = true)) }
        assertEquals(expected, rows.value)
    }
}
