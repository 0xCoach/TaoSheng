package app.resound.scheduling

object BackgroundSystem {
    data class Signals(val manufacturer: String, val brand: String, val platform: String = "",
        val emui: String = "", val magic: String = "", val osBrand: String = "", val installer: String = "")
    enum class Type(val label: String) { Android("普通安卓"), Huawei("华为EMUI"), Honor("荣耀MagicOS"), Harmony("鸿蒙(安卓内核)"), Container("鸿蒙卓易通容器") }
    fun identify(signals: Signals): Type = with(signals) {
        when {
            installer == "com.zhuoyi.appstore.lite" -> Type.Container
            osBrand.equals("harmony", true) || platform.isNotBlank() -> Type.Harmony
            magic.isNotBlank() || manufacturer.equals("honor", true) || brand.equals("honor", true) -> Type.Honor
            emui.isNotBlank() || manufacturer.equals("huawei", true) || brand.equals("huawei", true) -> Type.Huawei
            else -> Type.Android
        }
    }
    /** 所有平台跳转都经过这个失败安全链，详情页也可能不存在。 */
    fun openFirst(targets: List<String>, open: (String) -> Boolean): Boolean {
        for (target in targets) if (runCatching { open(target) }.getOrDefault(false)) return true
        return false
    }
}
