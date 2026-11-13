package app.resound.data

import android.app.Application
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 显式 TAOSHENG_182_BENCHMARK=before/after 才运行；仅合成内存库，无手机/真实数据库。
 * 所有被审计的常驻 DAO Flow 同时存活，逐次写入后等待有关查询消费完，避免调度合并掩盖读放大。
 * 延迟是从提交前到收到查询结果（包含排队），不能当作各线程 CPU 占比。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TableObservers182Benchmark {
    private data class Measure(var calls: Long = 0, var rows: Long = 0, var nanos: Long = 0)
    @Test fun residentObservers() = runBlocking(Dispatchers.IO) {
        val mode = System.getenv("TAOSHENG_182_BENCHMARK")
        assumeTrue(mode == "before" || mode == "after")
        val sqlStats = ConcurrentHashMap<String, Measure>()
        var measuring = false
        val driver = object : SQLiteDriver {
            private val base = AndroidSQLiteDriver()
            override val hasConnectionPool = base.hasConnectionPool
            override fun open(fileName: String): SQLiteConnection {
                val connection = base.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        val track = measuring && sql.startsWith("SELECT", true) && !sql.contains("room_") && !sql.contains("sqlite_")
                        return object : SQLiteStatement by statement {
                            var start = 0L; var rows = 0L
                            override fun step(): Boolean {
                                if (start == 0L) start = System.nanoTime()
                                return statement.step().also { if (it) rows++ }
                            }
                            override fun close() {
                                if (track && start != 0L) {
                                    val elapsed = System.nanoTime() - start
                                    val m = sqlStats.computeIfAbsent(sql.replace(Regex("\\s+"), " ")) { Measure() }
                                    synchronized(m) { m.calls++; m.rows += rows; m.nanos += elapsed }
                                }
                                statement.close()
                            }
                        }
                    }
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(driver).build()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val signals = Channel<String>(Channel.UNLIMITED)
        val counts = ConcurrentHashMap<String, Measure>()
        var phaseStart = System.nanoTime()
        fun <T> watch(name: String, flow: Flow<T>, rows: (T) -> Int) {
            counts[name] = Measure()
            owner.launch { flow.collect { value ->
                val m = counts.getValue(name)
                synchronized(m) { m.calls++; m.rows += rows(value); m.nanos += System.nanoTime() - phaseStart }
                signals.send(name)
            } }
        }
        suspend fun settled(names: Set<String>) = withTimeout(30_000) {
            val remaining = names.toMutableSet()
            while (remaining.isNotEmpty()) remaining.remove(signals.receive())
            while (signals.tryReceive().isSuccess) { }
        }
        try {
            // 20k segments / 30k env_sounds 在一对一外键下不可共存。仅本压力库关闭 FK，
            // 后 10k 环境音是独立历史压力行；业务对照测试保持 FK 开启。
            db.useWriterConnection { it.executeSql("PRAGMA foreign_keys=OFF") }
            db.writeTransaction {
                db.writeSql("""WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20000)
                    INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status,attempts,sentMillis,playbackMillis,redoPending,voiceprintProcessedAt)
                    SELECT x,x*60000,x*60000+30000,'mic','synthetic-'||x,100,'Done',0,0,0,0,x FROM n""")
                db.writeSql("""WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<30000)
                    INSERT INTO env_sounds SELECT x,x*40000,x*40000+30000,'synthetic-'||x,'Keep',NULL,'Rain','','{}',0,0,0,0,1 FROM n""")
                repeat(100) { n ->
                    db.transcribe().insertUsage(AsrUsageEntity("day-$n", n * 1000L))
                }
            }
            measuring = true
            phaseStart = System.nanoTime()
            // 同时保留未改的小表/聚合订阅，避免只测四条优化路径。
            watch("samples-self", db.voiceprint().observeSamples()) { it.size }
            watch("samples-all", db.voiceprint().observeAllSamples()) { it.size }
            watch("people-voice", db.voiceprint().observePeople()) { it.size }
            watch("people-hotwords", db.voiceprint().observePeople()) { it.size }
            watch("digest-pending", db.memory().observeUndigestedCount()) { 1 }
            watch("onboarding-speakers", db.transcribe().onboardingMultipleSpeakers(1_300_000_000L - 2 * 86_400_000L)) { if (it == null) 0 else 1 }
            watch("thing-state", db.things().observeState()) { if (it == null) 0 else 1 }
            if (mode == "before") {
                watch("voiceprint", db.transcribe().observeSegments()) { rows -> rows.count { it.status == "Done" }; rows.size }
                watch("queue", db.transcribe().observeSegments()) { rows -> rows.count { it.status == "Pending" }; rows.size }
                watch("failure", db.transcribe().observeSegments()) { rows -> rows.filter { it.status == "Failed" }; rows.size }
                watch("topic-progress", db.transcribe().observeSegments()) { rows -> rows.map { Triple(it.id,it.status,it.voiceprintProcessedAt) }; rows.size }
                watch("env-rows", db.envSound().observe()) { it.size }
                watch("env-events", db.envSound().events()) { it.size }
                watch("asr-usage", db.transcribe().observeUsage()) { it.size }
                watch("llm-usage", db.memory().observeUsage()) { it.size }
                watch("imports", db.imports().observe()) { rows -> rows.size + rows.sumOf { it.segments.size } }
                watch("topic-current", db.topics().observeAll()) { rows -> rows.size + rows.sumOf { it.spans.size + it.marks.size } }
                watch("topic-checkpoints", db.topics().observeAll()) { rows -> rows.size + rows.sumOf { it.spans.size + it.marks.size } }
                watch("voice-input", db.voiceInput().observed()) { it.size }
                watch("health-log", db.capture().events(0, Long.MAX_VALUE)) { it.size }
                watch("skills", db.skills().runs()) { it.size }
            } else {
                watch("voiceprint", db.transcribe().observeVoiceprintCounts()) { 1 }
                watch("queue", db.transcribe().observeQueueCounts()) { 1 }
                watch("failure", db.transcribe().observeFailedSegments()) { it.size }
                watch("topic-gate", db.topics().observePendingIds()) { it.size }
                watch("topic-late", db.topics().observeLateTranscriptIds()) { it.size }
                watch("topic-current", db.topics().observeCurrent()) { if (it == null) 0 else 1 + it.spans.size + it.marks.size }
                watch("topic-checkpoints", db.invalidationTracker.createFlow("topics", "topic_spans", "topic_marks")) { 0 }
                watch("asr-usage", db.transcribe().observeUsageTotals("day-99")) { 1 }
                watch("llm-usage", db.memory().observeTokens("day-99")) { 1 }
                watch("llm-change", db.memory().observeUsageChanges()) { 1 }
                watch("imports", db.imports().observeSummaryReady()) { it.size }
                watch("skills", db.skills().runs()) { it.size }
                // 没有界面/待整理专题时，这些订阅根本不会启动。
            }
            val all = counts.keys.toSet()
            settled(all)
            val segmentObservers = setOf("digest-pending", "onboarding-speakers") +
                if (mode == "before") setOf("voiceprint", "queue", "failure", "topic-progress", "imports")
                else setOf("voiceprint", "queue", "failure", "topic-late", "imports")
            val start = System.nanoTime()
            repeat(50) { i ->
                val id = 40001L + i
                val segment = SegmentEntity(id,1_300_000_000L+i*60000,1_300_030_000L+i*60000,"mic","new-$id",100)
                phaseStart = System.nanoTime()
                db.capture().segment(segment)
                settled(segmentObservers)
                phaseStart = System.nanoTime()
                db.envSound().insert(app.resound.envsound.EnvSoundRow(id,segment.startAt,segment.endAt,segment.path,"Keep",null,"Rain","","{}",false,false))
                if (mode == "before") settled(setOf("env-rows"))
                phaseStart = System.nanoTime()
                db.writeTransaction {
                    db.transcribe().complete(segment.copy(status="Done",completedAt=segment.endAt), listOf(TranscriptLineEntity(segmentId=id,startAt=segment.startAt,endAt=segment.endAt,text="合成原话",speakerTag=null)))
                    db.transcribe().attempt(AsrAttemptEntity(segmentId=id,at=segment.endAt,result="Done",errorType=null,elapsedMillis=1,sentMillis=30000,logId=null))
                    db.transcribe().addUsage("day-99",30000)
                }
                settled(segmentObservers + if (mode == "before") setOf("voice-input","asr-usage") else setOf("asr-usage"))
            }
            val wall = (System.nanoTime()-start)/1_000_000
            measuring = false
            if (mode == "after") listOf("env-rows", "env-events", "voice-input", "topic-progress", "health-log").forEach { counts[it] = Measure() }
            val report = buildString {
                appendLine("mode=$mode; segments=20000; env_sounds=30000; writes=50 insert+50 env+50 complete; wall_ms=$wall")
                appendLine("observer,calls,returned_rows,delivery_latency_ms")
                counts.toSortedMap().forEach { (name,m) -> appendLine("$name,${m.calls},${m.rows},${m.nanos/1_000_000.0}") }
                appendLine("SQL,calls,returned_rows,step_to_close_ms")
                sqlStats.toSortedMap().forEach { (sql,m) -> appendLine("$sql,${m.calls},${m.rows},${m.nanos/1_000_000.0}") }
            }
            File("build/observer182-$mode.txt").also { it.parentFile!!.mkdirs(); it.writeText(report) }
            println(report)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join(); db.close() }
    }
}
