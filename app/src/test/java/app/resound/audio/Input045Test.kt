package app.resound.audio

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo as D
import android.media.AudioManager
import app.resound.backup.BackupSettings
import app.resound.capture.SpeechSegmenter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class Input045Test {
    private val usb = InputRoute(InputDevice("Usb:DJI", "DJI", InputKind.Usb, true), 10)
    private val wired = InputRoute(InputDevice("Wired:耳机", "耳机", InputKind.Wired, true), 20)

    @Test fun catalogMergesBuiltinsUsbAndBluetoothAndKeepsKeysAcrossReplug() {
        val catalog = InputCatalog()
        val ports = listOf(InputPort(1,D.TYPE_BUILTIN_MIC,"顶部"), InputPort(2,D.TYPE_BUILTIN_MIC,"底部"),
            InputPort(3,D.TYPE_USB_DEVICE,"DJI","usb-1"), InputPort(4,D.TYPE_USB_HEADSET,"DJI","usb-2"),
            InputPort(5,D.TYPE_BLUETOOTH_SCO,"AirPods","aa:bb"), InputPort(6,D.TYPE_BLE_HEADSET,"AirPods","AA:BB"),
            InputPort(7,D.TYPE_TELEPHONY,"电话"), InputPort(8,D.TYPE_REMOTE_SUBMIX,"系统"),
            InputPort(9,D.TYPE_FM_TUNER,"FM"), InputPort(10,D.TYPE_TV_TUNER,"TV"),
            InputPort(11,InputCatalog.ECHO_REFERENCE,"回声"), InputPort(12,D.TYPE_LINE_ANALOG,"线路"))
        val result = InputCatalog.visible(catalog.update(ports), InputChoice.Phone)
        assertEquals(4, result.size)
        assertEquals(InputDevices.PHONE, result.first())
        assertEquals(InputKind.BluetoothLe, result.single { it.name == "AirPods" }.kind)
        val keys = result.map { it.key }.toSet()
        catalog.update(emptyList())
        assertEquals(keys, InputCatalog.visible(catalog.update(ports.map { it.copy(id=it.id+100) }),InputChoice.Phone).map { it.key }.toSet())
    }

    @Test fun recentConnectionWinsAndDisconnectedSelectionRetainsName() {
        val catalog = InputCatalog()
        val a = InputPort(1,D.TYPE_USB_DEVICE,"DJI")
        val b = InputPort(2,D.TYPE_WIRED_HEADSET,"耳机")
        catalog.update(listOf(a))
        assertEquals("耳机",catalog.update(listOf(b,a)).first().device.name)
        catalog.update(listOf(b))
        assertEquals("DJI",catalog.update(listOf(b,a)).first().device.name)
        val list = InputCatalog.visible(emptyList(),InputChoice.Device(usb.device.key,"保存的 DJI 名字"))
        assertEquals(InputDevices.PHONE,list.first())
        assertFalse(list.last().connected)
        assertEquals("保存的 DJI 名字",list.last().name)
    }

    @Test fun choiceDefaultsPersistsAndParticipatesInSettingsBackup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("inputs",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(InputChoice.Phone,InputPreferences(prefs).read())
        val chosen = InputChoice.Device(usb.device.key,usb.device.name)
        InputPreferences(prefs).write(chosen)
        assertEquals(chosen,InputPreferences(prefs).read())
        val dir = File(context.cacheDir,"input-settings").apply { mkdirs() }
        BackupSettings.capture(context,dir) { _,_ -> "" }
        InputPreferences(prefs).write(InputChoice.AutoExternal)
        assertEquals(InputChoice.AutoExternal,InputPreferences(prefs).read())
        BackupSettings.apply(context,dir) { _,_,_ -> }
        assertEquals(chosen,InputPreferences(prefs).read())
    }

    @Test fun selectionHonorsPhoneAutomaticAndMissingDevice() {
        val routes = listOf(wired,usb)
        assertEquals("mic",InputSnapshot(InputChoice.Phone,routes).selected().device.key)
        assertEquals(wired,InputSnapshot(InputChoice.AutoExternal,routes).selected())
        assertEquals(usb,InputSnapshot(InputChoice.Device(usb.device.key,"DJI"),routes).selected())
        assertEquals("mic",InputSnapshot(InputChoice.Device("missing","未连接"),routes).selected().device.key)
    }

    @Test fun disconnectFinishesSpeechImmediatelyAndReconnectIsDebounced() = runBlocking {
        var now = 0L
        val snapshots = MutableStateFlow(InputSnapshot(InputChoice.AutoExternal,listOf(usb)))
        val started = Channel<String>(Channel.UNLIMITED)
        val ended = Channel<Pair<String,Boolean>>(Channel.UNLIMITED)
        val task = launch {
            InputSwitchLoop(snapshots,{now}).run { route,_ ->
                val cutter = SpeechSegmenter(object : SpeechSegmenter.Sink {
                    override fun begin(startSample: Long) {}
                    override fun write(samples: ShortArray) {}
                    override fun end(endSample: Long, keep: Boolean) { ended.trySend(route.device.key to keep) }
                })
                try {
                    repeat(40) { cutter.accept(ShortArray(480) { 1000 },true) }
                    started.send(route.device.key)
                    awaitCancellation()
                } finally { cutter.finish() }
            }
        }
        try {
            assertEquals(usb.device.key,withTimeout(2000) { started.receive() })
            snapshots.value = InputSnapshot(InputChoice.AutoExternal,emptyList())
            assertEquals(usb.device.key to true,withTimeout(1000) { ended.receive() })
            assertEquals("mic",withTimeout(1000) { started.receive() })
            snapshots.value = InputSnapshot(InputChoice.AutoExternal,listOf(usb.copy(portId=11)))
            assertNull(withTimeoutOrNull(100) { started.receive() })
            snapshots.value = InputSnapshot(InputChoice.AutoExternal,emptyList())
            yield()
            now = 3001
            snapshots.value = InputSnapshot(InputChoice.AutoExternal,listOf(usb.copy(portId=12)))
            assertEquals("mic" to true,withTimeout(1000) { ended.receive() })
            assertEquals(usb.device.key,withTimeout(1000) { started.receive() })
        } finally { task.cancelAndJoin() }
    }

    @Test fun sourceFailureFallsBackWithoutMicRetryOrReopeningFailedDevice() = runBlocking {
        val snapshots = MutableStateFlow(InputSnapshot(InputChoice.AutoExternal,listOf(usb)))
        val started = Channel<Pair<String,String?>>(Channel.UNLIMITED)
        var finished = false
        val task = launch {
            InputSwitchLoop(snapshots,{0}).run { route,note ->
                if (route.device.kind != InputKind.Phone) {
                    try { throw AudioSourceException(AudioSourceException.Reason.Disconnected) }
                    finally { finished = true }
                }
                assertTrue(finished)
                started.send(route.device.key to note)
                awaitCancellation()
            }
        }
        try {
            val result = withTimeout(1000) { started.receive() }
            assertEquals("mic",result.first)
            assertTrue(result.second!!.contains("DJI"))
            snapshots.value = InputSnapshot(InputChoice.AutoExternal,listOf(usb,wired))
            assertNull(withTimeoutOrNull(100) { started.receive() })
        } finally { task.cancelAndJoin() }
    }

    @Test fun ownBluetoothModeIsNotCountedAsCallButRingingAndCallsAre() {
        assertFalse(MicYield.inCall(AudioManager.MODE_IN_COMMUNICATION,true))
        assertTrue(MicYield.inCall(AudioManager.MODE_IN_COMMUNICATION,false))
        assertTrue(MicYield.inCall(AudioManager.MODE_RINGTONE,true))
        assertTrue(MicYield.inCall(AudioManager.MODE_IN_CALL,true))
        assertEquals(MicYield.IN_CALL,MicYield.whileOpen(true) { MicYield.inCall(AudioManager.MODE_IN_CALL,true) })
        assertEquals(MicYield.OTHER_APP,MicYield.whileOpen(true) { MicYield.inCall(AudioManager.MODE_IN_COMMUNICATION,true) })
    }

    @Test fun conversionKeeps480SampleMonoFramesAcrossOddStereoReadsAndNonIntegerRates() {
        for (rate in listOf(48_000,44_100,8_000,16_000)) {
            val converter = PcmInputConverter(rate,2)
            val input = ShortArray(rate*2) { if (it%2==0) 3000 else 1000 }
            val frames = input.toList().chunked(997).flatMap { converter.accept(it.toShortArray(),it.size) }
            assertEquals(33,frames.size)
            assertTrue(frames.all { it.size==480 && it.all { v -> v==2000.toShort() } })
            converter.reset()
            val silence = converter.accept(ShortArray(rate*2*3/100),rate*2*3/100)
            assertEquals(1,silence.size)
            assertTrue(silence.single().all { it==0.toShort() })
        }
    }
}
