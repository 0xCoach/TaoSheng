package app.resound.skills

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import app.resound.skills.core.MeetingBackfill207

class SkillGate207Test {
    private val gate = SkillGate(2, 10, 20)
    private fun lines(speakers: (Int) -> String, meeting: (Int) -> Boolean = { false }) =
        List(30) { i -> SkillGateLine(i * 900_000L / 29, speakers(i), false, meeting(i)) }

    @Test fun meetingSceneFillsRequiredPeopleButKeepsTimeAndLineRequirements() {
        val input = lines({ "我" }, { true })
        assertNull(SkillGateRules.failure(gate, input))
        assertNotNull(SkillGateRules.failure(gate, input.take(3)))
        assertEquals(1, SkillGateRules.stats(gate.copy(minSpeakers = 1), input).speakers)
    }

    @Test fun maybeMeAndMeAreOnePerson() {
        val input = lines({ if (it % 2 == 0) "我" else "可能是我（未确认）" })
        assertEquals(1, SkillGateRules.stats(gate, input).speakers)
        assertNotNull(SkillGateRules.failure(gate, input))
    }

    @Test fun numberedUnknownLabelsOnlyAddOnePerson() {
        val input = lines({ if (it < 12) "说话人$it" else "我" })
        assertEquals(2, SkillGateRules.stats(gate, input).speakers)
        assertNull(SkillGateRules.failure(gate, input))
    }

    @Test fun unknownThresholdIsFiveSentencesOrTwentyPercent() {
        val five = List(100) { SkillGateLine(it * 60_000L, if (it < 5) "待确认" else "我", false) }
        assertEquals(2, SkillGateRules.stats(gate, five).speakers)
        assertEquals(1, SkillGateRules.stats(gate, five.drop(1)).speakers)
        val percent = List(20) { SkillGateLine(it * 60_000L, if (it < 4) "待确认" else "我", false) }
        assertEquals(2, SkillGateRules.stats(gate, percent).speakers)
    }

    @Test fun cardDoesNotBorrowMeetingOrVoiceInputOutsideCountedRange() {
        val input = lines({ "我" }, { it == 0 })
        assertNotNull(SkillGateRules.cardFailure(gate, input, 1, 900_000))
        assertEquals(0, SkillGateRules.stats(gate, input.map { it.copy(voiceInput = true) }).speakers)
    }

    @Test fun backfillOnlyRunsTwoOriginalDaysOnce() = runBlocking {
        val done = mutableSetOf<Long>()
        val calls = mutableListOf<Long>()
        suspend fun run() = MeetingBackfill207.run(100, done::contains, { done.add(it); true }) {
            calls += it.toEpochDays().toLong()
        }
        assertTrue(run())
        assertTrue(run())
        assertEquals(listOf(99L, 100L), calls)
    }

    @Test fun backfillCancellationKeepsCompletedDay() = runBlocking {
        val done = mutableSetOf<Long>()
        val calls = mutableListOf<Long>()
        try {
            MeetingBackfill207.run(100, done::contains, { done.add(it); true }) {
                val day = it.toEpochDays().toLong()
                calls += day
                if (day == 100L) throw CancellationException()
            }
            fail("应传播取消")
        } catch (_: CancellationException) { }
        assertEquals(setOf(99L), done)
        assertTrue(MeetingBackfill207.run(100, done::contains, { done.add(it); true }) {
            calls += it.toEpochDays().toLong()
        })
        assertEquals(listOf(99L, 100L, 100L), calls)
    }
}
