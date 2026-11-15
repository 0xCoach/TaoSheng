package app.resound.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 只记录固定代码位置和异常类别，禁止传入异常 message、用户文本或密钥。 */
object FailureNotes {
    private val notes = MutableStateFlow<List<String>>(emptyList())
    private val writer = MutableStateFlow<((String) -> Unit)?>(null)

    fun record(site: String, error: Throwable) {
        val line = "$site ${error::class.simpleName ?: "Throwable"}"
        notes.update { (it + line).takeLast(200) }
        // 诊断端失败不能改变业务结果；只在内存记第二次故障，不再递归调用持久化端。
        try { writer.value?.invoke(line) } catch (failure: Exception) {
            notes.update { (it + "diagnostics/write ${failure::class.simpleName}").takeLast(200) }
        }
    }

    /** 平台在现有崩溃诊断初始化处注入持久化；共用层不创建平台对象。 */
    fun install(write: (String) -> Unit) { writer.value = write }
    fun dump(): String = notes.value.joinToString("\n")
}
