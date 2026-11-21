package app.resound.transcribe.local

import org.junit.Assert.*
import org.junit.Test

class LocaleLanguageTest {
    @Test fun localeMapsToSupportedLanguages() {
        assertEquals(LocalAsrLanguage.Chinese, LocalAsrLanguage.fromLocaleTag("zh-CN"))
        assertEquals(LocalAsrLanguage.Chinese, LocalAsrLanguage.fromLocaleTag("zh-Hans-CN"))
        assertEquals(LocalAsrLanguage.Chinese, LocalAsrLanguage.fromLocaleTag("zh-TW"))
        assertEquals(LocalAsrLanguage.Cantonese, LocalAsrLanguage.fromLocaleTag("yue-HK"))
        assertEquals(LocalAsrLanguage.Cantonese, LocalAsrLanguage.fromLocaleTag("zh-HK"))
        assertEquals(LocalAsrLanguage.Cantonese, LocalAsrLanguage.fromLocaleTag("zh-Hant-MO"))
        assertEquals(LocalAsrLanguage.Japanese, LocalAsrLanguage.fromLocaleTag("ja-JP"))
        assertEquals(LocalAsrLanguage.Korean, LocalAsrLanguage.fromLocaleTag("ko-KR"))
        assertEquals(LocalAsrLanguage.English, LocalAsrLanguage.fromLocaleTag("en-US"))
    }

    @Test fun unmappedLocaleFallsBackToModelAuto() {
        assertNull(LocalAsrLanguage.fromLocaleTag("fr-FR"))
        assertNull(LocalAsrLanguage.fromLocaleTag("de-DE"))
        assertNull(LocalAsrLanguage.fromLocaleTag(""))
    }
}
