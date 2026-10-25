package app.resound

import android.content.Context

/** App 显示名（见 res/values/strings.xml 的 app_name）。代码里不要直接写名字。 */
val Context.appName: String get() = getString(R.string.app_name)

/** 拿不到 Context 的地方（推送标题、文件名、提示词）用它；ResoundApp 启动时从 app_name 填入。 */
var appDisplayName = ""
    internal set
