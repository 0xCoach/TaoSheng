package app.resound.diary

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.resound.data.MemoryDao
import app.resound.digest.MemoryRepository
import app.resound.review.ReviewRepository
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toJavaLocalDate
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 保留原文件分享入口，查询与 Markdown 内容交给共用仓储。 */
class LocalDiaryRepository(
    private val context: Context, memory: MemoryRepository, reviews: ReviewRepository, dao: MemoryDao,
    zoneProvider: ZoneProvider, private val io: CoroutineDispatcher,
    cards: suspend (LocalDate) -> List<app.resound.skills.SkillCard> = { emptyList() },
    lineWindow: ((LocalDate) -> Pair<Long, Long>)? = null,
) : DiaryRepository {
    private val prefs = context.getSharedPreferences("diary", Context.MODE_PRIVATE)
    private val store = DiaryContentStore(memory, reviews, dao, { cards(it.toJavaLocalDate()) },
        lineWindow?.let { read -> { date -> read(date.toJavaLocalDate()) } }, zoneProvider, io,
        prefs.getBoolean("cloudSync", false), { prefs.edit().putBoolean("cloudSync", it).commit() },
        { AndroidDiaryTime(ZoneId.of(it)) }, app.resound.envsound.EnvSoundPresentation::line,
        app.resound.transcribe.VoiceInputMetadata::markdown)
    override val cloudSync get() = store.cloudSync
    @Synchronized override fun setCloudSync(enabled: Boolean) = store.setCloudSync(enabled)
    override suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean) =
        store.markdown(from.toKotlinLocalDate(), to.toKotlinLocalDate(), includeLines)
    internal suspend fun markdownWithContext(from: LocalDate, to: LocalDate, includeLines: Boolean, lineContext: Boolean) =
        store.markdownWithContext(from.toKotlinLocalDate(), to.toKotlinLocalDate(), includeLines, lineContext)
    override suspend fun exportFile(from: LocalDate, to: LocalDate, includeLines: Boolean): Uri = withContext(io) {
        val content = markdown(from, to, includeLines)
        val directory = File(context.cacheDir, "diary").apply { check(isDirectory || mkdirs()) }
        val range = if (from == to) "$from" else "${from}至${to}"
        val file = File(directory, "resound-diary_$range.md")
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

}

private class AndroidDiaryTime(zone: ZoneId) : DiaryTextTime {
    private val time = app.resound.OutputTime(zone)
    override fun moment(at: Long) = time.moment(at)
    override fun event(start: Long, end: Long) = time.event(start, end)
    override fun day(date: kotlinx.datetime.LocalDate) = date.toJavaLocalDate().format(DAY)
    companion object { private val DAY = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA) }
}
internal fun cardsMarkdown(cards: List<app.resound.skills.SkillCard>, zone: ZoneId = ZoneId.systemDefault()) =
    diaryCardsMarkdown(cards, AndroidDiaryTime(zone))
