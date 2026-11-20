package app.resound.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 页面里要求切到另一个底栏页（如“就这个问问”跳到问问）。0 今天 · 1 助手 · 2 待办 · 3 记忆 · 4 问问 · 5 设置 */
object ShellNav {
    val request = MutableStateFlow<Pair<Int, Long>?>(null)
    fun go(tab: Int) { request.value = tab to System.nanoTime() }

    /** 通知等外部入口直达助手子页：值为 AssistantPage.ordinal，消费后置空 */
    val assistantPage = MutableStateFlow<Int?>(null)

    /** 从别的页直接打开设置里的某个子页（如首页上手清单） */
    val settingsPage = MutableStateFlow<app.resound.ui.settings.SettingsPage?>(null)
    fun openSettings(page: app.resound.ui.settings.SettingsPage) { settingsPage.value = page; go(5) }

    /** 通知栏“删最近 10 分钟”：回到今天页并打开聆听面板的删除确认 */
    val forgetRecent = MutableStateFlow(false)
}
