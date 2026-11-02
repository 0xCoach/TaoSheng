package app.resound.transcribe

import android.app.Application
import app.resound.backup.BackupSettings
import app.resound.transcribe.local.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class LocalLanguage093Test {
    @Test fun selectionPersistsChangesLocalIdentityAndSurvivesBackup() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AndroidTranscribeSettings(context)
        assertEquals(LocalAsrLanguage.Auto, settings.language.value)
        settings.setSource(TranscribeSource.Local)
        val before = settings.identity()
        var changes = 0; settings.onChanged = { changes++ }
        settings.setLanguage(LocalAsrLanguage.Chinese)
        settings.setLanguage(LocalAsrLanguage.Chinese)
        assertEquals(1, changes); assertNotEquals(before, settings.identity())
        assertEquals(LocalAsrLanguage.Chinese, AndroidTranscribeSettings(context).language.value)
        val backup = File(context.cacheDir, "language-backup").apply { mkdirs() }
        BackupSettings.capture(context, backup) { _, _ -> "" }
        settings.setLanguage(LocalAsrLanguage.Japanese)
        BackupSettings.apply(context, backup) { _, _, _ -> }
        assertEquals(LocalAsrLanguage.Chinese, AndroidTranscribeSettings(context).language.value)
    }

    @Test fun unknownStoredLanguageFallsBackAndDoesNotAffectCloudIdentity() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("transcribe", 0).edit().putString("localLanguage", "unknown").commit()
        val settings = AndroidTranscribeSettings(context)
        assertEquals(LocalAsrLanguage.Auto, settings.language.value)
        val before = settings.identity()
        settings.setLanguage(LocalAsrLanguage.English)
        assertEquals(before, settings.identity())
    }

    @Test fun bothBackendsReceiveSelectedLanguageAndInvalidateModelCacheOnChange() {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        for (backend in LocalBackend.entries) {
            val spec = LocalModels.spec(backend)
            val autoKey = localRecognizerKey(spec, dir, LocalAsrLanguage.Auto)
            for (language in LocalAsrLanguage.entries) {
                val config = localRecognizerConfig(backend, language, spec, dir, "/test/libs")
                assertEquals(language.code, config.modelConfig.senseVoice.language)
                assertEquals(if (backend == LocalBackend.Npu) "qnn" else "cpu", config.modelConfig.provider)
                assertEquals(File(dir, spec.modelName).path, config.modelConfig.senseVoice.model)
                if (language != LocalAsrLanguage.Auto) assertNotEquals(autoKey, localRecognizerKey(spec, dir, language))
            }
        }
    }
}
