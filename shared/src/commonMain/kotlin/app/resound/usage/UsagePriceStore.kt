package app.resound.usage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同步修改由平台接入串行调用；提交回调必须一次写完所有键，失败返回 false。 */
class UsagePriceStore(
    private val contains: (String) -> Boolean,
    private val read: (String) -> String?,
    private val commit: (Map<String, String>) -> Boolean,
) {
    private fun price(key: String, default: Double) = UsageRules.price(contains(key), read(key), default)
    private val current = MutableStateFlow(UsagePrice(
        price("promptPerMillion", UsageRules.DEFAULT.promptPerMillion!!),
        price("completionPerMillion", UsageRules.DEFAULT.completionPerMillion!!),
        price("asrPerHour", UsageRules.DEFAULT.asrPerHour!!),
    ))
    val state = current.asStateFlow()
    fun set(price: UsagePrice) {
        UsageRules.validate(price)
        check(commit(linkedMapOf(
            "promptPerMillion" to price.promptPerMillion?.toString().orEmpty(),
            "completionPerMillion" to price.completionPerMillion?.toString().orEmpty(),
            "asrPerHour" to price.asrPerHour?.toString().orEmpty(),
        ))) { "单价保存失败" }
        current.value = price
    }
}
