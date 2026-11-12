package app.resound.usage

object UsageRules {
    val DEFAULT = UsagePrice(2.0, 8.0, 0.9)
    fun price(present: Boolean, value: String?, default: Double): Double? =
        if (!present) default else value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun validate(price: UsagePrice) {
        require(listOf(price.promptPerMillion, price.completionPerMillion, price.asrPerHour)
            .all { it == null || it.isFinite() && it >= 0 }) { "单价必须是大于等于 0 的有限数值" }
    }
}
