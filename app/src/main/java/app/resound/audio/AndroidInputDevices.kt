/*
 * [INPUT]: 现有采集装配与录音输入选择。
 * [OUTPUT]: 保持系统输入行为，并接入显式选择的 Sonicloud 录音卡。
 * [POS]: 应用输入链路；录音卡失联不得触发手机麦克风回退。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class InputPreferences(private val prefs: SharedPreferences) {
    fun read(): InputChoice = when (prefs.getString("choice", null)) {
        "auto" -> InputChoice.AutoExternal
        "recorder" -> InputChoice.Recorder(prefs.getString("key", "")!!, prefs.getString("name", "录音卡")!!)
        "device" -> prefs.getString("key", null)?.takeIf { it.isNotBlank() }?.let {
            InputChoice.Device(it, prefs.getString("name", it) ?: it)
        } ?: InputChoice.Phone
        else -> InputChoice.Phone
    }
    fun write(choice: InputChoice) {
        val edit = prefs.edit().clear()
        when (choice) {
            is InputChoice.Recorder -> edit.putString("choice", "recorder").putString("key", choice.key).putString("name", choice.name)
            InputChoice.Phone -> edit.putString("choice", "phone")
            InputChoice.AutoExternal -> edit.putString("choice", "auto")
            is InputChoice.Device -> edit.putString("choice", "device").putString("key", choice.key).putString("name", choice.name)
        }
        check(edit.commit()) { "录音输入设置保存失败" }
    }
}

/** Application 持有，回调与进程同寿命；快照把选择和系统端口一次性发布给录音调度器。 */
class AndroidInputDevices(context: Context) : InputDevices {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val prefs = InputPreferences(context.getSharedPreferences("inputs", Context.MODE_PRIVATE))
    private val catalog = InputCatalog()
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
            InputPort(it.id, it.type, it.productName.toString(), runCatching { it.address }.getOrDefault(""))
        }
        publish(catalog.update(ports))
    }
    private fun publish(routes: List<InputRoute>) {
        mutableDevices.value = InputCatalog.visible(routes, choice.value)
        snapshots.value = InputSnapshot(choice.value, routes)
    }
    @Synchronized override fun choose(choice: InputChoice) {
        prefs.write(choice)
        mutableChoice.value = choice
        // 权限刚授权时重新枚举，让地址与能力信息及时生效。
        refresh()
    }
    internal fun report(device: InputDevice, note: String?) { mutableStatus.value = InputStatus(device, note) }
    internal fun port(id: Int): AudioDeviceInfo? = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).find { it.id == id }
}
