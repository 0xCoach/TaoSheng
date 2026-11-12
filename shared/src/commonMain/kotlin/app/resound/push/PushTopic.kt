package app.resound.push

/** 推什么 */
enum class PushTopic(val title: String) {
    DailyReview("每日回顾"), TodoReminder("待办提醒"), NewEvent("新整理的事"), SkillCard("技能卡片"),

    /** 每晚生成回顾后，推当天完整的记忆文档（Markdown + 结构化数据），给用户自己的 Agent 再加工 */
    DailyMemory("每日记忆文档"),

    /** 每天定时推当天足迹：在家/外出时长、停留地点；Webhook 另附结构化数据和定位点 */
    Footprint("每日足迹"),
}
