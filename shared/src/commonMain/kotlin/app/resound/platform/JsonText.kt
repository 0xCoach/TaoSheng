package app.resound.platform

import kotlinx.serialization.json.JsonElement

/** 保留旧安卓 JSON 文本协议对斜杠的转义，字段插入顺序由 JsonObject 保留。 */
fun JsonElement.legacyText(): String = toString().replace("/", "\\/")

/** 旧 JSON 读取约定允许字符串、数值以及容器转成文本；保持既有模型回复兼容性。 */
fun JsonElement.legacyValue(): String = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> legacyText()
}

fun JsonElement.legacyLong(): Long {
    val value = this as kotlinx.serialization.json.JsonPrimitive
    return if (value.isString) value.content.toDouble().toLong()
        else value.content.toLongOrNull() ?: value.content.toDouble().toLong()
}
