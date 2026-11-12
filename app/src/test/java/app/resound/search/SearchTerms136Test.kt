package app.resound.search

import app.resound.data.testSql
import android.app.Application
import androidx.room.Room
import app.resound.ask.AskSource
import app.resound.ask.AskTools
import app.resound.data.CaptureDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 多关键词搜索不能依赖 FTS 括号语法：测试环境的 SQLite 没开增强语法，旧写法一条都查不到。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SearchTerms136Test {
    @Test fun multipleKeywordsMatchAnyAndMultiCharKeywordNeedsAllBigrams() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .addCallback(SearchIndex.callback).allowMainThreadQueries().build()
        try {
            val sql = db.testSql
            sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,9000,'mic','test',1,'Done')")
            listOf("给老王发方案", "老王来了", "预算定了", "王来老").forEachIndexed { i, text ->
                sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(?,1,?,?,?)", arrayOf<Any>(i + 1, 1000 + i, 1500 + i, text))
            }
            val tools = AskTools(db, JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()))
            fun ids(vararg words: String) = mutableListOf<AskSource>().also {
                tools.execute("search_lines", JSONObject().put("keywords", JSONArray(words.toList())), it)
            }.map { it.id }.sorted()
            assertEquals(listOf(1L, 2L, 3L), ids("老王", "预算"))
            assertEquals(listOf(2L), ids("老王来"))
        } finally { db.close() }
    }
}
