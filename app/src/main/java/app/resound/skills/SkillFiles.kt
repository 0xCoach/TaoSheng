package app.resound.skills

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** 所有写入和备份共用锁；先落盘，再发布 StateFlow。 */
class SkillFiles(private val root: File, private val official: () -> List<Pair<SkillDefinition, String>>) {
    constructor(context: Context) : this(File(context.filesDir, "skills"), {
        context.assets.list("skills").orEmpty().map { id ->
            SkillJson.parse(context.assets.open("skills/$id/skill.json").bufferedReader().use { it.readText() }, SkillOrigin.Builtin) to
                context.assets.open("skills/$id/prompt.md").bufferedReader().use { it.readText() }
        }
    })
    companion object { val fileLock = Any() }
    private val definitions = linkedMapOf<String, Pair<SkillDefinition, String>>()
    /** 我的版本的“默认要求”：复制来的是官方默认，新建的是创建时那份；旧数据没有时等于当前基础要求 */
    private val mineDefaults = mutableMapOf<String, String>()
    private val state = MutableStateFlow<List<Skill>>(emptyList())
    val skills = state.asStateFlow()
    init { reload() }
    private fun read(file: File): String? = AtomicFile(file).let { a ->
        if (file.exists() || File(file.path + ".bak").exists()) a.openRead().bufferedReader().use { it.readText() } else null
    }
    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val a = AtomicFile(file); val out = a.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); a.finishWrite(out) }
        catch (e: Exception) { a.failWrite(out); throw e }
    }
    private fun overrides() = read(File(root, "overrides.json"))?.let(::JSONObject) ?: JSONObject()
    fun reload() = synchronized(fileLock) {
        definitions.clear(); mineDefaults.clear()
        official().sortedBy { it.first.order }.forEach { definitions[it.first.skill.id] = it }
        File(root, "mine").listFiles()?.filter { it.isDirectory && SkillJson.validKey(it.name) }?.sortedBy { it.name }?.forEach { dir ->
            // 单个损坏的自建技能不能阻止 App 启动；不覆盖损坏文件。
            runCatching {
                val d = SkillJson.parse(requireNotNull(read(File(dir, "skill.json"))), SkillOrigin.Mine)
                require(d.skill.id == dir.name && d.skill.id.startsWith("my_"))
                definitions[d.skill.id] = d to requireNotNull(read(File(dir, "prompt.md")))
                read(File(dir, "default.md"))?.let { mineDefaults[d.skill.id] = it }
            }
        }
        val overrides = overrides()
        state.value = cores() + definitions.values.map { (d, _) ->
            val s = d.skill; val o = overrides.optJSONObject(s.id) ?: JSONObject()
            s.copy(enabled = o.optBoolean("enabled", s.enabled), push = o.optBoolean("push", s.push),
                trigger = if (s.trigger is SkillTrigger.Daily && o.has("schedule")) SkillJson.trigger(o.getJSONObject("schedule")) else s.trigger,
                presetId = if (o.has("presetId")) o.nullString("presetId") else s.presetId,
                promptCustomized = read(File(root, "prompts/${s.id}.md")) != null ||
                    (mineDefaults[s.id]?.let { it != definitions[s.id]?.second } ?: false),
                upstreamUpdated = d.sourceId?.let { (definitions[it]?.first?.skill?.version ?: 0) > (d.sourceVersion ?: 0) } ?: false)
        }
    }
    private fun cores() = listOf("timeline" to "时间线整理", "review" to "每日回顾").map { (id, name) ->
        Skill(id, name, "核心功能，沿用原来的设置", id, SkillOrigin.Core, 1, true, SkillTrigger.Manual,
            SkillScope(), emptyList(), 1, false, null, false)
    }
    fun change(id: String, edit: (JSONObject) -> Unit) = synchronized(fileLock) {
        if (id !in definitions) return@synchronized
        val all = overrides(); val o = all.optJSONObject(id) ?: JSONObject()
        edit(o); all.put(id, o); write(File(root, "overrides.json"), all.toString(2)); reload()
    }
    /** 删除服务商后持久回到“跟随整理模型”，也覆盖我的版本定义里的默认引用。 */
    fun resetProviders(ids: Set<String>) = synchronized(fileLock) {
        reload()
        val affected = skills.value.filter { it.presetId in ids }
        if (affected.isEmpty()) return@synchronized
        val all = overrides()
        affected.forEach { s ->
            val o = all.optJSONObject(s.id) ?: JSONObject()
            all.put(s.id, o.put("presetId", JSONObject.NULL))
        }
        write(File(root, "overrides.json"), all.toString(2)); reload()
    }
    fun prompt(id: String): String = synchronized(fileLock) {
        if (id !in definitions) "" else read(File(root, "prompts/$id.md")) ?: definitions[id]?.second.orEmpty()
    }
    fun defaultPrompt(id: String): String = synchronized(fileLock) { mineDefaults[id] ?: definitions[id]?.second.orEmpty() }
    fun savePrompt(id: String, text: String): Result<Unit> = runCatching { synchronized(fileLock) {
        if (id in setOf("timeline", "review")) return@synchronized
        require(id in definitions && text.isNotBlank()) { "要求不能为空" }
        write(File(root, "prompts/$id.md"), text); reload()
    } }
    fun resetPrompt(id: String) = synchronized(fileLock) {
        if (id !in definitions) return@synchronized
        AtomicFile(File(root, "prompts/$id.md")).delete()
        // 我的版本：把基础要求也换回默认那份
        mineDefaults[id]?.let { if (it != definitions[id]?.second) write(File(root, "mine/$id/prompt.md"), it) }
        reload()
    }
    fun copyAsMine(id: String): Result<String> = runCatching { synchronized(fileLock) {
        val (d, _) = requireNotNull(definitions[id]); require(d.skill.origin == SkillOrigin.Builtin)
        var target = "my_$id"; var n = 2
        while (target in definitions || File(root, "mine/$target").exists()) target = "my_${id}_${n++}"
        val copy = requireNotNull(skills.value.find { it.id == id }).copy(id = target, name = "${d.skill.name}（我的版本）",
            origin = SkillOrigin.Mine, promptCustomized = false, upstreamUpdated = false)
        val definition = SkillDefinition(copy, d.order, id, d.skill.version)
        write(File(root, "mine/$target/prompt.md"), prompt(id))
        write(File(root, "mine/$target/default.md"), defaultPrompt(id))
        write(File(root, "mine/$target/skill.json"), SkillJson.encode(definition)); reload(); target
    } }
    fun saveMine(s: Skill, prompt: String): Result<Unit> = runCatching { synchronized(fileLock) {
        require(s.origin == SkillOrigin.Mine && s.id.startsWith("my_")); SkillJson.validate(s); require(prompt.isNotBlank())
        val old = definitions[s.id]?.first
        val version = if (old == null) 1 else old.skill.version + if (old.skill.fields != s.fields) 1 else 0
        val d = (old ?: SkillDefinition(s)).copy(skill = s.copy(version = version))
        write(File(root, "mine/${s.id}/prompt.md"), prompt)
        // 新建时留一份默认要求，以后改坏了能恢复；已有的不覆盖
        if (read(File(root, "mine/${s.id}/default.md")) == null) write(File(root, "mine/${s.id}/default.md"), prompt)
        write(File(root, "mine/${s.id}/skill.json"), SkillJson.encode(d))
        // 整份保存包含生效设置；旧覆盖不能盖住这次编辑。
        val all = overrides(); all.remove(s.id); write(File(root, "overrides.json"), all.toString(2))
        AtomicFile(File(root, "prompts/${s.id}.md")).delete(); reload()
    } }
    fun deleteMine(id: String) = synchronized(fileLock) {
        if (definitions[id]?.first?.skill?.origin != SkillOrigin.Mine) return@synchronized
        check(File(root, "mine/$id").deleteRecursively()); AtomicFile(File(root, "prompts/$id.md")).delete()
        val all = overrides(); all.remove(id); write(File(root, "overrides.json"), all.toString(2)); reload()
    }
}
