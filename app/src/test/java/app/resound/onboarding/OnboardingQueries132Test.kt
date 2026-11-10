package app.resound.onboarding

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnboardingQueries132Test {
    @Test fun databaseSignalsIgnoreBlankUncertainAndCrossSegmentTagsButAcceptKnownOtherPerson() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java).build()
        try {
            withTimeout(15_000) {
                val dao = db.transcribe()
                suspend fun segment(id: Long, at: Long) = db.capture().segment(SegmentEntity(id, at, at + 100, "mic", "s$id", 10))
                suspend fun line(segment: Long, at: Long, text: String, tag: String?, person: Long? = null) =
                    dao.insertLines(listOf(TranscriptLineEntity(segmentId = segment, startAt = at, endAt = at + 10,
                        text = text, speakerTag = tag, speakerPersonId = person)))
                segment(1, 1000)
                line(1, 1, " \t\n\r　", "0")
                assertNull(dao.onboardingFirstTranscript().first())
                line(1, 1200, "句子", "uncertain:0")
                line(1, 1100, "更早一句", "uncertain:100")
                assertEquals(1100L, dao.onboardingFirstTranscript().first())
                assertNull(dao.onboardingMultipleSpeakers(0).first())
                line(1, 1300, "一句", "0")
                segment(2, 2000)
                line(2, 2000, "另一段", "1")
                assertNull(dao.onboardingMultipleSpeakers(0).first()) // 不跨录音段累计标签。
                line(1, 1400, "同段另一人", "1")
                assertEquals(1000L, dao.onboardingMultipleSpeakers(0).first())
                assertNull(dao.onboardingMultipleSpeakers(1500).first()) // 旧录音不作近期证据。
                line(2, 2100, "我", null, 0)
                assertNull(dao.onboardingMultipleSpeakers(1500).first())
                line(2, 2200, "认出的其他人", null, 7)
                assertEquals(2000L, dao.onboardingMultipleSpeakers(1500).first())
                dao.deleteLines(2)
                assertNull(dao.onboardingMultipleSpeakers(1500).first())
            }
        } finally { db.close() }
    }
}
