package app.resound.prompts

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LocalPromptTemplates(context: Context) : PromptTemplates {
    private val directory = File(context.filesDir, "prompts")
    private val defaults = ids.associateWith { id ->
        context.assets.open("prompts/$id.md").bufferedReader().use { it.readText() }
    }
    private val metadata = listOf(
        Triple("digest", "整理成事件", "把转写原话整理成事件和提取项"),
        Triple("review", "每日回顾", "根据已整理的事件生成每日回顾"),
        Triple("ask", "问问", "检索记忆并回答问题"),
    )
    private val mutable = MutableStateFlow(infos())
    override val list = mutable.asStateFlow()
    private fun file(id: String): File {
        require(id in ids) { "未知的指令模板" }
        return File(directory, "$id.md")
    }
    override fun text(id: String): String = synchronized(fileLock) {
        val atomic = AtomicFile(file(id))
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            atomic.openRead().bufferedReader().use { it.readText() }
        } else defaultText(id)
    }
    override fun defaultText(id: String): String = defaults[id] ?: error("未知的指令模板")
    private fun required(id: String) = Regex("\\{\\{[^{}]+\\}\\}").findAll(defaultText(id)).map { it.value }.distinct().toList()
    private fun infos() = metadata.map { (id, title, description) ->
        PromptInfo(id, title, description, text(id) != defaultText(id), required(id))
    }
    override fun save(id: String, text: String): Result<Unit> = synchronized(fileLock) {
        runCatching {
            val target = file(id)
            require(text.isNotBlank()) { "指令模板不能为空" }
            val purpose = when (id) { "digest" -> "整理"; "review" -> "回顾"; else -> "问答" }
            required(id).firstOrNull { it !in text }?.let { error("缺少 $it，${purpose}时会用到") }
            check(directory.isDirectory || directory.mkdirs()) { "模板保存失败，请重试" }
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw IllegalStateException("模板保存失败，请重试") }
            mutable.value = infos()
        }
    }
    override fun reset(id: String): Unit = synchronized(fileLock) {
        val target = file(id)
        AtomicFile(target).delete()
        check(!target.exists() && !File(target.path + ".bak").exists()) { "恢复默认失败，请重试" }
        mutable.value = infos()
    }
    companion object {
        internal val ids = listOf("digest", "review", "ask")
        // AtomicFile 本身不串行化读写；备份与编辑共享此锁。
        internal val fileLock = Any()
    }
}
