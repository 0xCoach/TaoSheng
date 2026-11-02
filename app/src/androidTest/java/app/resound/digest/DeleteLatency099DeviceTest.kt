package app.resound.digest

import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.data.*
import app.resound.privacy.RoomPrivacyActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.measureTimeMillis

/** 独立 validation 包、独立内存库；不读取用户数据库、不请求模型。 */
@RunWith(AndroidJUnit4::class)
class DeleteLatency099DeviceTest {
    @Test fun sevenDayStatisticsNoLongerBlockSyntheticDeletion() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.resound.validation")
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .addCallback(app.resound.things.ThingSchema.callback)
            .addCallback(PendingDigestSchema.callback).build()
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.parse("2026-11-03")
        val until = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val from = date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        try {
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                val text = "synthetic words ".repeat(140)
                val segment = sql.compileStatement("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(?,?,?,'mic',?,0,'Done')")
                val event = sql.compileStatement("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(?,?,?,?,'合成事件','合成摘要','[]','[]',0,0,?,'synthetic')")
                val line = sql.compileStatement("INSERT INTO transcript_lines(segmentId,startAt,endAt,text,eventId) VALUES(?,?,?,?,?)")
                try {
                    for (id in 1L..2820L) {
                        val at = if (id <= 2800) from + ((id - 1) / 400) * 86_400_000L + ((id - 1) % 400) * 150_000L
                            else until + (id - 2801) * 150_000L
                        segment.bindLong(1, id); segment.bindLong(2, at); segment.bindLong(3, at + 150_000)
                        segment.bindString(4, "/synthetic-099/$id.ogg"); segment.executeInsert()
                        event.bindLong(1, id); event.bindLong(2, at); event.bindLong(3, at + 150_000)
                        event.bindString(4, if (id <= 2800 && id % 10 == 0L) "媒体" else "会议")
                        event.bindLong(5, at); event.executeInsert()
                        repeat(5) { n ->
                            line.bindLong(1, id); line.bindLong(2, at + n * 30_000)
                            line.bindLong(3, at + n * 30_000 + 20_000)
                            line.bindString(4, text); line.bindLong(5, id); line.executeInsert()
                        }
                    }
                } finally { line.close(); event.close(); segment.close() }
                // 批量构造已有历史数据，预先分词避免把建夹具时间花在逐句 SQL 分词上。
                // 测量前安装生产触发器并填充真实索引；统计与删除都走原有数据库路径。
                app.resound.search.SearchIndex.install(sql, backfill = false)
                fun tokens(value: String) = app.resound.search.SearchTokens.split(value).joinToString(" ")
                sql.execSQL("INSERT INTO lines_fts(rowid,text) SELECT id,? FROM transcript_lines", arrayOf(tokens(text)))
                sql.execSQL("INSERT INTO events_fts(rowid,title,summary,points) SELECT id,?,?,' ' FROM memory_events",
                    arrayOf(tokens("合成事件"), tokens("合成摘要")))
            }
            Log.i("DeleteLatency099", "synthetic_fixture_ready")
            val privacy = RoomPrivacyActions(db)
            val oldSpans = mutableListOf<RhythmSpan>()
            val enteredOld = CompletableDeferred<Unit>()
            val old = async(Dispatchers.IO) {
                measureTimeMillis {
                    db.withTransaction {
                        enteredOld.complete(Unit)
                        db.openHelper.readableDatabase.query("""SELECT l.* FROM transcript_lines l
                            LEFT JOIN memory_events e ON e.id=l.eventId WHERE l.startAt >= $from AND l.startAt < $until
                            AND (e.id IS NULL OR e.deleted=0) AND NOT EXISTS (SELECT 1 FROM memory_events m WHERE m.deleted=0
                            AND m.scene='媒体' AND l.startAt<m.endAt AND l.endAt>m.startAt) ORDER BY l.startAt,l.id""").use { cursor ->
                            val start = cursor.getColumnIndexOrThrow("startAt"); val end = cursor.getColumnIndexOrThrow("endAt")
                            while (cursor.moveToNext()) oldSpans += RhythmSpan(cursor.getLong(start), cursor.getLong(end))
                        }
                    }
                }
            }
            enteredOld.await()
            val oldDeleteMs = measureTimeMillis { assertEquals(1, privacy.forgetEvent(2801).events) }
            val oldMs = old.await()
            val enteredNew = CompletableDeferred<Unit>()
            var snapshot: RhythmSnapshot? = null
            val fresh = async(Dispatchers.IO) {
                measureTimeMillis {
                    db.withTransaction {
                        enteredNew.complete(Unit)
                        snapshot = db.memory().rhythmSnapshot(from, until)
                    }
                }
            }
            enteredNew.await()
            val newDeleteMs = measureTimeMillis { assertEquals(1, privacy.forgetEvent(2802).events) }
            val newMs = fresh.await()
            val stats = RoomRhythmStats(db, zone)
            val data = requireNotNull(snapshot)
            var baseline: RhythmBaseline? = null
            val calculateMs = measureTimeMillis { baseline = stats.calculate(data) }
            assertEquals(stats.calculate(data.copy(lines = oldSpans, media = emptyList())), baseline)
            assertEquals(12600, oldSpans.size)
            assertTrue("Lightweight snapshot must beat old full-text query", newMs < oldMs)
            assertTrue("Deletion should not wait multiple seconds behind statistics", newDeleteMs < 2000)
            val ready = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) { stats.baseline(date).collect { ready.complete(Unit) } }
            val batchMs: Long
            try {
                withTimeout(10_000) { ready.await() }
                batchMs = measureTimeMillis {
                    for (id in 2803L..2820L) assertEquals(1, privacy.forgetEvent(id).events)
                }
            } finally { collector.cancelAndJoin() }
            assertTrue(privacy.eventProgress.value.isEmpty())
            assertTrue("18 sequential deletes under live statistics", batchMs < 5000)
            Log.i("DeleteLatency099", "synthetic_lines=14000 events=2800 old_query_ms=$oldMs new_snapshot_ms=$newMs calculate_ms=$calculateMs old_queued_delete_ms=$oldDeleteMs new_queued_delete_ms=$newDeleteMs batch18_ms=$batchMs same_baseline=true")
        } finally { db.close() }
    }
}
