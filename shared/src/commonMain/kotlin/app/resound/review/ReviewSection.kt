package app.resound.review

/** 回顾里的一个栏目：一句话 / 要紧的 / 发生了什么 / 待办 / 值得留意 / 灵感 / 心力。空栏目不出现 */
data class ReviewSection(
    val title: String,
    /** 每条一句话 */
    val lines: List<String>,
    /** 每条对应的事件 id（和 lines 一一对应，没有出处为 null），界面点了能跳到那件事 */
    val eventIds: List<Long?>,
)
