package app.resound.audio

import android.app.Application
import app.resound.backup.PreferenceBackupPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class BluetoothPlayback193Test {
    private val bt = InputRoute(InputDevice("Bluetooth:mic", "蓝牙麦", InputKind.BluetoothCall, true), 10)
    private fun snapshot() = InputSnapshot(InputChoice.Device(bt.device.key, bt.device.name), listOf(bt))

    @Test fun defaultsPersistAcrossInputChoiceAndAreBackedUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("inputs", 0)
        prefs.edit().clear().commit()
        val settings = BluetoothYieldSettings(context)
        assertEquals(BluetoothYieldSettings.Options(), settings.read())
        settings.setEnabled(false); settings.setContinueOnPhone(false)
        InputPreferences(prefs).write(InputChoice.Phone)
        assertEquals(BluetoothYieldSettings.Options(false, false), settings.read())
        assertTrue(PreferenceBackupPolicy.setting("inputs", "bluetoothPlaybackYield"))
        assertTrue(PreferenceBackupPolicy.setting("inputs", "bluetoothPlaybackContinueOnPhone"))
    }

    @Test fun switchingReleasesOldRoundBeforePhoneAndPauseAndReturn() = runBlocking {
        val snapshots = MutableStateFlow(snapshot())
        val events = Channel<String>(Channel.UNLIMITED)
        val job = launch {
            InputSwitchLoop(snapshots, { 0L }, cooldownMillis = 0).run { route, note ->
                val label = if (route.pausedForPlayback) "pause" else route.device.key
                if (route.device.kind == InputKind.Phone) assertTrue(note!!.startsWith(MicYield.PLAYBACK))
                events.send("open:$label")
                try { awaitCancellation() } finally { events.trySend("release:$label") }
            }
        }
        try { withTimeout(3000) {
            assertEquals("open:${bt.device.key}", events.receive())
            snapshots.value = snapshot().copy(playbackYield = true)
            assertEquals("release:${bt.device.key}", events.receive())
            assertEquals("open:mic", events.receive())
            snapshots.value = snapshot().copy(playbackYield = true, pauseForPlayback = true)
            assertEquals("release:mic", events.receive()); assertEquals("open:pause", events.receive())
            snapshots.value = snapshot()
            assertEquals("release:pause", events.receive()); assertEquals("open:${bt.device.key}", events.receive())
        } } finally { job.cancelAndJoin() }
    }

    @Test fun subscriptionOnlyForEnabledBluetoothAndCancelledWithSession() = runBlocking {
        val snapshots = MutableStateFlow(InputSnapshot(InputChoice.Phone, listOf(bt)))
        val options = MutableStateFlow(BluetoothYieldSettings.Options())
        val playing = MutableStateFlow(true)
        val seen = Channel<InputSnapshot>(Channel.UNLIMITED)
        val job = launch { playbackYieldSnapshots(snapshots, options, playing).collect { seen.send(it) } }
        try { withTimeout(3000) {
            assertFalse(seen.receive().playbackYield); assertEquals(0, playing.subscriptionCount.value)
            snapshots.value = snapshot()
            while (!seen.receive().playbackYield) { }
            assertEquals(1, playing.subscriptionCount.value)
            options.value = options.value.copy(enabled = false)
            while (seen.receive().playbackYield) { }
            yield(); assertEquals(0, playing.subscriptionCount.value)
        } } finally { job.cancelAndJoin() }
        assertEquals(0, playing.subscriptionCount.value)
    }

    @Test fun playbackCanCancelRoundWaitingForOtherRecorderWithoutOpeningBluetoothAgain() = runBlocking {
        val snapshots = MutableStateFlow(snapshot())
        val events = Channel<String>(Channel.UNLIMITED)
        val job = launch {
            InputSwitchLoop(snapshots, { 0L }, cooldownMillis = 0).run { route, _ ->
                // 模拟 RoutedRecording 正在等待其他应用放麦；取消必须结束这一轮等待。
                events.send(if (route == bt) MicYield.OTHER_APP else "phone")
                try { awaitCancellation() } finally { events.trySend("closed") }
            }
        }
        try { withTimeout(3000) {
            assertEquals(MicYield.OTHER_APP, events.receive())
            snapshots.value = snapshot().copy(playbackYield = true)
            assertEquals("closed", events.receive()); assertEquals("phone", events.receive())
        } } finally { job.cancelAndJoin() }
    }
}
