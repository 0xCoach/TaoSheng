package app.resound.audio

import android.media.AudioDeviceInfo as D

/** 系统 id 仅用于当前路由；持久标识不使用会随拔插变化的 id/USB 地址。 */
internal data class InputPort(val id: Int, val type: Int, val name: String, val address: String = "")
internal data class InputRoute(val device: InputDevice, val portId: Int? = null)
internal data class InputSnapshot(val choice: InputChoice, val routes: List<InputRoute>) {
    fun selected(): InputRoute = when (val value = choice) {
        InputChoice.Phone -> InputRoute(InputDevices.PHONE)
        InputChoice.AutoExternal -> routes.firstOrNull() ?: InputRoute(InputDevices.PHONE)
        is InputChoice.Device -> routes.find { it.device.key == value.key } ?: InputRoute(InputDevices.PHONE)
    }
}

internal class InputCatalog {
    private var sequence = 0L
    private var seen = emptyMap<String, Long>()
    fun update(ports: List<InputPort>): List<InputRoute> {
        val bluetoothPorts = ports.filter { it.type == D.TYPE_BLE_HEADSET || it.type == D.TYPE_BLUETOOTH_SCO }
        // 同耳机的某个 profile 可能不暴露地址；只有名字对应唯一地址时才补齐，避免混淆同名耳机。
        val addresses = bluetoothPorts.filter { it.address.isNotBlank() }.groupBy { it.name.trim() }
            .mapValues { (_, rows) -> rows.map { it.address.trim().uppercase() }.distinct().singleOrNull() }
        val grouped = ports.mapNotNull { port -> kind(port.type)?.takeUnless { it == InputKind.Phone }?.let { kind ->
            val bluetooth = kind == InputKind.BluetoothLe || kind == InputKind.BluetoothCall
            val category = if (bluetooth) "Bluetooth" else kind.name
            val name = port.name.trim().ifEmpty { kind.title }
            val address = if (bluetooth) port.address.trim().uppercase().ifEmpty { addresses[name].orEmpty() } else ""
            Triple("$category:$name" + if (address.isNotEmpty()) ":$address" else "", port, kind)
        } }.groupBy { it.first }
        val next = grouped.mapValues { (key, _) -> seen[key] ?: ++sequence }
        seen = next
        return grouped.map { (key, entries) ->
            val (_, port, kind) = entries.sortedWith(compareBy<Triple<String, InputPort, InputKind>> {
                if (it.third == InputKind.BluetoothLe) 0 else 1
            }.thenBy { it.second.id }).first()
            InputRoute(InputDevice(key, port.name.trim().ifEmpty { kind.title }, kind, true), port.id)
        }.sortedByDescending { next[it.device.key] }
    }
    companion object {
        // AOSP TYPE_ECHO_REFERENCE 是隐藏常量，公开 SDK 不导出。
        const val ECHO_REFERENCE = 28
        fun kind(type: Int): InputKind? = when (type) {
            D.TYPE_BUILTIN_MIC -> InputKind.Phone
            D.TYPE_USB_DEVICE, D.TYPE_USB_HEADSET, D.TYPE_USB_ACCESSORY -> InputKind.Usb
            D.TYPE_WIRED_HEADSET -> InputKind.Wired
            D.TYPE_BLE_HEADSET -> InputKind.BluetoothLe
            D.TYPE_BLUETOOTH_SCO -> InputKind.BluetoothCall
            D.TYPE_TELEPHONY, D.TYPE_REMOTE_SUBMIX, D.TYPE_FM_TUNER, D.TYPE_TV_TUNER,
            ECHO_REFERENCE -> null
            else -> InputKind.Other
        }
        fun visible(routes: List<InputRoute>, choice: InputChoice): List<InputDevice> {
            val devices = listOf(InputDevices.PHONE) + routes.map { it.device }
            return if (choice is InputChoice.Device && devices.none { it.key == choice.key }) {
                val kind = when (choice.key.substringBefore(':')) {
                    "Bluetooth" -> InputKind.BluetoothCall
                    else -> InputKind.entries.find { it.name == choice.key.substringBefore(':') } ?: InputKind.Other
                }
                devices + InputDevice(choice.key, choice.name, kind, false)
            } else devices
        }
    }
}
