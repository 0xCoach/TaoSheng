package app.resound.audio

import app.resound.diagnostics.FailureNotes

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import app.resound.devices.*
import app.resound.devices.android.DeviceInputs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 卡 223：两个“自动”合并成一个带顺序的列表；旧的选法读出来时换成等价的新选法 */
internal class InputPreferences(private val prefs: SharedPreferences, private val newInstall: () -> Boolean = { false }) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun read(): InputChoice = when (prefs.getString("choice", null)) {
        "phone" -> InputChoice.Phone
        "auto2" -> runCatching {
            val order = json.decodeFromString<List<AutoInput>>(prefs.getString("order", null) ?: "[]")
            InputChoice.Auto(order.ifEmpty { InputChoice.Auto.DEFAULT }, prefs.getBoolean("newBluetoothOn", false))
        }.onFailure { FailureNotes.record("audio/AndroidInputDevices.kt:read", it) }.getOrDefault(InputChoice.Auto())
        // 旧“自动”：只用 USB、有线；旧“自动（含蓝牙）”：新连上的蓝牙也自动用
        "auto" -> InputChoice.Auto()
        "auto-bt" -> InputChoice.Auto(newBluetoothOn = true)
        "device" -> prefs.getString("key", null)?.takeIf { it.isNotBlank() }?.let { key ->
            val name = prefs.getString("name", key) ?: key
            // 专用录音设备仍是“只用这台”；以前指定的普通麦克风换成自动、排第一、开着
            if (key.startsWith("CardDevice:")) InputChoice.Device(key, name)
            else InputChoice.Auto(listOf(AutoInput(key, name, legacyKind(key), true)) + InputChoice.Auto.DEFAULT.filter { it.key != InputChoice.Auto.TYPE + legacyKind(key).name })
        } ?: InputChoice.Phone
        // 没选过：新装默认自动，从旧版升级的保持原来的手机麦克风
        else -> if (newInstall()) InputChoice.Auto() else InputChoice.Phone
    }
    private fun legacyKind(key: String) = when (key.substringBefore(':')) {
        "Bluetooth" -> InputKind.BluetoothCall
        else -> InputKind.entries.find { it.name == key.substringBefore(':') } ?: InputKind.Other
    }
    fun write(choice: InputChoice) {
        val edit = prefs.edit().remove("key").remove("name").remove("order").remove("newBluetoothOn")
        when (choice) {
            InputChoice.Phone -> edit.putString("choice", "phone")
            is InputChoice.Auto -> edit.putString("choice", "auto2").putString("order", json.encodeToString(choice.order))
                .putBoolean("newBluetoothOn", choice.newBluetoothOn)
            is InputChoice.Device -> edit.putString("choice", "device").putString("key", choice.key).putString("name", choice.name)
        }
        check(edit.commit()) { "录音输入设置保存失败" }
    }
}

/** Application 持有，回调与进程同寿命；快照把选择和系统端口一次性发布给录音调度器。 */
class AndroidInputDevices(context: Context) : InputDevices {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val prefs = InputPreferences(context.getSharedPreferences("inputs", Context.MODE_PRIVATE)) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime } }.getOrDefault(false)
    }
    private val catalog = InputCatalog()
    private var cardRoutes = emptyList<InputRoute>()
    private var onChoice: (InputChoice) -> Unit = {}
    internal fun attachCards(devices: DeviceInputs, cards: DeviceCards, scope: CoroutineScope) {
        onChoice = devices::select
        devices.select(choice.value)
        scope.launch {
            combine(devices.devices, cards.cards) { states, installed ->
                states.filter { state -> devices.hasLiveInput(state.cardId, installed) }.map {
                    InputRoute(InputDevice(DeviceInputs.deviceKey(it.address), it.name, InputKind.CardDevice,
                        it.status == DeviceStatus.Live || it.status == DeviceStatus.Backfilling))
                }
            }.collect { routes -> synchronized(this@AndroidInputDevices) { cardRoutes = routes; refresh() } }
        }
    }
    private val mutableChoice = MutableStateFlow(prefs.read())
    override val choice = mutableChoice.asStateFlow()
    private val mutableDevices = MutableStateFlow(InputCatalog.visible(emptyList(), choice.value))
    override val devices = mutableDevices.asStateFlow()
    private val mutableStatus = MutableStateFlow(InputStatus(InputDevices.PHONE, null))
    override val status = mutableStatus.asStateFlow()
    internal val snapshots = MutableStateFlow(InputSnapshot(choice.value, emptyList()))
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    init {
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }
    @Synchronized private fun refresh() {
        val ports = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            InputPort(it.id, it.type, it.productName.toString(), runCatching { it.address }.onFailure {
                FailureNotes.record("audio/AndroidInputDevices.kt:73", it)
            }.getOrDefault(""))
        }
        publish(catalog.update(ports) + cardRoutes)
    }
    private fun publish(routes: List<InputRoute>) {
        // 卡 223：新连上的蓝牙设备记进“自动”列表（默认关），设置页才能单独打开它
        (choice.value as? InputChoice.Auto)?.let { auto ->
            val learned = auto.learn(routes.map { it.device })
            if (learned != auto) { prefs.write(learned); mutableChoice.value = learned }
        }
        mutableDevices.value = InputCatalog.visible(routes, choice.value)
        snapshots.value = InputSnapshot(choice.value, routes)
    }
    @Synchronized override fun choose(choice: InputChoice) {
        prefs.write(choice)
        mutableChoice.value = choice
        onChoice(choice)
        // 权限刚授权时重新枚举，让地址与能力信息及时生效。
        refresh()
    }
    internal fun report(device: InputDevice, note: String?) { mutableStatus.value = InputStatus(device, note) }
    internal fun port(id: Int): AudioDeviceInfo? = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).find { it.id == id }
}
