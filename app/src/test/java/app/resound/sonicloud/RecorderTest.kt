/*
 * [INPUT]: 录音卡协议适配、输入路由与持久化导入边界。
 * [OUTPUT]: 关键路径回归：不回退手机、分包、暂停恢复、续传和幂等交接。
 * [POS]: 普通款 A 的离线测试；真实 Opus 音质与 BLE 必须真机验证。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.Application
import android.content.Context
import app.resound.audio.*
import app.resound.importer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class RecorderTest {
    @Test fun fragmentedBluetoothCallbacksPreserveEveryOpusByte() {
        val input = ByteArray(400) { it.toByte() }
        val frames = mutableListOf<ByteArray>()
        val assembler = RecorderFrames()
        for (chunk in input.toList().chunked(37)) assembler.accept(chunk.toByteArray(), frames::add)
        assembler.finish()
        assertEquals(10, frames.size)
        assertArrayEquals(input, frames.flatMap { it.asIterable() }.toByteArray())
    }

    @Test fun incompleteLastFrameIsRejected() {
        val assembler = RecorderFrames()
        assembler.accept(ByteArray(39)) { fail("不能输出半帧") }
        assertThrows(IllegalArgumentException::class.java) { assembler.finish() }
    }

    @Test fun selectedRecorderNeverBecomesPhoneEvenOnFailure() = runBlocking {
        val choice = InputChoice.Recorder("Sonicloud:AA:BB:CC:DD:EE:FF", "录音卡")
        val snapshots = MutableStateFlow(InputSnapshot(choice, emptyList()))
        val started = Channel<String>(Channel.UNLIMITED)
        val failure = CompletableDeferred<Unit>()
        val job = launch {
            InputSwitchLoop(snapshots, { 0 }).run(onUnchanged = { _, _ -> failure.complete(Unit) }) { route, _ ->
                started.send(route.device.key)
                if (route.device.kind == InputKind.Recorder) throw AudioSourceException(AudioSourceException.Reason.Disconnected)
                awaitCancellation()
            }
        }
        try {
            assertEquals(choice.key, withTimeout(2000) { started.receive() })
            withTimeout(2000) { failure.await() }
            assertTrue(started.tryReceive().isFailure)
            snapshots.value = InputSnapshot(InputChoice.Phone, emptyList())
            assertEquals("mic", withTimeout(2000) { started.receive() })
        } finally { job.cancelAndJoin() }
    }

    @Test fun recorderChoicePersistsWithoutChangingLegacyDeviceFallback() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val choice = InputChoice.Recorder("Sonicloud:AA:BB:CC:DD:EE:FF", "卡")
        InputPreferences(prefs).write(choice)
        assertEquals(choice, InputPreferences(prefs).read())
        assertEquals(InputKind.Recorder, InputSnapshot(choice, emptyList()).selected().device.kind)
        assertEquals("mic", InputSnapshot(InputChoice.Device("missing", "USB"), emptyList()).selected().device.key)
        assertEquals("mic", InputSnapshot(InputChoice.AutoExternal, emptyList()).selected().device.key)
    }

    @Test fun twoStateQueryMustNotTurnPauseIntoNewRecording() {
        val state = recorderQueryState(2, RecorderRecording.Paused, false)
        assertEquals(RecorderRecording.Paused, state)
        assertEquals(RecorderCommand.Continue, recorderResumeCommand(state))
        assertEquals(RecorderCommand.Start, recorderResumeCommand(recorderQueryState(2, RecorderRecording.Unknown, false)))
        assertNull(recorderResumeCommand(recorderQueryState(1, RecorderRecording.Paused, false)))
    }

    @Test fun androidBooleanConnectionAndDisconnectedSourceIdentityAreRespected() {
        listOf(true, "true", 1, "1").forEach { assertTrue(recorderConnected(it)) }
        listOf(false, "false", 0, "0", null).forEach { assertFalse(recorderConnected(it)) }
        assertFalse(recorderMayConnect("card-a", "card-b", true))
        assertTrue(recorderMayConnect("card-a", "card-a", true))
        assertTrue(recorderMayConnect("card-a", "card-b", false))
    }

    @Test fun pcmFromDecoderIsReframedWithoutDroppingSamplesBetweenCallbacks() {
        val converter = PcmInputConverter(48000, 1)
        val frames = mutableListOf<ShortArray>()
        repeat(3) { frames += converter.accept(ShortArray(960) { 1200 }, 960) }
        assertEquals(2, frames.size)
        assertTrue(frames.all { it.size == 480 && it.all { sample -> sample == 1200.toShort() } })
    }

    @Test fun fileIdentityIncludesDeviceAndSizeAndNeverUsesRemotePath() {
        val file = RecorderFile("../../note20260109-090905.opus", 80, 1)
        val key = recorderFileKey("card-a", file)
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(key, recorderFileKey("card-b", file))
        assertNotEquals(key, recorderFileKey("card-a", file.copy(size = 120)))
        assertNotNull(recorderStart(file.name))
        assertNull(recorderStart("unknown"))
    }

    private fun importer(scope: CoroutineScope): AndroidAudioImporter {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("imports", Context.MODE_PRIVATE).edit().clear().commit()
        return AndroidAudioImporter(context, scope, inspect = { AudioMetadata(1000, null, 1) }, enqueue = {})
    }

    @Test fun completedFileHandoffIsIdempotentAcrossImporterRecreation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val importer = importer(scope)
            val context = RuntimeEnvironment.getApplication()
            val source = File(context.cacheDir, "source").apply { writeBytes(ByteArray(80)) }
            val first = importer.receiveRecorder(source, "device-file", "note.opus", 123L)
            val reopened = AndroidAudioImporter(context, scope, inspect = { AudioMetadata(1000, null, 1) }, enqueue = {})
            assertEquals(first, reopened.receiveRecorder(source, "device-file", "note.opus", 123L))
            assertEquals(1, reopened.pending.value.size)
            assertEquals(123L, reopened.pending.value.single().info.suggestedStartAt)
        } finally { scope.cancel() }
    }

    @Test fun failedImportCanRetryWithFreshAttemptId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val importer = importer(scope)
            val source = File(RuntimeEnvironment.getApplication().cacheDir, "source").apply { writeBytes(ByteArray(80)) }
            val old = importer.receiveRecorder(source, "retry-file", "note.opus", null)
            importer.finish(old, "转写失败")
            assertFalse(importer.receivedRecorder("retry-file"))
            val next = importer.receiveRecorder(source, "retry-file", "note.opus", null)
            assertNotEquals(old, next)
        } finally { scope.cancel() }
    }

    @Test fun downloadResumesOnlyWrittenPrefixAndRejectsPrematureCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val importer = importer(scope)
            val context = RuntimeEnvironment.getApplication()
            val file = RecorderFile(UUID.randomUUID().toString() + ".opus", 80, 1)
            val first = RecorderTransfer(context, importer)
            assertEquals(0, first.begin("card", file))
            first.append(ByteArray(40)); first.close()
            val second = RecorderTransfer(context, importer)
            assertEquals(40, second.begin("card", file))
            var rejected = false
            try { second.complete() } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertFalse(importer.receivedRecorder(recorderFileKey("card", file)))
            second.close()
        } finally { scope.cancel() }
    }
}
