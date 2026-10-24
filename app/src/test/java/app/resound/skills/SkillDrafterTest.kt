package app.resound.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SkillDrafterTest {
    @Test fun askReturnsQuestionWithOptions() {
        val step = SkillDrafter.step("""{"ask":"想什么时候看？","options":["每晚","每次发生时"]}""", "my_x", mustFinish = false)
        assertEquals(DraftStep.Ask("想什么时候看？", listOf("每晚", "每次发生时")), step)
    }

    @Test fun skillIsSanitized() {
        val step = SkillDrafter.step("""```json
{"skill":{"name":"运动记录","summary":"记运动","icon":"rocket","trigger":{"type":"Daily","minutes":5000,"weekdays":[7,9]},
"scope":{"range":"Week","speakers":"MeOnly","includeLines":false},"maxCardsPerDay":99,
"fields":[{"key":"Count Times","label":"次数","type":"Number","hint":"","required":true},{"key":"count_times","label":"项目","type":"Weird","hint":""}],
"prompt":"统计运动"}}
```""", "my_x", mustFinish = false) as DraftStep.Done
        val s = step.draft.skill
        assertEquals("star", s.icon)
        assertEquals(SkillTrigger.Daily(1439, setOf(7)), s.trigger)
        assertEquals(20, s.maxCardsPerDay)
        assertEquals(listOf("count_times", "f2"), s.fields.map { it.key })
        assertEquals(FieldType.Bullets, s.fields[1].type)
    }

    @Test fun askAfterLimitIsRejected() {
        assertTrue(runCatching { SkillDrafter.step("""{"ask":"还有吗？"}""", "my_x", mustFinish = true) }.isFailure)
    }
}
