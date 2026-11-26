package app.resound.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import app.resound.capture.MicHolderGuess

/** 系统使用情况事件源；授权判断与 MicHolderGuess 同源，不复制第二份逻辑。 */
class AndroidScreenEventSource(private val context: Context) : ScreenEventSource {
    private val guess = MicHolderGuess(context)
    private val stats = context.getSystemService(UsageStatsManager::class.java)

    override fun permitted(): Boolean = guess.permitted()

    override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = buildList {
        val query = stats.queryEvents(from, until)
        val cursor = UsageEvents.Event()
        while (query.getNextEvent(cursor)) {
            val name = cursor.packageName ?: continue
            add(RawScreenEvent(cursor.timeStamp, name, cursor.className, cursor.eventType))
        }
    }

    override suspend fun appNames(pkgs: Set<String>): Map<String, String> = appLabels(context, pkgs)
}
