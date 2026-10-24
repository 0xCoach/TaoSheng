package app.resound.voiceprint

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class VoiceprintMigrationTest {
    @Test fun versionEightKeepsSamplesAndManualCorrections() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "identity-v8.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/8.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,4000,'mic','/old.ogg',100,'Done')")
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerTag,speakerName) VALUES(2,1,1000,2000,'原话','0','我'),(3,1,2000,3000,'另一句','1',NULL)")
            old.execSQL("INSERT INTO voiceprint_samples(source,sourceLineId,segmentId,groupKey,vector,durationMillis,createdAt) VALUES('line',2,1,'tag:0',?,3000,1000)", arrayOf(VoiceprintMath.encode(FloatArray(192){0.1f})))
            old.execSQL("INSERT INTO voiceprint_exclusions VALUES(1,'tag:1',1000)")
            old.version = 8
        }
        val db = Room.databaseBuilder(context,CaptureDatabase::class.java,name).addMigrations(CaptureDatabase.MIGRATION_8_9, CaptureDatabase.MIGRATION_9_10).build()
        try {
            assertEquals(0L,db.voiceprint().samples().single().personId)
            assertEquals(0L,db.voiceprint().line(2)!!.speakerPersonId)
            assertTrue(db.voiceprint().line(2)!!.speakerManual)
            assertTrue(db.voiceprint().line(3)!!.speakerManual)
            assertEquals(listOf("tag:1"), db.voiceprint().exclusions(1))
            assertTrue(db.voiceprint().people().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionThreePreservesTranscriptDigestAndAcceptsNewBlobAndExclusion() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-006.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/3.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\u0024{TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,4000,'mic','/old.ogg',100,'Done')")
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerTag,speakerName,digestedAt) VALUES(2,1,1000,2000,'保留原话','0','我',3000)")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(CaptureDatabase.MIGRATION_1_2, CaptureDatabase.MIGRATION_2_3, CaptureDatabase.MIGRATION_3_4, CaptureDatabase.MIGRATION_4_5, CaptureDatabase.MIGRATION_5_6, CaptureDatabase.MIGRATION_6_7, CaptureDatabase.MIGRATION_7_8, CaptureDatabase.MIGRATION_8_9, CaptureDatabase.MIGRATION_9_10).build()
        try {
            assertEquals(10, db.openHelper.writableDatabase.version) // Room 校验所有表/列/索引/外键。
            val line = db.voiceprint().line(2)!!
            assertEquals("保留原话", line.text); assertEquals("我", line.speakerName)
            assertEquals(3000L, line.digestedAt); assertNull(line.speakerScore)
            assertNull(db.transcribe().segment(1).voiceprintProcessedAt)
            val vector = VoiceprintMath.encode(FloatArray(192) { 0.1f })
            db.voiceprint().saveSample(VoiceprintSampleEntity(source="line", sourceLineId=2, segmentId=1, groupKey="tag:0", vector=vector, durationMillis=3000, createdAt=9000))
            db.voiceprint().exclude(VoiceprintExclusionEntity(1, "tag:0", 9000))
            assertArrayEquals(vector, db.voiceprint().samples().single().vector)
            assertEquals(listOf("tag:0"), db.voiceprint().exclusions(1))
            db.voiceprint().result(listOf(2), "我", .8f); db.voiceprint().processed(1,9000)
            assertEquals(.8f, db.voiceprint().line(2)!!.speakerScore!!, 0f)
            assertEquals(9000L, db.transcribe().segment(1).voiceprintProcessedAt)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
