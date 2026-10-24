package app.resound.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * 卡 045：录音输入设备。默认手机麦克风；系统认得的外接麦克风（Type-C/USB 麦、领夹麦接收器、有线耳机、蓝牙耳机）都能选。
 * 后面的人声检测、切段、转写不变，换设备就是换一个 [AudioSource]。
 */
enum class InputKind(val title: String) {
    Phone("手机麦克风"),
    Usb("USB / Type-C 麦克风"),
    Wired("有线耳机"),
    BluetoothLe("蓝牙耳机（LE Audio）"),
    BluetoothCall("蓝牙耳机（通话音质）"),
    Other("其他输入"),
}

data class InputDevice(
    /** 稳定标识，拔插后不变；手机麦克风固定为 "mic"。录音片段的 sourceId 就写它。 */
    val key: String,
    /** 给人看的名字，例如 "DJI Mic Mini"、"AirPods Pro" */
    val name: String,
    val kind: InputKind,
    /** 现在是否接着 */
    val connected: Boolean,
)

/** 用户选哪个输入 */
sealed interface InputChoice {
    /** 手机麦克风（默认） */
    data object Phone : InputChoice

    /** 有外接麦克风就用外接（多个时用最近接上的），都没有用手机麦克风 */
    data object AutoExternal : InputChoice

    /** 指定某个设备；没接着时先用手机麦克风，接上自动切过去 */
    data class Device(val key: String, val name: String) : InputChoice
}

data class InputStatus(
    /** 现在实际在录的设备 */
    val using: InputDevice,
    /** 给人看的一句提示，例如 “DJI Mic Mini 断开了，已换回手机麦克风”；没事为 null */
    val note: String?,
)

interface InputDevices {
    val choice: StateFlow<InputChoice>

    /** 立刻生效：正在录的片段按正常结束收尾，从新设备开始新片段。设置随备份。 */
    fun choose(choice: InputChoice)

    /** 手机麦克风排第一，其后是接着的外接设备；选中的设备没接着时也列出（connected = false）。 */
    val devices: StateFlow<List<InputDevice>>

    val status: StateFlow<InputStatus>

    companion object {
        val PHONE = InputDevice("mic", InputKind.Phone.title, InputKind.Phone, connected = true)
    }
}
