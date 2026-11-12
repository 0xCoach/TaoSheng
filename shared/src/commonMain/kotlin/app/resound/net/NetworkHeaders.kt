package app.resound.net

import app.resound.digest.HeaderEntry

fun validateNetworkHeaders(headers: List<HeaderEntry>) {
    val forbidden = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")
    headers.forEach { entry ->
        require(entry.name.isNotEmpty() && entry.name.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
        }) { "请求头名称只能使用英文字母、数字和 HTTP 允许的符号，不能有空格或中文" }
        require(entry.name.lowercase() !in forbidden) { "不能修改 Host、Content-Length、Content-Type、Transfer-Encoding 或 Connection 请求头" }
        // OkHttp 的请求头使用可打印 ASCII；提前拒绝，防止其异常回显整个值。
        require(entry.value.all { it == '\t' || it.code in 32..126 }) { "请求头的值不能有回车、换行或其他控制字符；中文请先编码" }
    }
}

fun networkHeaders(key: String, headers: List<HeaderEntry>): List<Pair<String, String>> {
    validateNetworkHeaders(headers)
    require(key.all { it.code in 33..126 }) { "密钥格式不正确" }
    val result = mutableListOf<Pair<String, String>>()
    if (key.isNotBlank() && headers.none { it.name.equals("Authorization", true) }) result.add("Authorization" to "Bearer $key")
    headers.forEach { h -> result.removeAll { it.first.equals(h.name, true) }; result.add(h.name to h.value) }
    return result
}

fun decodeNetworkHeaders(text: String): List<HeaderEntry> {
    val array = Arr(text)
    return (0 until array.length()).map { i -> array.getJSONObject(i).let { HeaderEntry(it.getString("name"),it.getString("value")) } }.also(::validateNetworkHeaders)
}
fun encodeNetworkHeaders(headers: List<HeaderEntry>): String {
    validateNetworkHeaders(headers)
    return Arr(headers.map { Obj().put("name",it.name).put("value",it.value) }).toString()
}
