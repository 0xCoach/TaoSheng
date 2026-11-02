package app.resound.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.ui.transcript.LocalLanguageChoice
import app.resound.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class LocalLanguageChoice093Test {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun allSixLanguagesCanBeSelectedAndDisplayed() {
        var selected by mutableStateOf(LocalAsrLanguage.Auto)
        compose.setContent { ResoundTheme(ThemeMode.Light) { LocalLanguageChoice(selected) { selected = it } } }
        for (language in LocalAsrLanguage.entries) {
            compose.onNodeWithText("主要识别语言").performClick()
            compose.onNodeWithText(language.title).performClick()
            compose.onNodeWithText("${language.title} ▾").assertExists()
            compose.runOnIdle { assertEquals(language, selected) }
        }
    }
}
