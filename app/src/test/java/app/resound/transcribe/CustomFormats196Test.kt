package app.resound.transcribe

import android.content.Context
import app.resound.backup.PreferenceBackupPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class CustomFormats196Test {
    @Test fun cachePersistsButConfigurationChangesClearItAndOldRequestsCannotRewrite() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("transcribe", 0).edit().clear().commit()
        context.getSharedPreferences("custom-asr-formats", 0).edit().clear().commit()
        val settings = AndroidTranscribeSettings(context)
        val endpoint = "https://example.invalid/asr"
        settings.saveCustom(endpoint, "first", CustomEndpointMode.Full)
        val old = settings.customFormats(endpoint, "first", CustomEndpointMode.Full)
        old.remember(endpoint)
        assertEquals("json", AndroidTranscribeSettings(context).customFormats(endpoint, "first", CustomEndpointMode.Full).format(endpoint))
        settings.saveCustom(endpoint, "first", CustomEndpointMode.Full)
        assertEquals("json", old.format(endpoint))
        settings.saveCustom(endpoint, "second", CustomEndpointMode.Full)
        old.remember(endpoint)
        assertEquals("verbose_json", old.format(endpoint))
        settings.customFormats(endpoint, "second", CustomEndpointMode.Full).remember(endpoint)
        settings.saveCustom("https://other.invalid/asr", "second", CustomEndpointMode.Full)
        settings.saveCustom(endpoint, "second", CustomEndpointMode.Full)
        assertEquals("verbose_json", old.format(endpoint))
    }
    @Test fun cacheStoresOnlyAddressFingerprintAndTimestampAndIsExcludedFromBackup() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = AndroidTranscribeSettings(context)
        val endpoint = "https://example.invalid/private-path"
        settings.saveCustom(endpoint, "model", CustomEndpointMode.Full)
        settings.customFormats(endpoint, "model", CustomEndpointMode.Full).remember(endpoint)
        val all = context.getSharedPreferences("custom-asr-formats", 0).all
        assertEquals(setOf(keyFingerprint(endpoint)), all.keys)
        assertTrue(all.values.all { it is Long })
        assertTrue("custom-asr-formats" in PreferenceBackupPolicy.excluded)
        assertFalse("custom-asr-formats" in PreferenceBackupPolicy.backedUp)
    }
}
