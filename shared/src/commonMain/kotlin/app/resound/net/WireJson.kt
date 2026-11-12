package app.resound.net

import app.resound.platform.SharedJson
import kotlinx.serialization.json.*

/** 协议迁移的窄适配层：仅封装 JsonElement，保留旧服务的数字字符串与 null 语义。 */
class Obj private constructor(private val fields: MutableMap<String, Any?>) {
    constructor() : this(linkedMapOf())
    constructor(text: String) : this((SharedJson.parseToJsonElement(text) as JsonObject).mapValues { unwrap(it.value) }.toMutableMap())
    fun put(key: String, value: Any?): Obj = apply { if (value == null) fields.remove(key) else fields[key] = value }
    fun remove(key: String) = fields.remove(key)
    fun has(key: String) = fields.containsKey(key)
    fun isNull(key: String) = fields[key] == null || fields[key] == JsonNull
    fun length() = fields.size
    fun keys() = fields.keys
    fun opt(key: String): Any? = fields[key]?.let { if (it is JsonPrimitive && !it.isString && it != JsonNull) it.longOrNull ?: it.doubleOrNull ?: it.booleanOrNull else it }
    fun getBoolean(key: String) = getString(key).lowercase().toBooleanStrict()
    fun get(key: String): Any = fields[key] ?: error("缺少字段")
    fun optJSONObject(key: String) = fields[key] as? Obj
    fun optJSONArray(key: String) = fields[key] as? Arr
    fun getJSONArray(key: String) = optJSONArray(key) ?: error("字段不是数组")
    fun getJSONObject(key: String) = optJSONObject(key) ?: error("字段不是对象")
    fun getString(key: String): String = get(key).toString()
    fun optString(key: String, fallback: String = "") = fields[key]?.toString() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = fields[key]?.toString()?.toDoubleOrNull()?.toLong() ?: fallback
    fun optInt(key: String, fallback: Int = 0) = optLong(key, fallback.toLong()).toInt()
    fun getLong(key: String) = getString(key).let { it.toLongOrNull() ?: it.toDouble().toLong() }
    fun getDouble(key: String) = getString(key).toDouble()
    fun toString(indent: Int) = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), element())
    fun getInt(key: String) = getString(key).toDouble().toInt()
    fun optBoolean(key: String, fallback: Boolean = false) = fields[key]?.toString()?.lowercase()?.toBooleanStrictOrNull() ?: fallback
    fun element(): JsonObject = JsonObject(fields.mapValues { wrap(it.value) })
    override fun toString() = wire(element())
    companion object { val NULL = JsonNull }
}
class Arr private constructor(private val items: MutableList<Any?>, unused: Unit) {
    constructor() : this(mutableListOf(), Unit)
    constructor(text: String) : this((SharedJson.parseToJsonElement(text) as JsonArray).map { unwrap(it) }.toMutableList(), Unit)
    constructor(items: List<Any?>) : this(items.toMutableList(), Unit)
    fun put(value: Any?): Arr = apply { items.add(value ?: JsonNull) }
    fun length() = items.size
    fun get(i: Int): Any = items[i]!!.let { if (it is JsonPrimitive && !it.isString && it != JsonNull) it.longOrNull ?: it.doubleOrNull ?: it.booleanOrNull ?: it else it }
    fun optBoolean(i: Int, fallback: Boolean = false) = items.getOrNull(i)?.toString()?.lowercase()?.toBooleanStrictOrNull() ?: fallback
    fun getJSONObject(i: Int) = items[i] as Obj
    fun optJSONObject(i: Int) = items.getOrNull(i) as? Obj
    fun getString(i: Int) = items[i].toString()
    fun optString(i: Int) = items.getOrNull(i)?.toString().orEmpty()
    fun isNull(i: Int) = items.getOrNull(i) == null || items.getOrNull(i) == JsonNull
    fun getLong(i: Int) = items[i].toString().let { it.toLongOrNull() ?: it.toDouble().toLong() }
    fun element() = JsonArray(items.map(::wrap))
    override fun toString() = wire(element())
}
private fun unwrap(value: JsonElement): Any = when(value) {
    is JsonObject -> Obj(value.toString())
    is JsonArray -> Arr(value.toString())
    JsonNull -> JsonNull
    is JsonPrimitive -> if(value.isString) value.content else value
}
private fun wrap(value: Any?): JsonElement = when(value) {
    null -> JsonNull
    is JsonElement -> value
    is Obj -> value.element()
    is Arr -> value.element()
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
/** Android JSON 的转义与整数形式浮点数写法，保持请求正文稳定。 */
internal fun wire(value: JsonElement): String = when(value) {
    is JsonObject -> value.entries.joinToString(",", "{", "}") { wire(JsonPrimitive(it.key)) + ":" + wire(it.value) }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::wire)
    is JsonPrimitive -> if(value.isString) value.toString().replace("/", "\\/") else value.toString().removeSuffix(".0")
}
