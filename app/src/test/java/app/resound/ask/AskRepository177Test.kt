package app.resound.ask

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.LlmStreamReply
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AskRepository177Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private fun suggestions() = AskSuggestions(context.getSharedPreferences("synthetic177-ask", 0), { false }, { "" }, { emptyList() })
    private fun fixture(name: String, value: String) {
        val path = File("src/test/resources/repo177/RoomAskRepository/$name.txt")
        assertEquals(path.readText(), value)
    }
    private suspend fun finished(repo: RoomAskRepository, size: Int = 2) = withTimeout(5000) { repo.messages.first { it.size == size && !it.last().streaming } }
    @Test fun commandsAndSerializedMessagesMatchOldRepository() = runBlocking {
        var selected: Long? = 0
        val repo = RoomAskRepository(db.ask(), scope, { false }, { error("不得调用模型") }, { "" }, suggestions(), now = { 1000 }, readSelected = { selected }, saveSelected = { selected = it })
        val text = StringBuilder(); fun save(key: String, value: Any?) { text.appendLine("$key=$value") }
        save("suggestions", repo.suggestions.value)
        repo.ask("  合成查询  "); save("answer", finished(repo)); save("selected", selected)
        val first = repo.currentConversation.value!!
        save("rows", db.ask().messages(first)); save("history", repo.history(" 合成 ", 0).first())
        repo.renameConversation(first, " 改名 "); withTimeout(5000) { repo.history("改名", 10).first { it.size == 1 } }; save("renamed", repo.history("", 10).first())
        repo.newConversation(); withTimeout(5000) { repo.messages.first { it.isEmpty() } }; save("new", repo.currentConversation.value)
        repo.askAbout(AskSource("event", 9, 2000, "来源标题", "不应落库的全文"), "  关于它  ")
        save("about", finished(repo)); val second = repo.currentConversation.value!!
        save("about.rows", db.ask().messages(second))
        repo.deleteConversation(first); withTimeout(5000) { repo.history("", 10).first { it.size == 1 } }; save("delete.other", repo.currentConversation.value)
        repo.openConversation(second); repo.confirmAction(-999); repo.cancelAction(-999); repo.stop()
        // 串行命令的持久化改名作为此前命令完成的屏障。
        repo.renameConversation(second, "屏障"); withTimeout(5000) { repo.history("屏障", 10).first { it.size == 1 } }; save("after.noop", repo.messages.value)
        repo.deleteConversation(second); withTimeout(5000) { repo.messages.first { it.isEmpty() } }; save("deleted", repo.history("", 10).first()); save("selection.cleared", selected)
        fixture("commands", text.toString())
    }
    @Test fun stopRejectsLateCallbackAndKeepsSavedPartial() = runBlocking {
        val started = CompletableDeferred<(String) -> Unit>()
        val repo = RoomAskRepository(db.ask(), scope, { true }, { AskEngine(AskStream { _, _, delta, _ ->
            delta("半句"); started.complete(delta); awaitCancellation()
        }, JSONArray(), { _, _, _ -> JSONObject() }, { _, _ -> }, mode = { AskMode.Prefetch }, prefetch = { _, _ -> AskContext("合成资料", emptyList()) }) }, { "" }, suggestions(), now = { 1000 })
        repo.ask("问题")
        val late = withTimeout(5000) { started.await() }
        repo.stop(); val final = finished(repo)
        late("迟到内容")
        repo.newConversation(); withTimeout(5000) { repo.messages.first { it.isEmpty() } }
        late("新会话后迟到")
        fixture("stop", "final=$final\nsaved=${db.ask().messages(1)}\nafterNew=${repo.messages.value}\n")
    }
    @Test fun failedExchangeRollsBackBothMessages() = runBlocking {
        db.testSql.execSQL("CREATE TRIGGER synthetic177_fail BEFORE INSERT ON ask_messages WHEN NEW.role = 'Assistant' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        val repo = RoomAskRepository(db.ask(), scope, { false }, { error("不得调用模型") }, { "" }, suggestions(), now = { 1000 })
        repo.ask("保存失败")
        fixture("save-failure", "messages=${finished(repo)}\nrows=${db.ask().messages(1)}\n")
    }
}
