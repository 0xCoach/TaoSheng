package app.resound.audio

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 卡 112：全零判故障、自动不选蓝牙 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class CaptureHealth112Test {
    private val zeros = ShortArray(960)
    private val quiet = ShortArray(960).also { it[500] = 1 }  // 安静房间的底噪：哪怕只有一个采样不是零

    @Test fun continuousZeroTripsOnlyAfterLimitAndAnySampleResets() {
        val watch = CaptureHealth.ZeroWatch(limitMs = 20_000)
        assertFalse(watch.accept(zeros, zeros.size, 0))
        assertFalse(watch.accept(zeros, zeros.size, 19_999))
        assertFalse("有一点底噪就不算全零", watch.accept(quiet, quiet.size, 20_500))
        assertFalse(watch.accept(zeros, zeros.size, 21_000))
        assertTrue(watch.accept(zeros, zeros.size, 41_000))
        assertEquals(20_500, CaptureHealth.lastSoundAt)
    }

    @Test fun emptyReadsAndResetDoNotCountAsZeroAudio() {
        val watch = CaptureHealth.ZeroWatch(limitMs = 1_000)
        assertFalse(watch.accept(zeros, 0, 0))
        assertFalse(watch.accept(zeros, zeros.size, 100))
        watch.reset()  // 让麦期间会 reset，不计时
        assertFalse(watch.accept(zeros, zeros.size, 1_000))
        assertTrue(watch.accept(zeros, zeros.size, 2_100))
    }

    @Test fun automaticSkipsBluetoothButKeepsUsbAndWired() {
        val le = InputRoute(InputDevice("Bluetooth:耳机", "耳机", InputKind.BluetoothLe, true), 30)
        val sco = InputRoute(InputDevice("Bluetooth:旧耳机", "旧耳机", InputKind.BluetoothCall, true), 31)
        val usb = InputRoute(InputDevice("Usb:DJI", "DJI", InputKind.Usb, true), 10)
        assertEquals("mic", InputSnapshot(InputChoice.AutoExternal, listOf(le, sco)).selected().device.key)
        assertEquals(usb, InputSnapshot(InputChoice.AutoExternal, listOf(le, usb)).selected())
        assertEquals(le, InputSnapshot(InputChoice.Device(le.device.key, "耳机"), listOf(le, usb)).selected())
    }
}
