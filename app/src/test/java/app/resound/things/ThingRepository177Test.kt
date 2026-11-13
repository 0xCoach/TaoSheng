package app.resound.things

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ThingRepository177Test {
    @Test fun everyMethodAndFlowMatchesRecordedBytes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 100_000L
        val repo = RoomThingRepository(db, scope, parseDraft = { text ->
            if (text == "离线") error("synthetic offline")
            ThingDrafts(listOf(ThingDraft(text, ThingAction.Placed, "书柜", null)), true)
        }, now = { now }, problem = { error(it) })
        val out = StringBuilder()
        fun record(label: String, value: Any?) { out.appendLine("$label=$value") }
        fun proposal(name: String, action: String = "Placed", place: String? = "书柜") = ThingProposal(
            ThingRecordRow(name = name, action = action, place = place, who = null, owner = "Mine", at = now,
                sourceLineId = null, sourceText = "合成原话", status = "Pending", origin = "Live", createdAt = now), true)
        try {
            record("auto.initial", repo.autoDetect.value)
            repo.setAutoDetect(false); repo.flush(); record("auto.off", withTimeout(5000) { repo.autoDetect.first { !it } })
            repo.setAutoDetect(true); repo.flush(); record("auto.on", withTimeout(5000) { repo.autoDetect.first { it } })
            record("draft", repo.draft("钥匙")); record("draft.fallback", repo.draft("离线"))
            repo.add(listOf(ThingDraft("钥匙/\"备用\"", ThingAction.Placed, "书柜%_\\", null),
                ThingDraft("书", ThingAction.Lent, null, "同事")), ThingOrigin.Typed, "合成输入")
            repo.flush(); val keys = repo.things().first().single { it.name.startsWith("钥匙") }.id
            val book = repo.things().first().single { it.name == "书" }.id
            record("things", repo.things().first()); record("thing", repo.thing(keys).first()); record("missing", repo.thing(999).first())
            record("search.empty", repo.search("").first()); record("search.literal", repo.search("%_\\").first())
            record("history", repo.history(keys).first()); record("pending.empty", repo.pending().first())
            now += 1000
            val pending = db.writeTransaction { repo.propose(proposal("新钥匙")) }!!; record("propose", pending)
            record("pending", repo.pending().first())
            repo.accept(pending.id, ThingEdit("新钥匙", ThingAction.Placed, "抽屉", null, ThingOwner.Mine), true); repo.flush()
            record("accepted", repo.things().first())
            val ignored = db.writeTransaction { repo.propose(proposal("应忽略")) }!!
            repo.ignore(ignored.id); repo.flush(); record("ignored", db.things().record(ignored.id))
            val bulk = db.writeTransaction { repo.propose(proposal("批量")) }!!
            repo.setAll(listOf(bulk.id, bulk.id), RecordStatus.Accepted); repo.flush(); record("bulk", repo.things().first())
            repo.rename(keys, "  主钥匙/\"\\\n  "); repo.setOwner(keys, ThingOwner.Family); repo.flush()
            record("renamed", repo.thing(keys).first()); record("aliases.bytes", db.things().thing(keys)!!.aliasesJson)
            val firstRecord = repo.history(keys).first().single().id
            repo.editRecord(firstRecord, ThingEdit("主钥匙/\"\\", ThingAction.Placed, "柜上", null, ThingOwner.Family)); repo.flush()
            record("edited", repo.history(keys).first())
            repo.markReturned(book, "  书架  "); repo.flush(); record("returned", repo.history(book).first())
            repo.deleteRecord(firstRecord); repo.flush(); record("record.deleted", repo.history(keys).first())
            repo.deleteThing(book); repo.flush(); record("thing.deleted", repo.thing(book).first())
            record("final", repo.things().first()); record("state", db.things().state())
            val file = File("src/test/resources/repo177/RoomThingRepository/outputs.txt")
            assertEquals(file.readText(), out.toString())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
