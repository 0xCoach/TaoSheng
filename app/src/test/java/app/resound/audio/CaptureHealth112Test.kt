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
        // 卡 223：新连上的蓝牙学进列表但默认关，所以默认的自动仍不选蓝牙
        val auto = InputChoice.Auto().learn(listOf(le.device, sco.device))
        assertEquals(listOf(false, false), auto.order.filter { it.kind != InputKind.Usb && it.kind != InputKind.Wired && it.kind != InputKind.Other }.map { it.enabled })
        assertEquals("mic", InputSnapshot(auto, listOf(le, sco)).selected().device.key)
        assertEquals(usb, InputSnapshot(auto, listOf(le, usb)).selected())
        assertEquals(le, InputSnapshot(InputChoice.Device(le.device.key, "耳机"), listOf(le, usb)).selected())
    }

    /** 卡 223：从上往下用第一个开着又接着的；只开 DJI，AirPods 连上也不抢；↑ 能让蓝牙排到 USB 前面；专用录音设备不抢 */
    @Test fun automaticFollowsListOrderAndPerDeviceToggles() {
        val dji = InputRoute(InputDevice("Bluetooth:DJI Mic Mini", "DJI Mic Mini", InputKind.BluetoothLe, true), 30)
        val pods = InputRoute(InputDevice("Bluetooth:AirPods", "AirPods", InputKind.BluetoothCall, true), 31)
        val usb = InputRoute(InputDevice("Usb:DJI", "DJI", InputKind.Usb, true), 10)
        val card = InputRoute(InputDevice("CardDevice:录音卡", "录音卡", InputKind.CardDevice, true))
        val auto = InputChoice.Auto().learn(listOf(dji.device, pods.device)).toggle(dji.device.key, true)
        fun pick(choice: InputChoice, vararg routes: InputRoute) = InputSnapshot(choice, routes.toList()).selected()
        assertEquals(dji, pick(auto, pods, dji))
        assertEquals("mic", pick(auto, pods).device.key)
        assertEquals(usb, pick(auto, dji, usb))
        val djiFirst = auto.moveUp(dji.device.key).moveUp(dji.device.key).moveUp(dji.device.key)
        assertEquals(dji.device.key, djiFirst.order.first().key)
        assertEquals(dji, pick(djiFirst, usb, dji))
        assertEquals("mic", pick(auto, card).device.key)
        // 关掉 USB 那一类，插着也不用
        assertEquals("mic", pick(InputChoice.Auto().toggle("type:Usb", false), usb).device.key)
        // 从旧“自动（含蓝牙）”迁移来的：新连上的蓝牙默认开
        assertEquals(dji, pick(InputChoice.Auto(newBluetoothOn = true).learn(listOf(dji.device)), dji))
    }

    /** 卡 223：旧选法读出来换成等价的新选法；没选过的新装默认自动、老用户保持手机麦克风 */
    @Test fun legacyChoicesMigrate() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("inputs223", 0)
        fun read(choice: String?, key: String? = null, newInstall: Boolean = false): InputChoice {
            prefs.edit().clear().apply { choice?.let { putString("choice", it) }; key?.let { putString("key", it).putString("name", "名字") } }.commit()
            return InputPreferences(prefs) { newInstall }.read()
        }
        assertEquals(InputChoice.Auto(), read("auto"))
        assertEquals(InputChoice.Auto(newBluetoothOn = true), read("auto-bt"))
        assertEquals(InputChoice.Phone, read("phone"))
        assertEquals(InputChoice.Phone, read(null))
        assertEquals(InputChoice.Auto(), read(null, newInstall = true))
        assertEquals(InputChoice.Device("CardDevice:AA", "名字"), read("device", "CardDevice:AA"))
        val bt = read("device", "Bluetooth:DJI") as InputChoice.Auto
        assertEquals(listOf("Bluetooth:DJI", "type:Usb", "type:Wired", "type:Other"), bt.order.map { it.key })
        assertTrue(bt.order.all { it.enabled })
        val usb = read("device", "Usb:DJI") as InputChoice.Auto
        assertEquals(listOf("Usb:DJI", "type:Wired", "type:Other"), usb.order.map { it.key })
    }
}
