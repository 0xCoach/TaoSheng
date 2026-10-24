package app.resound.ui.preview

import app.resound.digest.DigestSettings
import app.resound.digest.DigestState
import app.resound.digest.ItemStatus
import app.resound.digest.LlmPreset
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** 整理功能接上前，界面开发用的假数据（内容取自 DeepSeek 实测输出）。 */
class FakeMemoryRepository : MemoryRepository {
    private val today = LocalDate.now()
    private fun at(h: Int, m: Int, s: Int = 0, date: LocalDate = today) =
        date.atTime(h, m, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val items = MutableStateFlow(
        listOf(
            MemoryItem(1, 1, "待办", "我要做", "把体检报告拍照发给李医生", "${today}", at(0, 0), "我", ItemStatus.Pending, 2),
            MemoryItem(2, 2, "待办", "别人答应我", "对方答应周二下班前把评审材料发给我", "${today.plusDays(4)} 18:00", at(18, 0, date = today.plusDays(4)), "对方", ItemStatus.Pending, 6),
            MemoryItem(3, 2, "约定", null, "周三下午两点在三楼会议室开评审会", "${today.plusDays(5)} 14:00", at(14, 0, date = today.plusDays(5)), null, ItemStatus.Pending, 7),
            MemoryItem(4, 2, "决定", null, "预算按十二万上报、不再追加，因为加了领导不会批", null, null, null, ItemStatus.Pending, 8),
            MemoryItem(5, 3, "关键信息", null, "那家酸菜鱼店的订位电话是 86234567", null, null, null, ItemStatus.Pending, 11),
            MemoryItem(6, 5, "灵感", null, "回响的每日回顾可以加一个“今天说话最多的人”", null, null, "我", ItemStatus.Pending, 15),
            MemoryItem(7, 5, "约定", null, "跟老王的饭局改到下周四晚上七点", "${today.plusDays(6)} 19:00", at(19, 0, date = today.plusDays(6)), "老王", ItemStatus.Pending, 16),
            MemoryItem(8, 5, "待办", "我要做", "微信告诉老王：饭局改到下周四晚上七点", null, null, "我", ItemStatus.Pending, 16),
            MemoryItem(9, 6, "知识", null, "咖啡最好在起床后一个半小时再喝，太早喝会影响皮质醇", null, null, null, ItemStatus.Pending, 17),
        ),
    )

    private fun baseEvents(date: LocalDate) = listOf(
        MemoryEvent(6, at(16, 5, date = date), at(16, 5, 20, date), "闲聊", "聊咖啡该什么时候喝",
            "有人说咖啡最好在起床后一个半小时再喝，太早喝会影响皮质醇，另一人说自己一直是起床就喝。",
            listOf("起床后一个半小时再喝咖啡更好"), emptyList(), null, "愉快", 2, false, emptyList()),
        MemoryEvent(5, at(15, 40, date = date), at(15, 40, 30, date), "独白", "想到产品点子并改约饭局",
            "想到给回响的每日回顾加“今天说话最多的人”，并决定把跟老王的饭局改到下周四晚上七点，准备用微信告诉他。",
            listOf("每日回顾可加“今天说话最多的人”", "饭局改到下周四晚上七点"), listOf("老王"), null, "平静", 2, true, emptyList()),
        MemoryEvent(4, at(14, 20, date = date), at(14, 20, 20, date), "媒体", "外放的理财节目",
            "外放的节目在讲如何在三十天内学会理财，提到把每月收入分成四份。", emptyList(), emptyList(), null, "平静", 1, false, emptyList()),
        MemoryEvent(3, at(12, 5, date = date), at(12, 6, date = date), "闲聊", "午饭聊酸菜鱼",
            "两人吃饭时聊到这家酸菜鱼比上次那家好吃，提议下次带爸妈来，并记下订位电话。",
            listOf("这家酸菜鱼比上次好吃", "周末要提前订位"), listOf("爸妈"), null, "愉快", 3, false, emptyList()),
        MemoryEvent(2, at(10, 30, date = date), at(10, 31, 10, date), "会议", "确认测试机与评审安排",
            "两人确认测试手机明天上午送到，评审定在周三下午两点三楼会议室，对方周二下班前发材料，预算按十二万报。",
            listOf("测试手机明天上午送到", "评审：周三 14:00 三楼会议室", "对方周二下班前发材料", "预算按十二万上报"),
            emptyList(), "三楼会议室", "平静", 2, true, emptyList()),
        MemoryEvent(1, at(9, 12, date = date), at(9, 12, 20, date), "独白", "安排测试报告与体检报告",
            "打算先看回响的测试报告、下午跟老王过一遍，并提醒自己把体检报告拍照发给李医生。",
            listOf("先看回响测试报告", "下午跟老王过一遍", "体检报告发李医生"), listOf("老王", "李医生"), null, "平静", 2, true, emptyList()),
    )

    override fun events(date: LocalDate): Flow<List<MemoryEvent>> = items.map { all ->
        if (date != today) emptyList() else baseEvents(date).map { e -> e.copy(items = all.filter { it.eventId == e.id }) }
    }

    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = flowOf(
        listOf(
            TranscriptLine(1, at(10, 30, 15), at(10, 30, 18), "那个测试手机到了没有？", "0"),
            TranscriptLine(2, at(10, 30, 19), at(10, 30, 24), "还没到，物流显示明天上午送。", "1"),
            TranscriptLine(3, at(10, 30, 25), at(10, 30, 30), "行，那周三的评审你能准备好吗？", "0"),
            TranscriptLine(4, at(10, 30, 31), at(10, 30, 38), "可以，我周二下班前把材料发你。", "1"),
        ),
    )

    override fun pendingItems(): Flow<List<MemoryItem>> =
        items.map { all -> all.filter { it.status == ItemStatus.Pending && it.category in setOf("待办", "约定", "备忘") } }

    override fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>> =
        items.map { all -> all.filter { it.category in categories && (statuses == null || it.status in statuses) } }

    override fun setItemStatus(id: Long, status: ItemStatus) {
        items.value = items.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    override fun setImportant(eventId: Long, important: Boolean) = Unit
    override fun deleteEvent(eventId: Long) = Unit

    override val state: StateFlow<DigestState> =
        MutableStateFlow(DigestState(12, false, System.currentTimeMillis() - 25 * 60_000L, null, 18_400))

    override fun runNow() = Unit
}

class FakeDigestSettings : DigestSettings {
    override val presets = listOf(
        LlmPreset("deepseek", "DeepSeek Flash", "https://api.deepseek.com", "deepseek-flash"),
        LlmPreset("doubao", "豆包 Seed 2.1 Lite", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-lite-260915"),
    )
    private val _preset = MutableStateFlow("deepseek")
    override val presetId: StateFlow<String> = _preset
    override fun setPreset(id: String) { _preset.value = id }
    private val _hasKey = MutableStateFlow(true)
    override val hasKey: StateFlow<Boolean> = _hasKey
    override fun saveKey(key: String) { _hasKey.value = true }
    override fun clearKey() { _hasKey.value = false }
    override suspend fun test(): Result<String> { delay(800); return Result.success("可用（DeepSeek Flash）：你好呀") }
}
