package app.resound.decision

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.MemoryItemEntity
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import app.resound.platform.NowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DecisionEvidenceGateTest {
    private lateinit var db: CaptureDatabase
    private lateinit var repo: DecisionRepository
    private val now = 2_000_000_000_000L

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .allowMainThreadQueries().build()
        repo = DecisionRepository(db.decisions(), db.memory(), db.revisions(), Dispatchers.Unconfined, NowMillis { now })
    }
    @After fun close() = db.close()

    private fun item(sourceText: String? = null, lineId: Long? = null) = MemoryItemEntity(
        eventId = null, category = "决定", kind = null, content = "用方案A做", dueText = null,
        dueAt = null, who = null, lineId = lineId, status = "Accepted", createdAt = now, sourceText = sourceText)
    private suspend fun line(id: Long, text: String): Long {
        db.capture().segment(SegmentEntity(1, now, now + 1000, "mic", "f.ogg", 15, "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id = id, segmentId = 1, startAt = now, endAt = now + 1, text = text, speakerTag = "0")))
        return id
    }

    @Test fun tentativePhrasingNeverBecomesSureDecision() = runBlocking {
        val id = repo.importFromItem(item(sourceText = "考虑一下用方案A吧"))!!
        assertEquals(DecisionConfidence.Tentative.name, db.decisions().decision(id)!!.confidence)
    }

    @Test fun missingEvidenceIsAlwaysTentative() = runBlocking {
        val id = repo.importFromItem(item(sourceText = null))!!
        val row = db.decisions().decision(id)!!
        assertEquals(DecisionConfidence.Tentative.name, row.confidence)
        assertNull(row.sourceText)
    }

    @Test fun clearDecisionWordsKeepSure() = runBlocking {
        val id = repo.importFromItem(item(sourceText = "我们决定就用方案A，不做别的了"))!!
        assertEquals(DecisionConfidence.Sure.name, db.decisions().decision(id)!!.confidence)
    }

    @Test fun evidenceFallsBackToTranscriptLine() = runBlocking {
        val lineId = line(5, "就这么定，就用方案A")
        val id = repo.importFromItem(item(sourceText = null, lineId = lineId))!!
        val row = db.decisions().decision(id)!!
        assertEquals(DecisionConfidence.Sure.name, row.confidence)
        assertEquals("就这么定，就用方案A", row.sourceText)
    }

    @Test fun neutralStatementStaysLikely() = runBlocking {
        val id = repo.importFromItem(item(sourceText = "下周三发版本"))!!
        assertEquals(DecisionConfidence.Likely.name, db.decisions().decision(id)!!.confidence)
    }

    @Test fun importInheritsProjectLinkAndLeavesUnlinkedNull() = runBlocking {
        val linked = DecisionRepository(db.decisions(), db.memory(), db.revisions(),
            Dispatchers.Unconfined, NowMillis { now }, db.projects())
        val pid = db.projects().insert(app.resound.projects.ProjectRow(name = "项目X", createdAt = now, updatedAt = now))
        db.projects().insertLink(app.resound.projects.ProjectLinkRow(projectId = pid,
            refKind = app.resound.projects.ProjectRefKind.Item.name, refId = 7, createdAt = now))
        val decided = item(sourceText = "我们决定就用方案A，不做别的了")
        val linkedId = linked.importFromItem(decided.copy(id = 7))!!
        val plainId = linked.importFromItem(decided.copy(id = 8))!!
        assertEquals(pid, db.decisions().decision(linkedId)!!.projectId)
        assertNull(db.decisions().decision(plainId)!!.projectId)
    }
}
