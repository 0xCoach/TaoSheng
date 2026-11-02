package app.resound.audio

import android.Manifest
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class BluetoothRelease091Test {
    @Test fun failedBluetoothOpenReleasesOwnRequestEvenWhenPreviousGlobalModeWasCommunication() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        val manager = context.getSystemService(AudioManager::class.java)
        shadowOf(manager).setAvailableCommunicationDevices(emptyList())
        val port = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        for (previous in listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_IN_COMMUNICATION)) {
            manager.mode = previous
            val error = runCatching { RoutedRecording(context, { port }, InputKind.BluetoothCall) {}.events().collect() }.exceptionOrNull()
            assertTrue(error is AudioSourceException)
            assertEquals(AudioSourceException.Reason.Disconnected, (error as AudioSourceException).reason)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        }
    }

    @Test fun missingPermissionDoesNotChangeAnExistingAudioMode() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val manager = context.getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_CALL
        val error = runCatching { RoutedRecording(context, { null }, InputKind.BluetoothCall) {}.events().collect() }.exceptionOrNull()
        assertEquals(AudioSourceException.Reason.NoPermission, (error as AudioSourceException).reason)
        assertEquals(AudioManager.MODE_IN_CALL, manager.mode)
    }
}
