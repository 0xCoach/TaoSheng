package app.resound.transcribe

import kotlinx.coroutines.flow.StateFlow

/** 热词：让转写认准你的称呼、常见人名和专有名词。界面由 Claude 做。 */
interface HotwordSettings {
    /** 手动加的词（去重、按添加顺序） */
    val words: StateFlow<List<String>>

    fun setWords(words: List<String>)

    /** 自动带上的词：声纹人物的名字等（只读，界面单独展示） */
    val automatic: StateFlow<List<String>>
}
