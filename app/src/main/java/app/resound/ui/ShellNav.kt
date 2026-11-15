package app.resound.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 页面里要求切到另一个底栏页（如“就这个问问”跳到问问）。0 今天 · 1 问问 · 2 记忆 · 3 待办 · 4 设置 */
object ShellNav {
    val request = MutableStateFlow<Pair<Int, Long>?>(null)
    fun go(tab: Int) { request.value = tab to System.nanoTime() }

    /** 从别的页直接打开设置里的某个子页（如首页上手清单） */
    val settingsPage = MutableStateFlow<app.resound.ui.settings.SettingsPage?>(null)
    fun openSettings(page: app.resound.ui.settings.SettingsPage) { settingsPage.value = page; go(4) }

    /** 搜索结果点每日回顾：切到记忆页并打开那天的回顾（MainActivity 接收后清空） */
    val reviewRequest = MutableStateFlow<java.time.LocalDate?>(null)
    fun openReview(day: java.time.LocalDate) { reviewRequest.value = day }

    /** 通知栏“删最近 10 分钟”：回到今天页并打开聆听面板的删除确认 */
    val forgetRecent = MutableStateFlow(false)
}
