package app.resound.skills

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.digest.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SkillRepository177Test {
    @Test fun queryStatusAndSettingsMatchOldRepository() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "skills177").apply { deleteRecursively(); mkdirs() }
        val files = SkillFiles(root) { File("src/main/assets/skills").listFiles()!!.map { dir ->
            SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()
        } }
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val date = LocalDate.parse("2026-11-01")
        val now = date.atTime(22, 0).atZone(SKILL_ZONE).toInstant().toEpochMilli()
        val output = mutableListOf<String>()
        try {
            val r = LocalSkillRepository(files, db.skills(), db.memory(), scope,
                { LlmPreset("test", "测试", "https://example.invalid", "test") to "test-only" },
                { _, _, _, _ -> LlmReply("{}", 0, 0) }, { emptyList() }, object : Notifier {
                    override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {}
                }, { _, _ -> }, { now }, io = kotlinx.coroutines.Dispatchers.IO)
            output += "initial=${r.generating.value}/${r.generated.value}/${r.cards(date).first()}/${r.card(404).first()}"
            output += "generateError=" + runCatching { r.generate("meeting", date) }.exceptionOrNull()?.message
            r.setEnabled("meeting", false); r.setPush("meeting", true); r.setPreset("meeting", "test")
            r.setSchedule("meeting", -1, setOf(99)) // 非 Daily 原样忽略
            r.setSchedule("evening_note", 1200, setOf(1, 7))
            output += "badSchedule=" + runCatching { r.setSchedule("evening_note", -1, setOf(1)) }.isFailure
            val original = r.defaultPrompt("meeting")
            r.savePrompt("meeting", "合成要求").getOrThrow(); output += "prompt=${r.prompt("meeting")},default=${r.defaultPrompt("meeting") == original}"
            r.resetPrompt("meeting"); output += "reset=${r.prompt("meeting") == original}"
            val id = r.copyAsMine("meeting").getOrThrow()
            r.saveMine(r.skills.value.single { it.id == id }.copy(name = "我的会议"), "我的要求").getOrThrow()
            output += "mine=$id/${r.prompt(id)}/${r.skills.value.single { it.id == id }}"
            r.deleteMine(id)
            output += "skills=${r.skills.value}"
            db.skills().run(SkillRunRow(skillId = "meeting", date = date.toEpochDay(), at = now - 1, ok = false, result = "旧失败", tokens = 9, kind = SkillSchedule.MANUAL, retryable = false))
            val status = withTimeout(5000) { r.status.first { it["meeting"]?.tokens30d == 9L && it.keys.size == files.skills.value.size } }
            output += "status=$status"
            val row = SkillCardRow(skillId = "meeting", skillVersion = 1, skillName = "旧会议", icon = "meeting", date = date.toEpochDay(), entity = "synthetic", at = now - 100, endAt = now, title = "合成会议", summary = "摘要", fieldsJson = "[]", valuesJson = "{}", evidenceJson = "[1,2]", generatedAt = now, manual = true)
            val saved = db.skills().merge(row, 8)!!.first
            output += "cards=${r.cards(date).first()}"
            output += "cardsOf=${r.cardsOf("meeting").first()}"
            output += "card=${r.card(saved.id).first()}"
            output += "withCards=${r.skillsWithCards.first()}"
            r.deleteCard(saved.id)
            output += "deleted=" + withTimeout(5000) { r.cards(date).first { it.isEmpty() } }
            val text = output.joinToString("\n", postfix = "\n")
            val target = File("src/test/resources/repo177/LocalSkillRepository/queries-settings.txt")
            assertEquals(target.readText(), text)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); root.deleteRecursively() }
    }
}
