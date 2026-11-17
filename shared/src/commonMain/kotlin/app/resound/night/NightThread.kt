package app.resound.night
import app.resound.platform.ZoneHistory
import kotlinx.serialization.Serializable

@Serializable data class NightThreadEvent(val kind: String,val start: String,val end: String,val text: String,val aboveBackgroundDb: Int? = null)
@Serializable data class NightThread(val id: Long,val bedtime: String?,val onset: String?,val wake: String?,val sleepMillis: Long?,
    val awakeCount: Int,val awakeMillis: Long,val confidence: String?,val reasons: List<String>,val events: List<NightThreadEvent>,val markdown: String)
object NightThreads {
    fun build(detail: NightDetail,settings: NightSettings,zones: ZoneHistory): NightThread? {
        if(!settings.includeInPush)return null
        val summary=detail.summary;val times=summary.times
        if(summary.state !in setOf(NightState.Ready,NightState.Interrupted))return null
        val events=detail.events.filter { it.feedback!=Feedback.No && it.id !in detail.vetoed }.map { e->
            val title=when(e.kind) {
                NightEventKind.Snore->if(settings.sharedRoom)"卧室里的疑似鼾声" else "疑似鼾声"
                NightEventKind.Cough->if(settings.sharedRoom)"卧室里的疑似咳嗽/清嗓" else "疑似咳嗽/清嗓"
                NightEventKind.SleepTalk->(if(e.mine)"疑似梦话" else "夜间人声")+(e.text?.let { "：“$it”" } ?: " 1 段（转写中）")
                NightEventKind.Awake->"醒了"
                NightEventKind.Gap->e.text ?: "没有记录"
                NightEventKind.Playback->"手机在放声音"
                NightEventKind.Turn->"疑似翻身"
                NightEventKind.SnoreBreak->"鼾声中断"
            }
            NightThreadEvent(e.kind.name,zones.iso(e.start),zones.iso(e.end),title,e.aboveBackgroundDb)
        }
        val markdown=buildString {
            appendLine("## 昨晚\n本节时间为 ${zones.caption(summary.date)}")
            if(times!=null)appendLine("- 上床 ${zones.clock(times.bedtime)} · ${if(times.onsetEstimated)"约 " else ""}${zones.clock(times.onset)} 入睡 · ${zones.clock(times.wake)} 起床 · 睡了约 ${NightRules.duration(summary.sleepMillis ?: 0)}（醒了 ${summary.awakeCount} 次，共 ${NightRules.duration(summary.awakeMillis)}）")
            events.forEach {e->appendLine("- ${e.start.substring(11,16)}–${e.end.substring(11,16)} ${e.text}"+(e.aboveBackgroundDb?.let { "，最响比背景高 $it 分贝" } ?: ""))}
            detail.breakNotice?.let { appendLine("- $it") }
            detail.dreams.mapNotNull { it.text }.forEach { appendLine("- 梦：${it.replace('\n',' ')}") }
            appendLine("- 可信度：${summary.confidence?.title ?: "仅供参考"}（${summary.reasons.joinToString("；")}）")
        }.trimEnd()
        return NightThread(summary.id,times?.let {zones.iso(it.bedtime)},times?.let {zones.iso(it.onset)},times?.let {zones.iso(it.wake)},summary.sleepMillis,summary.awakeCount,summary.awakeMillis,summary.confidence?.name,summary.reasons,events,markdown)
    }
}
