package app.resound.importer

/** 导入录音的类型：决定怎么区分说话人、生成什么纪要 */
enum class ImportKind(val title: String, val hint: String) {
    Meeting("会议", "多人讨论，生成会议纪要"),
    Training("培训", "一人主讲，生成培训笔记，不区分说话人"),
    Call("通话 / 访谈", "两人一来一回，生成通话记录"),
    Normal("普通", "随手录的、自己口述的，照常整理"),
}

/** 预选依据（界面小字说明为什么这么选） */
enum class KindReason { CallFolder, DualChannel, LastUsed, Default }

data class KindSuggestion(val kind: ImportKind, val reason: KindReason)
