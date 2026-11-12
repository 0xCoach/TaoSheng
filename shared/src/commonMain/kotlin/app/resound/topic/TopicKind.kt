package app.resound.topic

/** 卡 135：界面仅通过 AppGraph.topics 使用。时间均为 epoch 毫秒，时段为 [startAt, endAt)。 */
enum class TopicKind(val title: String) {
    Meeting("会议"), Training("培训"), Call("通话 / 访谈"), Script("口播稿"), Normal("普通")
}
