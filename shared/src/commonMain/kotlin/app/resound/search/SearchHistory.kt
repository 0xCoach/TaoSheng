package app.resound.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 最新十条搜索；存储函数由平台注入，修改串行完成后再发布状态。 */
class SearchHistory(private val read: () -> String?, private val write: (String) -> Unit) {
    private val gate = Mutex()
    private val _items = MutableStateFlow(runCatching {
        Json.decodeFromString<List<String>>(read() ?: "[]").map(String::trim).filter(String::isNotEmpty).distinct().take(10)
    }.getOrDefault(emptyList()))
    val items = _items.asStateFlow()
    suspend fun add(q: String) = gate.withLock {
        val value = q.trim()
        if (value.isNotEmpty()) save((listOf(value) + _items.value.filter { it != value }).take(10))
    }
    suspend fun remove(q: String) = gate.withLock { save(_items.value.filter { it != q.trim() }) }
    suspend fun clear() = gate.withLock { save(emptyList()) }
    private fun save(values: List<String>) { write(Json.encodeToString(values)); _items.value = values }
}
