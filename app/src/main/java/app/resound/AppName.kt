package app.resound

import android.content.Context

/** App 显示名（见 res/values/strings.xml 的 app_name）。代码里不要直接写名字。 */
val Context.appName: String get() = getString(R.string.app_name)
