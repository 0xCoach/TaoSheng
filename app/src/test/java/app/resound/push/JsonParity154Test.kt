package app.resound.push

import android.app.Application
import app.resound.data.TranscriptLineEntity
import app.resound.things.*
import app.resound.platform.legacyText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class JsonParity154Test {
    @Test fun subscriptionJsonMatchesOriginalBytesIncludingOrderAndNull() {
        for (s in listOf(PushSubscription(), PushSubscription.FullArchive.copy(trigger = RecordTrigger.Schedule(listOf(1320, 60, 60), setOf(7, 1)), rangeDays = 4),
            PushSubscription.ForAgents.copy(trigger = RecordTrigger.Manual, backfillDays = null, fullCheckAt = null))) {
            val old = LegacySubscriptions154.json(s).toString()
            assertEquals(old, SubscriptionJson.encode(s).legacyText())
            assertEquals(LegacySubscriptions154.read(JSONObject(old)), Subscriptions.read(JSONObject(old)))
        }
    }
    @Test fun thingPromptMatchesOriginalBytesForEscapesContextAndNull() {
        val line = TranscriptLineEntity(id = 1, segmentId = 2, startAt = 1000, endAt = 2000,
            text = "钥匙 / 鞋柜\n\"备注\"\t😀 </script>", speakerTag = null, speakerPersonId = 0)
        val latest = ThingRecord(1, 1, "钥匙", ThingAction.Lent, null, "小明", ThingOwner.Mine, 1000, 1, "原话", RecordStatus.Accepted, ThingOrigin.Live, false, null)
        val known = listOf(Thing(1, "钥匙", listOf("家/钥匙"), ThingOwner.Mine, latest, null, null, 1, false))
        assertEquals(LegacyThingPrompt154.input(listOf(line), listOf(line), known), ThingPrompt.input(listOf(line), listOf(line), known))
        assertEquals(LegacyThingPrompt154.input(emptyList(), emptyList(), emptyList(), true), ThingPrompt.input(emptyList(), emptyList(), emptyList(), true))
    }
    @Test fun folderFrontMatterMatchesOriginalQuoting() {
        val type = "每日/记录"; val date = "2026-11-12"; val content = "换行\n\"内容\""; val body = "  正文\n"
        val old = "---\n类型: ${JSONObject.quote(type)}\n日期: ${JSONObject.quote(date)}\n内容: ${JSONObject.quote(content)}\n更新: \"${MemoryFolder.UPDATED}\"\n来源: 自动生成，请勿手动修改（改了会被覆盖）\n条数: 1\n---\n# 标题\n\n${body.trim()}\n"
        assertEquals(old, MemoryFolder.card(type, date, content, "标题", 1, body))
    }
    @Test fun modelReplyKeepsLegacyNumericAndLenientSyntax() {
        val line = TranscriptLineEntity(id = 1, segmentId = 2, startAt = 1000, endAt = 2000, text = "原话", speakerTag = null)
        val reply = """{items:[{name:'钥匙',action:'Placed',owner:'Mine',place:'柜子',lineId:1.9,knownId:null,confident:'TRUE',explicit:false,aliases:['家/钥匙']}]}"""
        assertEquals(LegacyThingPrompt154.parse(reply, listOf(line), emptySet(), 5), ThingPrompt.parse(reply, listOf(line), emptySet(), 5))
    }

}
