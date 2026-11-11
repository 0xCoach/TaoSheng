package app.resound.devices.android

import android.content.SharedPreferences
import app.resound.devices.driver.*
import app.resound.devices.hub.*

/** 独立 device-drivers 文件。字符串与字节使用独立前缀，组件按长度编码避免碰撞。 */
class AndroidHubRepository(private val prefs: SharedPreferences) : HubRepository {
    private fun component(value: String) = "${value.length}:$value"
    private fun field(handle: String, name: String) = "selection/${component(handle)}/$name"
    override fun selections(): List<DriverSelection> = prefs.all.keys.filter { it.startsWith("handle/") }.mapNotNull { key ->
        val handle = prefs.getString(key, null) ?: return@mapNotNull null
        val driver = prefs.getString(field(handle, "driver"), null) ?: return@mapNotNull null
        DriverSelection(handle, driver, prefs.getString(field(handle, "id"), null)?.let(::DeviceId))
    }
    override fun save(selection: DriverSelection) {
        check(prefs.edit().putString("handle/${component(selection.platformHandle)}", selection.platformHandle)
            .putString(field(selection.platformHandle, "driver"), selection.driverId)
            .putString(field(selection.platformHandle, "id"), selection.deviceId?.value).commit())
    }
    override fun forget(platformHandle: String) {
        val saved = selections().firstOrNull { it.platformHandle == platformHandle }
        val edit = prefs.edit().remove("handle/${component(platformHandle)}")
            .remove(field(platformHandle, "driver")).remove(field(platformHandle, "id"))
        if (saved != null) {
            val keys = listOfNotNull("handle:$platformHandle", saved.deviceId?.let { "id:${it.value}" })
            // 同一全局身份仍被另一句柄引用时，保留其进度。
            keys.filter { key -> selections().none { it.platformHandle != platformHandle && "id:${it.deviceId?.value}" == key } }
                .forEach { key ->
                    val prefix = namespace(saved.driverId, key)
                    prefs.all.keys.filter { it.startsWith(prefix) }.forEach(edit::remove)
                    edit.remove(disableKey(saved.driverId, key))
                }
        }
        check(edit.commit())
    }
    private fun namespace(driver: String, device: String) = "store/${component(driver)}/${component(device)}/"
    private fun disableKey(driver: String, device: String) = "disabled/${component(driver)}/${component(device)}"
    override fun disabled(driverId: String, deviceKey: String) = prefs.getString(disableKey(driverId, deviceKey), null)?.let(DisableReason::valueOf)
    override fun setDisabled(driverId: String, deviceKey: String, reason: DisableReason?) {
        check(prefs.edit().putString(disableKey(driverId, deviceKey), reason?.name).commit())
    }
    override fun store(driverId: String, deviceKey: String): DriverStore {
        val prefix = namespace(driverId, deviceKey)
        return object : DriverStore {
            override fun getString(key: String) = prefs.getString(prefix + "s/" + key, null)
            override fun putString(key: String, value: String?) { check(prefs.edit().putString(prefix + "s/" + key, value).commit()) }
            override fun getBytes(key: String) = prefs.getString(prefix + "b/" + key, null)?.let { hex ->
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            override fun putBytes(key: String, value: ByteArray?) {
                check(prefs.edit().putString(prefix + "b/" + key, value?.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }).commit())
            }
            override fun keys() = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).drop(2) }.toSet()
        }
    }
}
