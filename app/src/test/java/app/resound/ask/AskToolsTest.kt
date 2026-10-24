package app.resound.ask

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AskToolsTest {
    @Test fun allToolsFilterOrEscapingTimeSpeakerDeletedAndCap() {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sqlite=db.openHelper.writableDatabase
            val at=AskTools.timestamp("2026-10-23 15:00",false,ZoneId.of("Asia/Shanghai"))
            sqlite.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?, 'mic','/test',1,'Done')",arrayOf<Any>(at,at+1000))
            for (id in 1..3) sqlite.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(?,?,?,'会议',?,?,'[\"投屏\"]','[\"老王\"]',0,?,?, 'fake')",arrayOf<Any>(id,at,at+1000,"会议$id","会议室方案",if(id==3) 1 else 0,at))
            sqlite.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerTag,speakerName,eventId) VALUES(1,1,?,?, '价格100%_确定','0','我',1)",arrayOf<Any>(at,at+1000))
            sqlite.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerTag,eventId) VALUES(2,1,?,?, '老王的安排','1',2)",arrayOf<Any>(at+60000,at+61000))
            sqlite.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId) VALUES(3,1,?,?, '已删除的老王',3)",arrayOf<Any>(at,at+1000))
            sqlite.execSQL("INSERT INTO memory_items(id,eventId,category,content,lineId,status,createdAt) VALUES(1,1,'灵感','会议室投屏',1,'Pending',?)",arrayOf<Any>(at))
            sqlite.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(2,3,'灵感','被删除的','Pending',?)",arrayOf<Any>(at))
            val tools=AskTools(db,JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()),ZoneId.of("Asia/Shanghai"))
            val sources=mutableListOf<AskSource>()
            fun find(name:String,args:String)=tools.execute(name,JSONObject(args),sources).getJSONArray("results")
            assertEquals(2,find("search_lines","""{"keywords":["100%_","老王"]}""").length())
            assertEquals(1,find("search_lines","""{"keywords":["%_"],"speaker":"我","from":"2026-10-23","to":"2026-10-23 15:00"}""").length())
            assertEquals(0,find("search_lines","""{"keywords":["' OR 1=1 --"]}""").length())
            assertEquals(2,find("search_events","""{"keywords":["投屏"]}""").length())
            assertEquals(2,find("search_events","""{"keywords":["老王"]}""").length())
            assertEquals(2,find("get_day","""{"date":"2026-10-23"}""").length())
            assertEquals(0,find("get_day","""{"date":"2026-10-22"}""").length())
            assertEquals(1,find("list_items","""{"category":"灵感","status":"Pending","from":"2026-10-23","to":"2026-10-23"}""").length())
            assertEquals(0,find("list_items","""{"status":"Done"}""").length())
            assertTrue(sources.any { it.kind=="item" && it.quote=="价格100%_确定" })
            for(id in 4..60) sqlite.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(?,1,?,?,?)",arrayOf<Any>(id,at,at+1,"长".repeat(1000)))
            val result=tools.execute("search_lines",JSONObject("""{"keywords":[]}"""),sources)
            assertTrue(result.getBoolean("truncated")); assertTrue(result.toString().length<=6000)
            assertTrue(result.getJSONArray("results").length()<=40)
            assertTrue(sources.all { s -> s.quote.length<=900 })
            try { tools.query("get_day",JSONObject("""{"date":"2026-02-30"}""")); fail("非法日期") }
            catch(_:java.time.DateTimeException) {}
        } finally { db.close() }
    }
}
