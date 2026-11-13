# A 段调用清单（改动前）

基线：24198117d9001fa1c450b87d667b3ac5cc207b8a

逐一查找约定声明的全部顶层类型；保留实现、生产调用、界面、预览假实现及测试的文件与行号。成员调用另按编译器和接收者核对；同包及通配导入调用也包含在下面。

## digest/MemoryContract.kt

类型：MemoryRepository, DigestSettings

```text
app/src/main/java/app/resound/push/DailyMemory.kt:39:internal class DailyMemorySource(context: Context, memory: MemoryRepository, private val reviews: ReviewRepository,
app/src/main/java/app/resound/push/DailyMemory.kt:51:    private val windowMemory = object : MemoryRepository by memory {
app/src/test/java/app/resound/ui/BatchDeleteInvestigationTest.kt:44:        val memory = object : MemoryRepository by FakeMemoryRepository() {
app/src/main/java/app/resound/AppGraph.kt:6:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/AppGraph.kt:7:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/AppGraph.kt:167:    lateinit var memory: MemoryRepository
app/src/main/java/app/resound/AppGraph.kt:168:    lateinit var digestSettings: DigestSettings
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:42:) : MemoryRepository {
app/src/main/java/app/resound/digest/AndroidDigestSettings.kt:12:) : DigestSettings {
app/src/main/java/app/resound/health/HealthAndroid.kt:27:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/health/HealthAndroid.kt:56:    private val digest: DigestSettings,
app/src/main/java/app/resound/digest/MemoryContract.kt:13:interface MemoryRepository {
app/src/main/java/app/resound/digest/MemoryContract.kt:116:interface DigestSettings {
app/src/main/java/app/resound/diary/LocalDiaryRepository.kt:10:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/diary/LocalDiaryRepository.kt:26:    private val memory: MemoryRepository,
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:48:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:88:fun OnboardingWizard(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:367:private fun ColumnScope.TryIt(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, since: Long, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:65:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:105:private class Tab(val name: String, val grouping: Grouping, val source: (MemoryRepository) -> Flow<List<MemoryItem>>)
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:120:fun TodoScreen(memory: MemoryRepository, bottomPadding: Dp) {
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:290:    memory: MemoryRepository,
app/src/main/java/app/resound/ui/items/TodoSheets.kt:40:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/items/TodoSheets.kt:70:internal fun EditItemSheet(item: MemoryItem, memory: MemoryRepository, strongOn: Boolean = false, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/items/TodoSheets.kt:171:internal fun ReminderSheet(memory: MemoryRepository, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/items/DayDiary.kt:64:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/items/DayDiary.kt:90:internal fun DatePickerStrip(memory: MemoryRepository, date: LocalDate, onPick: (LocalDate) -> Unit) {
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:41:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:77:    digestSettings: DigestSettings,
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:3:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:9:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:21:class FakeMemoryRepository : MemoryRepository {
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:93:class FakeDigestSettings : DigestSettings {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:101:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/ui/today/TodayScreen.kt:106:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/today/TodayScreen.kt:143:    memory: MemoryRepository,
app/src/main/java/app/resound/ui/today/TodayScreen.kt:144:    settings: DigestSettings,
app/src/main/java/app/resound/ui/today/TodayScreen.kt:502:fun ModelCard(settings: DigestSettings, onSaved: () -> Unit) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:707:private fun EmptyTimeline(memory: MemoryRepository) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:893:internal fun EventSheet(event: MemoryEvent, memory: MemoryRepository, onClose: () -> Unit) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1073:private fun SourceAudioButton(source: app.resound.transcribe.TranscriptLine, lines: List<app.resound.transcribe.TranscriptLine>, item: MemoryItem, memory: MemoryRepository, lead: Long = 5_000L) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1111:internal fun ItemSheet(opened: MemoryItem, memory: MemoryRepository, onOpenEvent: (() -> Unit)?, onClose: () -> Unit) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1422:internal fun rememberDeleteEvent(memory: MemoryRepository): (MemoryEvent) -> Unit {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1430:private suspend fun deleteEventWithFeedback(memory: MemoryRepository, id: Long, context: android.content.Context): Boolean {
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:48:import app.resound.digest.DigestSettings
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:75:    digestSettings: DigestSettings,
app/src/main/java/app/resound/ui/today/OverviewCard.kt:50:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/today/OverviewCard.kt:112:    memory: MemoryRepository,
app/src/main/java/app/resound/ui/today/OverviewCard.kt:167:    capture: CaptureController, memory: MemoryRepository, transcripts: TranscriptRepository, hasKey: Boolean,
app/src/main/java/app/resound/ui/today/CopyContent.kt:19:import app.resound.digest.MemoryRepository
app/src/main/java/app/resound/ui/today/CopyContent.kt:105:internal fun CopySheet(events: List<MemoryEvent>, memory: MemoryRepository, onDone: (String) -> Unit, onDismiss: () -> Unit) {
```

## digest/RhythmContract.kt

类型：RhythmBaseline, RhythmStats

```text
app/src/main/java/app/resound/digest/RhythmContract.kt:12:data class RhythmBaseline(
app/src/main/java/app/resound/digest/RhythmContract.kt:20:interface RhythmStats {
app/src/main/java/app/resound/digest/RhythmContract.kt:22:    fun baseline(date: LocalDate): Flow<RhythmBaseline?>
app/src/main/java/app/resound/digest/RoomRhythmStats.kt:19:) : RhythmStats {
app/src/main/java/app/resound/digest/RoomRhythmStats.kt:20:    override fun baseline(date: LocalDate): Flow<RhythmBaseline?> {
app/src/main/java/app/resound/digest/RoomRhythmStats.kt:32:    internal fun calculate(snapshot: RhythmSnapshot): RhythmBaseline? {
app/src/main/java/app/resound/digest/RoomRhythmStats.kt:55:            RhythmBaseline(1,
app/src/main/java/app/resound/digest/RoomRhythmStats.kt:59:        return RhythmBaseline(days.size, median(days.map { it.speechMinutes }), median(days.map { it.events }),
app/src/main/java/app/resound/ui/today/RhythmViews.kt:45:import app.resound.digest.RhythmBaseline
app/src/test/java/app/resound/digest/Rhythm064Test.kt:54:        assertEquals(RhythmBaseline(4, 3, 3, 2, 3), stats.baseline(date).first())
app/src/test/java/app/resound/digest/Rhythm064Test.kt:57:        assertEquals(RhythmBaseline(3, 2, 2, 2, 2), stats.baseline(date).first())
app/src/test/java/app/resound/digest/Rhythm064Test.kt:65:            val values = Channel<RhythmBaseline?>(Channel.UNLIMITED)
app/src/test/java/app/resound/digest/Rhythm064Test.kt:70:                assertEquals(RhythmBaseline(3, 1, 0, 0, 0), withTimeout(5000) { values.receive() })
app/src/test/java/app/resound/digest/Rhythm064Test.kt:72:                assertEquals(RhythmBaseline(4, 1, 0, 0, 0), withTimeout(5000) { values.receive() })
app/src/main/java/app/resound/AppGraph.kt:54:    lateinit var rhythm: app.resound.digest.RhythmStats
app/src/androidTest/java/app/resound/digest/DeleteLatency099DeviceTest.kt:93:            var baseline: RhythmBaseline? = null
```

## capture/QuietScheduleContract.kt

类型：QuietRule, QuietWindow, QuietSchedule

```text
app/src/main/java/app/resound/AppGraph.kt:95:    lateinit var quietSchedule: app.resound.capture.QuietSchedule
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:12:data class QuietRule(
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:24:data class QuietWindow(
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:25:    val rule: QuietRule,
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:30:interface QuietSchedule {
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:32:    val rules: StateFlow<List<QuietRule>>
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:35:    val active: StateFlow<QuietWindow?>
app/src/main/java/app/resound/capture/QuietScheduleContract.kt:38:    fun save(rule: QuietRule)
app/src/main/java/app/resound/health/HealthAndroid.kt:25:import app.resound.capture.QuietSchedule
app/src/main/java/app/resound/health/HealthAndroid.kt:53:    private val quiet: QuietSchedule,
app/src/main/java/app/resound/capture/QuietRules.kt:9:    private data class Span(val start: Long, val end: Long, val rule: QuietRule)
app/src/main/java/app/resound/capture/QuietRules.kt:10:    private fun spans(rules: List<QuietRule>, first: LocalDate, last: LocalDate, zone: ZoneId): List<Span> {
app/src/main/java/app/resound/capture/QuietRules.kt:31:    private fun nearby(rules: List<QuietRule>, now: Long, zone: ZoneId): List<Span> {
app/src/main/java/app/resound/capture/QuietRules.kt:39:    fun windowAt(rules: List<QuietRule>, now: Long, zone: ZoneId): QuietWindow? =
app/src/main/java/app/resound/capture/QuietRules.kt:40:        nearby(rules, now, zone).firstOrNull { now >= it.start && now < it.end }?.let { QuietWindow(it.rule, it.end) }
app/src/main/java/app/resound/capture/QuietRules.kt:42:    fun nextChange(rules: List<QuietRule>, now: Long, zone: ZoneId): Long? =
app/src/main/java/app/resound/capture/QuietRules.kt:48:    internal fun recordingGaps(rules: List<QuietRule>, from: Long, until: Long, zone: ZoneId, manualUntil: Long, skipUntil: Long): List<Pair<Long, Long>> {
app/src/main/java/app/resound/capture/QuietRules.kt:67:internal fun capturePausePlan(now: Long, manualUntil: Long, quiet: QuietWindow?): CapturePausePlan? {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:22:) : QuietSchedule {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:25:            check(prefs.edit().putString("quietRules", encode(listOf(QuietRule(QuietSchedule.SLEEP_ID, "睡眠", 23 * 60, 7 * 60, enabled = false)))).commit())
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:32:    private val currentActive = MutableStateFlow<QuietWindow?>(null)
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:36:    @Synchronized override fun save(rule: QuietRule) {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:45:    private fun persist(list: List<QuietRule>) {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:50:    @Synchronized fun refresh(): QuietWindow? {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:65:        private fun encode(rules: List<QuietRule>) = JSONArray().apply {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:70:        private fun decode(json: String): List<QuietRule> = runCatching {
app/src/main/java/app/resound/capture/AndroidQuietSchedule.kt:74:                QuietRule(row.getString("id"), row.getString("name"), row.getInt("startMinute"), row.getInt("endMinute"),
app/src/test/java/app/resound/capture/QuietSchedule056Test.kt:21:    private val sleep = QuietRule("sleep", "睡眠", 23 * 60, 7 * 60)
app/src/test/java/app/resound/capture/QuietSchedule056Test.kt:36:        val rules = listOf(sleep, QuietRule("morning", "早间", 7 * 60, 9 * 60), QuietRule("overlap", "重叠", 8 * 60, 10 * 60))
app/src/main/java/app/resound/ui/verify/QuietSection.kt:35:import app.resound.capture.QuietRule
app/src/main/java/app/resound/ui/verify/QuietSection.kt:36:import app.resound.capture.QuietSchedule
app/src/main/java/app/resound/ui/verify/QuietSection.kt:46:internal fun QuietSection(schedule: QuietSchedule) {
app/src/main/java/app/resound/ui/verify/QuietSection.kt:50:    var editing by remember { mutableStateOf<QuietRule?>(null) }
app/src/main/java/app/resound/ui/verify/QuietSection.kt:79:private fun QuietRow(rule: QuietRule, now: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
app/src/main/java/app/resound/ui/verify/QuietSection.kt:102:private fun QuietRuleSheet(rule: QuietRule?, isNew: Boolean, onSave: (QuietRule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/verify/QuietSection.kt:156:            onSave(QuietRule(rule?.id ?: "q" + System.currentTimeMillis(), name.trim().ifBlank { "停录" }, start, end, days, rule?.enabled ?: true))
app/src/main/java/app/resound/ui/verify/QuietSection.kt:170:    QuietRule("", "睡眠", 23 * 60, 7 * 60),
app/src/main/java/app/resound/ui/verify/QuietSection.kt:171:    QuietRule("", "午休", 12 * 60 + 30, 14 * 60, WORKDAYS),
app/src/main/java/app/resound/ui/verify/QuietSection.kt:172:    QuietRule("", "下班后", 18 * 60, 9 * 60, WORKDAYS),
app/src/main/java/app/resound/ui/verify/QuietSection.kt:173:    QuietRule("", "周末", 0, 0, WEEKEND),
app/src/main/java/app/resound/ui/today/RhythmViews.kt:43:import app.resound.capture.QuietRule
app/src/main/java/app/resound/ui/today/RhythmViews.kt:119:private fun quietHours(rules: List<QuietRule>, date: LocalDate): Map<Int, String> = buildMap {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:208:private fun HourBars(r: DayRhythm, date: LocalDate, rules: List<QuietRule>, onHour: (Int) -> Unit, onEnv: (app.resound.envsound.EnvSpan) -> Unit) {
```

## importer/ImportKindContract.kt

类型：ImportRecordStatus, ImportRecord, ImportKinds

```text
app/src/main/java/app/resound/importer/AndroidImportKinds.kt:26:) : ImportKinds {
app/src/main/java/app/resound/importer/AndroidImportKinds.kt:59:    override fun records(date: LocalDate): Flow<List<ImportRecord>> = db.imports().day(
app/src/main/java/app/resound/importer/AndroidImportKinds.kt:62:    override fun record(id: Long): Flow<ImportRecord?> = db.imports().observe(id).map { it?.record() }
app/src/main/java/app/resound/importer/ImportRecordPresentation.kt:3:fun ImportRecordWithSegments.record() = ImportRecord(row.id, row.fileName, ImportKind.valueOf(row.kind), row.startAt, row.endAt,
app/src/main/java/app/resound/importer/ImportRecordPresentation.kt:4:        ImportRecordStatus.valueOf(row.status), row.error, row.cardId,
app/src/main/java/app/resound/importer/ImportKindContract.kt:11:enum class ImportRecordStatus { Transcribing, Summarizing, Done, Failed }
app/src/main/java/app/resound/importer/ImportKindContract.kt:14:data class ImportRecord(
app/src/main/java/app/resound/importer/ImportKindContract.kt:20:    val status: ImportRecordStatus,
app/src/main/java/app/resound/importer/ImportKindContract.kt:30:interface ImportKinds {
app/src/main/java/app/resound/importer/ImportKindContract.kt:41:    fun records(date: LocalDate): Flow<List<ImportRecord>>
app/src/main/java/app/resound/importer/ImportKindContract.kt:42:    fun record(id: Long): Flow<ImportRecord?>
app/src/main/java/app/resound/AppGraph.kt:141:    lateinit var importKinds: app.resound.importer.ImportKinds
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:33:import app.resound.importer.ImportRecord
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:34:import app.resound.importer.ImportRecordStatus
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:49:private fun statusText(r: ImportRecord): String? = when (r.status) {
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:50:    ImportRecordStatus.Transcribing -> "转写中"
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:51:    ImportRecordStatus.Summarizing -> if (r.kind == ImportKind.Normal) "整理中" else "生成纪要中"
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:52:    ImportRecordStatus.Failed -> "没完成"
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:53:    ImportRecordStatus.Done -> null
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:63:internal fun ImportLine(r: ImportRecord, events: Int, onOpen: () -> Unit) {
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:73:                style = RsType.caption, color = if (r.status == ImportRecordStatus.Failed) c.critical else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:106:        statusText(r)?.let { Text(it + (r.error?.let { e -> "：$e" } ?: ""), style = RsType.subhead, color = if (r.status == ImportRecordStatus.Failed) c.critical else c.textSecondary) }
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:117:            } else Text(if (r.status == ImportRecordStatus.Done || r.status == ImportRecordStatus.Failed) "还没有纪要，可以点下面“重新生成”" else "转写完会自动生成纪要",
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:141:        val busy = r.status == ImportRecordStatus.Transcribing || r.status == ImportRecordStatus.Summarizing
app/src/main/java/app/resound/ui/today/TodayScreen.kt:192:    val inImport: (MemoryEvent) -> app.resound.importer.ImportRecord? = { e -> folded.firstOrNull { e.startAt >= it.startAt && e.startAt < it.endAt } }
app/src/main/java/app/resound/ui/today/TodayScreen.kt:744:    data class Import(val record: app.resound.importer.ImportRecord, val events: List<MemoryEvent>) : TimelineEntry { override val at get() = record.startAt }
app/src/main/java/app/resound/ui/today/TodayScreen.kt:751:                           onOpenImport: (app.resound.importer.ImportRecord) -> Unit = {},
```

## transcribe/TranscribeContract.kt

类型：TranscriptStatus, TranscriptLine, VoiceInputMeta, SegmentTranscript, TranscribeQueueState, TranscriptRepository, TranscribeSettings

```text
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:20:    private val settings: TranscribeSettings, private val scheduler: TranscribeScheduler, private val scope: CoroutineScope) : TranscriptRepository {
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:44:    private val initial = TranscribeQueueState(0, 0, false, last.value.first, null, if (settings.ready.value) last.value.second else "转写未就绪，请检查密钥或下载本地模型", 0, 0)
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:45:    override val queue: StateFlow<TranscribeQueueState> = combine(dao.observeQueueCounts().distinctUntilChanged(), usage, effectiveLast, scheduler.observe(), calendar) { counts, usage, state, work, _ ->
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:49:        TranscribeQueueState(counts.pending, counts.failed, counts.running, state.first, next, state.second,
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:59:    override fun day(date: LocalDate): Flow<List<SegmentTranscript>> {
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:64:            SegmentTranscript(segment.id, segment.startAt, segment.endAt, TranscriptStatus.valueOf(segment.status),
app/src/main/java/app/resound/transcribe/RoomTranscriptRepository.kt:65:                lines.sortedWith(compareBy({ it.startAt }, { it.id })).map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, app.resound.transcribe.VoiceInputMetadata.read(it.voiceInputJson)) },
app/src/main/java/app/resound/transcribe/AndroidTranscribeSettings.kt:23:class AndroidTranscribeSettings internal constructor(private val context: Context, private val headerStore: app.resound.digest.ServiceHeaders) : TranscribeSettings {
app/src/main/java/app/resound/transcribe/TranscribeWork.kt:147:class TranscribeScheduler(private val context: Context, private val settings: TranscribeSettings) {
app/src/main/java/app/resound/transcribe/VoiceInputMetadata.kt:6:internal fun TranscriptLineEntity.voiceInputMeta(): VoiceInputMeta? = VoiceInputMetadata.read(voiceInputJson)
app/src/main/java/app/resound/transcribe/VoiceInputMetadata.kt:8:    fun read(raw: String?): VoiceInputMeta? = raw?.let { runCatching {
app/src/main/java/app/resound/transcribe/VoiceInputMetadata.kt:11:        VoiceInputMeta(j.getString("app"), j.getString("package_name"), optional("title"), optional("before"), optional("after"))
app/src/main/java/app/resound/transcribe/VoiceInputMetadata.kt:13:    fun json(meta: VoiceInputMeta, context: Boolean = true): JSONObject = JSONObject()
app/src/main/java/app/resound/transcribe/VoiceInputMetadata.kt:16:    fun label(meta: VoiceInputMeta) = listOfNotNull("⌨ 语音输入", meta.app, meta.title).joinToString(" · ")
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:12:enum class TranscriptStatus {
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:39:data class TranscriptLine(
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:53:    val voiceInput: VoiceInputMeta? = null,
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:57:data class VoiceInputMeta(
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:70:data class SegmentTranscript(
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:74:    val status: TranscriptStatus,
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:75:    val lines: List<TranscriptLine>,
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:82:data class TranscribeQueueState(
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:99:interface TranscriptRepository {
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:101:    fun day(date: LocalDate): Flow<List<SegmentTranscript>>
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:103:    val queue: StateFlow<TranscribeQueueState>
app/src/main/java/app/resound/transcribe/TranscribeContract.kt:129:interface TranscribeSettings {
app/src/main/java/app/resound/AppGraph.kt:8:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/AppGraph.kt:9:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/AppGraph.kt:164:    lateinit var transcripts: TranscriptRepository
app/src/main/java/app/resound/AppGraph.kt:165:    lateinit var transcribeSettings: TranscribeSettings
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:14:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:95:    override suspend fun sourceLines(lineId: Long): List<TranscriptLine> = kotlinx.coroutines.withContext(Dispatchers.IO) {
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:96:        dao.segmentLinesOf(lineId).map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, app.resound.transcribe.VoiceInputMetadata.read(it.voiceInputJson)) }
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:98:    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = dao.eventLines(eventId).map { rows ->
app/src/main/java/app/resound/digest/RoomMemoryRepository.kt:99:        rows.map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, app.resound.transcribe.VoiceInputMetadata.read(it.voiceInputJson)) }
app/src/main/java/app/resound/digest/MemoryContract.kt:3:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/digest/MemoryContract.kt:18:    fun eventLines(eventId: Long): Flow<List<TranscriptLine>>
app/src/main/java/app/resound/digest/MemoryContract.kt:20:    suspend fun sourceLines(lineId: Long): List<TranscriptLine> = emptyList()
app/src/main/java/app/resound/health/HealthAndroid.kt:29:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/health/HealthAndroid.kt:30:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/health/HealthAndroid.kt:54:    private val transcribe: TranscribeSettings,
app/src/main/java/app/resound/health/HealthAndroid.kt:55:    private val transcripts: TranscriptRepository,
app/src/main/java/app/resound/capture/VoiceInputEngine.kt:3:import app.resound.transcribe.VoiceInputMeta
app/src/main/java/app/resound/capture/VoiceInputEngine.kt:14:internal data class InputRecord(val token: String, val at: Long, val text: String, val meta: VoiceInputMeta,
app/src/main/java/app/resound/capture/VoiceInputEngine.kt:320:            VoiceInputMeta(j.screen.app, j.screen.packageName, if (j.context) base.title else null,
app/src/main/java/app/resound/capture/VoiceInputEngine.kt:333:            VoiceInputMeta(w.screen.app, w.screen.packageName, w.screen.title?.takeIf { w.screen.packageName in VoiceInputOcr.TITLED },
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:50:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:88:fun OnboardingWizard(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:367:private fun ColumnScope.TryIt(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, since: Long, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:68:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:292:    transcripts: TranscriptRepository?,
app/src/main/java/app/resound/ui/items/DayDiary.kt:66:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/items/DayDiary.kt:198:    lines: List<TranscriptLine>,
app/src/main/java/app/resound/ui/items/DayDiary.kt:490:private fun LineRow(line: TranscriptLine) {
app/src/main/java/app/resound/ui/items/DayDiary.kt:652:internal fun dayLines(segments: List<app.resound.transcribe.SegmentTranscript>): List<TranscriptLine> =
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:3:import app.resound.transcribe.SegmentTranscript
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:4:import app.resound.transcribe.TranscribeQueueState
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:5:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:6:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:7:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:8:import app.resound.transcribe.TranscriptStatus
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:21:class FakeTranscriptRepository : TranscriptRepository {
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:27:        TranscribeQueueState(
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:38:    override val queue: StateFlow<TranscribeQueueState> = _queue
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:40:    override fun day(date: LocalDate): Flow<List<SegmentTranscript>> =
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:41:        segments.map { all -> if (date == today) all else sample(date).filter { it.status == TranscriptStatus.Done } }
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:48:                if (it.status == TranscriptStatus.Pending) it.copy(status = TranscriptStatus.Running) else it
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:52:                if (it.status == TranscriptStatus.Running) {
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:53:                    it.copy(status = TranscriptStatus.Done, lines = listOf(line(it.segmentId * 10, it.startAt, "0", "好，那就这么定了，我回头把方案发群里。")))
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:71:            if (it.status == TranscriptStatus.Failed) it.copy(status = TranscriptStatus.Pending, error = null) else it
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:76:    private fun sample(date: LocalDate): List<SegmentTranscript> {
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:79:            SegmentTranscript(9, at(16, 40), at(16, 41, 20), TranscriptStatus.Pending, emptyList(), audioAvailable = true),
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:80:            SegmentTranscript(8, at(16, 12), at(16, 12, 40), TranscriptStatus.Pending, emptyList(), audioAvailable = true),
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:81:            SegmentTranscript(7, at(15, 3), at(15, 3, 30), TranscriptStatus.Failed, emptyList(), error = "网络中断，已重试 5 次", audioAvailable = true),
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:82:            SegmentTranscript(6, at(14, 20), at(14, 20, 8), TranscriptStatus.Empty, emptyList(), audioAvailable = true),
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:83:            SegmentTranscript(
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:84:                5, at(14, 2), at(14, 5, 10), TranscriptStatus.Done,
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:93:            SegmentTranscript(
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:94:                4, at(12, 31), at(12, 33), TranscriptStatus.Done,
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:101:            SegmentTranscript(
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:102:                3, at(9, 15), at(9, 16, 30), TranscriptStatus.Done,
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:110:        TranscriptLine(id, at, at + 4000, text, tag)
app/src/main/java/app/resound/ui/preview/FakeTranscripts.kt:113:class FakeTranscribeSettings : TranscribeSettings {
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:10:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:65:    override fun eventLines(eventId: Long): Flow<List<TranscriptLine>> = flowOf(
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:67:            TranscriptLine(1, at(10, 30, 15), at(10, 30, 18), "那个测试手机到了没有？", "0"),
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:68:            TranscriptLine(2, at(10, 30, 19), at(10, 30, 24), "还没到，物流显示明天上午送。", "1"),
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:69:            TranscriptLine(3, at(10, 30, 25), at(10, 30, 30), "行，那周三的评审你能准备好吗？", "0"),
app/src/main/java/app/resound/ui/preview/FakeMemory.kt:70:            TranscriptLine(4, at(10, 30, 31), at(10, 30, 38), "可以，我周二下班前把材料发你。", "1"),
app/src/main/java/app/resound/ui/transcript/LinePlayer.kt:18:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/transcript/LinePlayer.kt:47:    var allLines by mutableStateOf<List<TranscriptLine>>(emptyList()); private set
app/src/main/java/app/resound/ui/transcript/LinePlayer.kt:59:    private var queue: List<TranscriptLine> = emptyList()
app/src/main/java/app/resound/ui/transcript/LinePlayer.kt:75:    fun play(context: Context, lines: List<TranscriptLine>, index: Int, from: PlaySource? = null, leadMillis: Long = 0) {
app/src/main/java/app/resound/ui/transcript/LinePlayer.kt:148:    private suspend fun startAt(line: TranscriptLine, atAbs: Long? = null, request: Long = playRequest) {
app/src/main/java/app/resound/ui/transcript/PlayTimeline.kt:4:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/transcript/PlayTimeline.kt:13:internal class PlayTimeline(lines: List<TranscriptLine>) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:94:import app.resound.transcribe.SegmentTranscript
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:95:import app.resound.transcribe.TranscribeQueueState
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:102:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:105:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:106:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:107:import app.resound.transcribe.TranscriptStatus
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:126:    repo: TranscriptRepository,
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:127:    settings: TranscribeSettings,
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:134:    val segments by remember(date) { repo.day(date).map { day -> day.filterNot { it.status == TranscriptStatus.Done && it.lines.isEmpty() } } }
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:203:                    val done = list.count { it.status == TranscriptStatus.Done || it.status == TranscriptStatus.Empty }
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:274:private fun QueueCard(repo: TranscriptRepository, hasKey: Boolean, realtime: Boolean, local: Boolean, settingsShown: Boolean,
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:351:private fun queueDetail(q: TranscribeQueueState, hasKey: Boolean, realtime: Boolean, now: Long): String = when {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:393:private fun SettingsCard(settings: TranscribeSettings, onSaved: () -> Unit) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:709:private fun SegmentCard(seg: SegmentTranscript, modifier: Modifier, realtime: Boolean, onTranscribeAnyway: () -> Unit,
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:738:                TranscriptStatus.Done -> SpeakerLines(seg.lines)
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:739:                TranscriptStatus.Running -> Placeholder()
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:740:                TranscriptStatus.Pending -> Note(if (realtime) "马上转写" else "等下一轮转写")
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:741:                TranscriptStatus.Empty -> Note("只有声音，没识别出说话内容")
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:742:                TranscriptStatus.Failed -> Note(seg.error ?: "没转成功，可以在上面点重试", c.critical)
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:743:                TranscriptStatus.SkippedPlayback -> Row(verticalAlignment = Alignment.CenterVertically) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:748:                TranscriptStatus.Abandoned -> FailedActions(seg.error ?: "录音损坏，无法转写", retry = null, onDelete = onDelete)
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:750:                TranscriptStatus.Quiet -> FailedActions(seg.error ?: "多次转写失败", retry = if (seg.audioAvailable) onRetry else null, onDelete = onDelete)
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:761:private fun StatusChip(seg: SegmentTranscript) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:764:        TranscriptStatus.Pending -> "等待中" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:765:        TranscriptStatus.Running -> "转写中" to c.accent
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:766:        TranscriptStatus.Failed -> "没转成功" to c.critical
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:767:        TranscriptStatus.Empty -> "没有文字" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:768:        TranscriptStatus.Done -> if (seg.audioAvailable) return else "录音已删" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:769:        TranscriptStatus.SkippedPlayback -> "外放时录的" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:770:        TranscriptStatus.Abandoned -> "无法转写" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:771:        TranscriptStatus.Quiet -> "已忽略" to c.textTertiary
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:815:fun SpeakerLines(lines: List<TranscriptLine>) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:820:        lines.fold(mutableListOf<MutableList<TranscriptLine>>()) { acc, line ->
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:838:    fun tap(line: TranscriptLine) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:941:private fun VoiceInputContext(v: app.resound.transcribe.VoiceInputMeta) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:967:private fun TranscriptLine.maybeMe() = speakerName == null && (speakerScore ?: 0f) >= VoiceprintThresholds.MAYBE
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:969:private fun TranscriptLine.speakerKey() = speakerName ?: if (maybeMe()) "可能是我" else speakerTag
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:971:private fun speakerLabel(line: TranscriptLine): String =
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:975:private fun speakerColor(line: TranscriptLine): Color {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:990:private fun SpeakerBadge(line: TranscriptLine) {
app/src/main/java/app/resound/ui/transcript/TranscriptScreen.kt:1014:private fun BoxScope.SegmentSelectBar(selection: app.resound.ui.today.TimelineSelection, order: List<Long>, chosen: List<SegmentTranscript>, bottomPadding: androidx.compose.ui.unit.Dp,
app/src/main/java/app/resound/ui/today/TodayScreen.kt:78:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/today/TodayScreen.kt:142:    transcripts: TranscriptRepository,
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1073:private fun SourceAudioButton(source: app.resound.transcribe.TranscriptLine, lines: List<app.resound.transcribe.TranscriptLine>, item: MemoryItem, memory: MemoryRepository, lead: Long = 5_000L) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:1123:    val fallback by androidx.compose.runtime.produceState<List<app.resound.transcribe.TranscriptLine>?>(null, item.sourceLineId, eventLines) {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:46:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/today/RhythmViews.kt:80:private fun overlaps(l: TranscriptLine, e: MemoryEvent) = l.startAt < e.endAt && l.endAt > e.startAt
app/src/main/java/app/resound/ui/today/RhythmViews.kt:82:private fun TranscriptLine.ms() = (endAt - startAt).coerceAtLeast(0)
app/src/main/java/app/resound/ui/today/RhythmViews.kt:84:internal fun dayRhythm(events: List<MemoryEvent>, lines: List<TranscriptLine>): DayRhythm? {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:90:    fun byHour(ls: List<TranscriptLine>) = IntArray(24).also { h -> ls.forEach { h[Instant.ofEpochMilli(it.startAt).atZone(zone).hour] += (it.ms() / 1000).toInt() } }.toList()
app/src/main/java/app/resound/ui/today/RhythmViews.kt:134:internal fun RhythmCard(events: List<MemoryEvent>, lines: List<TranscriptLine>, date: LocalDate, pendingSegments: Int,
app/src/main/java/app/resound/ui/today/OverviewCard.kt:55:import app.resound.transcribe.TranscriptRepository
app/src/main/java/app/resound/ui/today/OverviewCard.kt:113:    transcripts: TranscriptRepository,
app/src/main/java/app/resound/ui/today/OverviewCard.kt:167:    capture: CaptureController, memory: MemoryRepository, transcripts: TranscriptRepository, hasKey: Boolean,
app/src/main/java/app/resound/ui/today/CopyContent.kt:20:import app.resound.transcribe.TranscriptLine
app/src/main/java/app/resound/ui/today/CopyContent.kt:59:internal fun eventCopyText(event: MemoryEvent, lines: List<TranscriptLine>, mode: CopyMode): String = buildString {
app/src/main/java/app/resound/ui/importer/ImportRecordViews.kt:36:import app.resound.transcribe.SegmentTranscript
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:46:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:76:    transcribeSettings: TranscribeSettings,
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:49:import app.resound.transcribe.TranscribeSettings
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:74:    transcribeSettings: TranscribeSettings,
app/src/test/java/app/resound/transcribe/TranscribeSchedulerTest.kt:24:    private class Settings : TranscribeSettings {
app/src/test/java/app/resound/diary/DayDiaryTest.kt:261:        val meta = app.resound.transcribe.VoiceInputMetadata.json(app.resound.transcribe.VoiceInputMeta(
app/src/test/java/app/resound/capture/VoiceInput048Test.kt:38:    private val meta = VoiceInputMeta("合成聊天", "synthetic.chat", "老王", "报告明天能交吗", null)
app/src/test/java/app/resound/capture/VoiceInput049Test.kt:7:import app.resound.transcribe.VoiceInputMeta
app/src/test/java/app/resound/capture/VoiceInput049Test.kt:228:        InputRecord("$at-$text", at, text, VoiceInputMeta("聊天", pkg, title, before, "不应存的回复"), 999999L)
app/src/test/java/app/resound/ui/LinePlayerStart091Test.kt:54:    private fun play() = LinePlayer.play(RuntimeEnvironment.getApplication(), listOf(TranscriptLine(1, 1000, 2000, "测试", null, segmentId = 1)), 0)
app/src/test/java/app/resound/ui/RhythmTest.kt:4:import app.resound.transcribe.TranscriptLine
app/src/test/java/app/resound/ui/RhythmTest.kt:16:    private fun line(id: Long, s: Long, e: Long) = TranscriptLine(id, s, e, "话", null)
app/src/test/java/app/resound/ui/PlayTimelineTest.kt:3:import app.resound.transcribe.TranscriptLine
app/src/test/java/app/resound/ui/PlayTimelineTest.kt:9:    private fun line(id: Long, seg: Long, s: Long, e: Long) = TranscriptLine(id, s, e, "话", null, segmentId = seg)
```

## capture/CaptureContract.kt

类型：CaptureStatus, CaptureController, DayStats, SegmentInfo, WatchdogEventType, WatchdogEvent, CaptureStatsRepository, SetupItem, SetupGuide

```text
app/src/main/java/app/resound/diagnostics/LocalProblemReports.kt:5:import app.resound.capture.CaptureStatsRepository
app/src/main/java/app/resound/diagnostics/LocalProblemReports.kt:20:    private val stats: CaptureStatsRepository,
app/src/main/java/app/resound/quick/QuickActionsAndroid.kt:13:import app.resound.capture.CaptureController
app/src/main/java/app/resound/quick/QuickActionsAndroid.kt:32:internal fun attachQuickActionsAndroid(context: Context, scope: CoroutineScope, capture: CaptureController,
app/src/main/java/app/resound/voiceprint/VoiceprintRecorder.kt:6:import app.resound.capture.CaptureController
app/src/main/java/app/resound/voiceprint/VoiceprintRecorder.kt:13:    suspend fun record(controller: CaptureController, progress: (Int) -> Unit): ShortArray = withContext(Dispatchers.IO) {
app/src/main/java/app/resound/AppGraph.kt:3:import app.resound.capture.CaptureController
app/src/main/java/app/resound/AppGraph.kt:4:import app.resound.capture.CaptureStatsRepository
app/src/main/java/app/resound/AppGraph.kt:5:import app.resound.capture.SetupGuide
app/src/main/java/app/resound/AppGraph.kt:160:    lateinit var capture: CaptureController
app/src/main/java/app/resound/AppGraph.kt:161:    lateinit var stats: CaptureStatsRepository
app/src/main/java/app/resound/AppGraph.kt:162:    lateinit var setup: SetupGuide
app/src/main/java/app/resound/widget/CaptureWidget.kt:13:import app.resound.capture.CaptureController
app/src/main/java/app/resound/widget/CaptureWidget.kt:51:        fun attach(context: Context, scope: CoroutineScope, capture: CaptureController, topics: TopicRepository) {
app/src/main/java/app/resound/digest/AndroidTodoVoiceInput.kt:6:import app.resound.capture.CaptureController
app/src/main/java/app/resound/digest/AndroidTodoVoiceInput.kt:19:    private val controller: CaptureController,
app/src/main/java/app/resound/health/HealthAndroid.kt:20:import app.resound.capture.CaptureController
app/src/main/java/app/resound/health/HealthAndroid.kt:52:    private val capture: CaptureController,
app/src/main/java/app/resound/capture/CaptureContract.kt:13:data class CaptureStatus(
app/src/main/java/app/resound/capture/CaptureContract.kt:27:interface CaptureController {
app/src/main/java/app/resound/capture/CaptureContract.kt:28:    val status: StateFlow<CaptureStatus>
app/src/main/java/app/resound/capture/CaptureContract.kt:48:data class DayStats(
app/src/main/java/app/resound/capture/CaptureContract.kt:70:data class SegmentInfo(
app/src/main/java/app/resound/capture/CaptureContract.kt:79:enum class WatchdogEventType {
app/src/main/java/app/resound/capture/CaptureContract.kt:94:data class WatchdogEvent(
app/src/main/java/app/resound/capture/CaptureContract.kt:96:    val type: WatchdogEventType,
app/src/main/java/app/resound/capture/CaptureContract.kt:103:interface CaptureStatsRepository {
app/src/main/java/app/resound/capture/CaptureContract.kt:104:    fun dayStats(date: LocalDate): Flow<DayStats>
app/src/main/java/app/resound/capture/CaptureContract.kt:106:    fun segments(date: LocalDate): Flow<List<SegmentInfo>>
app/src/main/java/app/resound/capture/CaptureContract.kt:108:    fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>>
app/src/main/java/app/resound/capture/CaptureContract.kt:115:data class SetupItem(
app/src/main/java/app/resound/capture/CaptureContract.kt:123:interface SetupGuide {
app/src/main/java/app/resound/capture/CaptureContract.kt:125:    fun items(): List<SetupItem>
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:16:class AndroidSetupGuide(private val context: Context) : SetupGuide {
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:18:    override fun items(): List<SetupItem> {
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:23:            SetupItem("mic", "麦克风权限", "在应用权限里允许使用麦克风。", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:24:            SetupItem("notify", "通知权限", "允许通知，并允许录音中断提醒显示。", NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)),
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:25:            SetupItem("battery", "忽略电池优化", "允许${context.appName}不受电池优化限制。", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)),
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:26:            if (!xiaomi) null else SetupItem("autostart", "小米自启动", "在安全中心为${context.appName}开启自启动；系统不提供可靠查询，设置后请手动确认。", manual("autostart")),
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:27:            if (!xiaomi) null else SetupItem("powerkeeper", "省电策略：无限制", "将${context.appName}的省电策略设为无限制，设置后请手动确认。", manual("powerkeeper")),
app/src/main/java/app/resound/capture/AndroidSetupGuide.kt:28:            SetupItem("lock", "最近任务锁定", "打开最近任务，长按${context.appName}卡片并点锁定（不同手机叫法略有不同）；设置后请手动确认。", manual("lock")))
app/src/main/java/app/resound/capture/CaptureStore.kt:19:    suspend fun event(type: WatchdogEventType, detail: String? = null, gapStart: Long? = null, gapEnd: Long? = null) {
app/src/main/java/app/resound/capture/CaptureTilePolicy.kt:5:internal fun CaptureTilePolicy.click(controller: CaptureController, lastPauseMinutes: Int = QuickPrefsRules.DEFAULT_PAUSE) = when (action(controller.status.value.state)) {
app/src/main/java/app/resound/capture/CaptureTilePolicy.kt:11:internal fun CaptureTilePolicy.subtitle(status: CaptureStatus, offsetSeconds: Int = 0): String =
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:15:    private val transcribeDiagnostics: suspend () -> String = { "" }) : CaptureStatsRepository {
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:20:    override fun segments(date: LocalDate): Flow<List<SegmentInfo>> {
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:22:        return dao.segments(a, b).map { rows -> rows.map { SegmentInfo(it.id, it.startAt, it.endAt, it.sourceId, File(it.path), it.sizeBytes) } }
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:24:    override fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>> {
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:28:            WatchdogEventType.entries.find { it.name == row.type }?.let { type ->
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:29:                WatchdogEvent(row.at, type, row.detail, row.batteryPercent, row.charging)
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:33:    override fun dayStats(date: LocalDate): Flow<DayStats> {
app/src/main/java/app/resound/capture/RoomCaptureStatsRepository.kt:43:            DayStats(date, segments.count { it.startAt in a until b }, segments.sumOf { overlap(it.startAt, it.endAt) },
app/src/main/java/app/resound/capture/AndroidCaptureController.kt:11:class AndroidCaptureController(private val context: Context, private val store: CaptureStore) : CaptureController {
app/src/main/java/app/resound/capture/AndroidCaptureController.kt:12:    private val current = MutableStateFlow(CaptureStatus(CaptureState.Stopped, System.currentTimeMillis()))
app/src/main/java/app/resound/capture/CaptureTileServiceAndroid.kt:30:    private fun render(status: CaptureStatus) {
app/src/main/java/app/resound/capture/CaptureWatchdog.kt:52:                    app.store.event(WatchdogEventType.BootRestart, "${intent.action}：等待点击通知恢复，未从后台启动麦克风")
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:46:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:88:fun OnboardingWizard(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:169:private fun ColumnScope.Permissions(capture: CaptureController, onNext: () -> Unit) {
app/src/main/java/app/resound/ui/onboarding/OnboardingWizard.kt:367:private fun ColumnScope.TryIt(capture: CaptureController, transcripts: TranscriptRepository, memory: MemoryRepository, since: Long, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:77:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:79:import app.resound.capture.CaptureStatsRepository
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:80:import app.resound.capture.DayStats
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:81:import app.resound.capture.SetupGuide
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:82:import app.resound.capture.SetupItem
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:83:import app.resound.capture.WatchdogEvent
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:84:import app.resound.capture.WatchdogEventType
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:102:    capture: CaptureController,
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:103:    stats: CaptureStatsRepository,
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:104:    setup: SetupGuide,
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:166:fun AdvancedPage(stats: CaptureStatsRepository, bottomPadding: androidx.compose.ui.unit.Dp) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:264:private fun StatusCard(capture: CaptureController) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:383:private fun StatsGrid(day: DayStats?) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:431:private fun WatchdogSection(events: List<WatchdogEvent>) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:488:private fun compactEvents(events: List<WatchdogEvent>): List<WatchdogEvent> {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:489:    val micTypes = setOf(WatchdogEventType.MicBusy, WatchdogEventType.MicReleased)
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:490:    val out = mutableListOf<WatchdogEvent>()
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:498:        val times = run.count { it.type == WatchdogEventType.MicBusy }
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:501:            type = WatchdogEventType.MicBusy,
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:511:private fun AllEventsSheet(events: List<WatchdogEvent>, onClose: () -> Unit) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:539:private fun EventRow(e: WatchdogEvent, last: Boolean) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:542:        WatchdogEventType.ServiceStarted -> "开始聆听" to c.good
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:543:        WatchdogEventType.ServiceStopped -> "停止" to c.textTertiary
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:544:        WatchdogEventType.Killed -> "被系统关掉" to c.critical
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:545:        WatchdogEventType.Recovered -> "自动恢复" to c.good
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:546:        WatchdogEventType.Paused -> "暂停" to c.warning
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:547:        WatchdogEventType.Resumed -> "恢复" to c.good
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:548:        WatchdogEventType.MicBusy -> "麦克风让给别的应用" to c.warning
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:549:        WatchdogEventType.MicReleased -> "收回麦克风" to c.good
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:550:        WatchdogEventType.InputSwitched -> "录音输入切换" to c.accent
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:551:        WatchdogEventType.BootRestart ->
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:570:private fun SetupSection(setup: SetupGuide) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:589:private fun SetupRow(item: SetupItem, onOpen: () -> Unit, onDone: () -> Unit) {
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:626:                SetupItem("usage", "看出谁占了麦克风",
app/src/main/java/app/resound/ui/verify/VerifyScreen.kt:636:private fun ExportRow(stats: CaptureStatsRepository) {
app/src/main/java/app/resound/ui/today/TodayScreen.kt:77:import app.resound.capture.CaptureStatsRepository
app/src/main/java/app/resound/ui/today/TodayScreen.kt:99:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/today/TodayScreen.kt:140:    capture: CaptureController,
app/src/main/java/app/resound/ui/today/TodayScreen.kt:141:    stats: CaptureStatsRepository,
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:3:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:5:import app.resound.capture.CaptureStatsRepository
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:6:import app.resound.capture.CaptureStatus
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:7:import app.resound.capture.DayStats
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:8:import app.resound.capture.SegmentInfo
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:9:import app.resound.capture.SetupGuide
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:10:import app.resound.capture.SetupItem
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:11:import app.resound.capture.WatchdogEvent
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:12:import app.resound.capture.WatchdogEventType
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:27:class FakeCaptureController : CaptureController {
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:28:    private val _status = MutableStateFlow(CaptureStatus(CaptureState.Listening, System.currentTimeMillis()))
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:29:    override val status: StateFlow<CaptureStatus> = _status
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:53:        _status.value = CaptureStatus(CaptureState.Listening, System.currentTimeMillis())
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:57:        _status.value = CaptureStatus(CaptureState.Stopped, System.currentTimeMillis())
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:62:        _status.value = CaptureStatus(CaptureState.Paused, now, pausedUntil = now + minutes * 60_000L)
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:68:class FakeStatsRepository : CaptureStatsRepository {
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:72:    override fun dayStats(date: LocalDate): Flow<DayStats> = flowOf(
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:73:        DayStats(
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:86:    override fun segments(date: LocalDate): Flow<List<SegmentInfo>> = flowOf(emptyList())
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:88:    override fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>> = flowOf(
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:90:            WatchdogEvent(at(date, 7, 42), WatchdogEventType.ServiceStarted, "手动开启", 92, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:91:            WatchdogEvent(at(date, 10, 5), WatchdogEventType.MicBusy, "来电", 85, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:92:            WatchdogEvent(at(date, 10, 11), WatchdogEventType.MicReleased, null, 85, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:93:            WatchdogEvent(at(date, 13, 20), WatchdogEventType.Killed, "漏掉约 6 分钟", 71, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:94:            WatchdogEvent(at(date, 13, 26), WatchdogEventType.Recovered, "定时检查拉起", 71, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:95:            WatchdogEvent(at(date, 15, 0), WatchdogEventType.Paused, "暂停 1 小时", 64, false),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:96:            WatchdogEvent(at(date, 16, 0), WatchdogEventType.Resumed, "到点自动恢复", 60, true),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:103:class FakeSetupGuide : SetupGuide {
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:106:        SetupItem("mic", "麦克风权限", "用来收音，只在有人说话时保存", "mic" in done),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:107:        SetupItem("notify", "通知权限", "显示“正在聆听”和中断提醒", "notify" in done),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:108:        SetupItem("battery", "忽略电池优化", "避免系统为省电把录音停掉", "battery" in done),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:109:        SetupItem("autostart", "允许自启动", "被关掉后能自己重新启动", if ("autostart" in done) true else null),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:110:        SetupItem("powerkeeper", "省电策略设为“无限制”", "小米最容易杀后台的一项", if ("powerkeeper" in done) true else null),
app/src/main/java/app/resound/ui/preview/FakeCapture.kt:111:        SetupItem("lock", "在最近任务里锁定回响", "多任务界面下拉回响卡片，出现小锁", if ("lock" in done) true else null),
app/src/test/java/app/resound/quick/Quick147Test.kt:3:import app.resound.capture.CaptureController
app/src/test/java/app/resound/quick/Quick147Test.kt:5:import app.resound.capture.CaptureStatus
app/src/test/java/app/resound/quick/Quick147Test.kt:21:        val controller = object : CaptureController {
app/src/test/java/app/resound/quick/Quick147Test.kt:22:            override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
app/src/test/java/app/resound/voiceprint/InputPersistence045Test.kt:39:            assertEquals(WatchdogEventType.InputSwitched, event.type)
app/src/test/java/app/resound/privacy/PrivacyCaptureTest.kt:26:        val controller = object : CaptureController {
app/src/test/java/app/resound/privacy/PrivacyCaptureTest.kt:27:            override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
app/src/test/java/app/resound/privacy/PrivacyCaptureTest.kt:35:        controller.status.value = CaptureStatus(CaptureState.Paused, 0, 0)
app/src/test/java/app/resound/privacy/PrivacyCaptureTest.kt:38:        controller.status.value = CaptureStatus(CaptureState.Stopped, 0)
app/src/test/java/app/resound/digest/ManualTodo054Test.kt:198:    private class Controller : CaptureController {
app/src/test/java/app/resound/digest/ManualTodo054Test.kt:199:        override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
app/src/test/java/app/resound/digest/ManualTodo054Test.kt:202:        override fun stop() { status.value = CaptureStatus(CaptureState.Stopped, 0) }
app/src/test/java/app/resound/digest/ManualTodo054Test.kt:203:        override fun pause(minutes: Int) { status.value = CaptureStatus(CaptureState.Paused, 0) }
app/src/test/java/app/resound/digest/ManualTodo054Test.kt:204:        override fun resume() { resumes++; status.value = CaptureStatus(CaptureState.Listening, 0) }
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:39:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:75:    capture: CaptureController,
app/src/main/java/app/resound/ui/settings/SettingsHome.kt:312:private fun HealthCard(r: HealthReport, capture: CaptureController, onOpen: (SettingsPage) -> Unit) {
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:46:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/settings/SettingsScreen.kt:73:    capture: CaptureController,
app/src/main/java/app/resound/ui/today/ListenSheet.kt:33:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/today/ListenSheet.kt:49:fun ListenSheet(capture: CaptureController, startOnForget: Boolean = false, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/today/QuickPause.kt:25:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/today/QuickPause.kt:47:internal fun BoxScope.PauseBar(note: Pair<Int, Long>?, bottomPadding: Dp, capture: CaptureController, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/today/OverviewCard.kt:48:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/today/OverviewCard.kt:111:    capture: CaptureController,
app/src/main/java/app/resound/ui/today/OverviewCard.kt:167:    capture: CaptureController, memory: MemoryRepository, transcripts: TranscriptRepository, hasKey: Boolean,
app/src/main/java/app/resound/capture/CaptureService.kt:134:                store.event(WatchdogEventType.ServiceStopped, "用户正常停止")
app/src/main/java/app/resound/capture/CaptureService.kt:154:                for ((from, to) in gaps) store.event(WatchdogEventType.Killed, "上次未正常停止；排除暂停后缺口约 ${to - from}ms（上界估计）", from, to)
app/src/main/java/app/resound/capture/CaptureService.kt:155:                store.event(WatchdogEventType.Recovered, "恢复途径=${intent.getStringExtra("route") ?: "unknown"}")
app/src/main/java/app/resound/capture/CaptureService.kt:159:            store.event(WatchdogEventType.ServiceStarted, "启动途径=${intent.getStringExtra("route")}")
app/src/main/java/app/resound/capture/CaptureService.kt:208:        store.event(WatchdogEventType.Paused, if (quietName == null) "暂停至 $until" else "停录时段：$quietName，至 $clock")
app/src/main/java/app/resound/capture/CaptureService.kt:224:        if (wasPaused) store.event(WatchdogEventType.Resumed, "暂停结束或用户恢复")
app/src/main/java/app/resound/capture/CaptureService.kt:285:                                    store.event(WatchdogEventType.MicBusy, if (busyDetail == event.reason) event.reason else "${event.reason}；$busyDetail")
app/src/main/java/app/resound/capture/CaptureService.kt:293:                                if (busy) { store.event(WatchdogEventType.MicReleased); busy = false }
app/src/main/java/app/resound/capture/CaptureService.kt:307:                        if (busy) { store.event(WatchdogEventType.MicReleased); busy = false }
app/src/main/java/app/resound/capture/CaptureService.kt:348:                    store.event(WatchdogEventType.MicBusy, "${CaptureHealth.ZERO_PCM}，第 $zeroFails 次重开")
app/src/main/java/app/resound/capture/CaptureService.kt:354:                } else if (!busy) { store.event(WatchdogEventType.MicBusy, "${e.reason}: ${e.message}"); busy = true }
app/src/main/java/app/resound/capture/CaptureService.kt:383:        if (initialized) { beat(); store.event(WatchdogEventType.ServiceStopped, "$detail；等待手动恢复，保留未正常停止标记") }
app/src/main/java/app/resound/ui/today/StartChecklist.kt:20:import app.resound.capture.CaptureController
app/src/main/java/app/resound/ui/today/StartChecklist.kt:49:fun StartChecklist(capture: CaptureController, hasLlmKey: Boolean, topGap: androidx.compose.ui.unit.Dp = 0.dp) {
```

## diary/DiaryContract.kt

类型：DiaryRepository

```text
app/src/main/java/app/resound/AppGraph.kt:154:    lateinit var diary: app.resound.diary.DiaryRepository
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:60:import app.resound.diary.DiaryRepository
app/src/main/java/app/resound/ui/items/ItemsScreens.kt:293:    diary: DiaryRepository?,
app/src/main/java/app/resound/ui/items/DayDiary.kt:59:import app.resound.diary.DiaryRepository
app/src/main/java/app/resound/ui/items/DayDiary.kt:508:internal fun ExportSheet(diary: DiaryRepository, date: LocalDate, onClose: () -> Unit) {
app/src/main/java/app/resound/diary/DiaryContract.kt:11:interface DiaryRepository {
app/src/main/java/app/resound/diary/LocalDiaryRepository.kt:31:) : DiaryRepository {
app/src/main/java/app/resound/push/DailyMemory.kt:50:    // 复用 DiaryRepository.markdown，而取数窗口与回顾严格一致：(前一日截止，当日截止]。
app/src/main/java/app/resound/backup/AndroidBackupRepository.kt:19:    private val diary: app.resound.diary.DiaryRepository? = null,
app/src/test/java/app/resound/backup/DiaryBackupTest.kt:10:import app.resound.diary.DiaryRepository
app/src/test/java/app/resound/backup/DiaryBackupTest.kt:39:        val diary = object : DiaryRepository {
app/src/test/java/app/resound/backup/BackupPrompts029Test.kt:14:import app.resound.diary.DiaryRepository
app/src/test/java/app/resound/backup/BackupPrompts029Test.kt:169:        val diary = object : DiaryRepository {
```

## envsound/EnvSoundContract.kt

类型：SkipMode, MediaMode, EnvSoundSettings, EnvTag, SpecialSound, SegmentVerdict, SegmentSound, EventSounds, HourSound, EnvSpan, EnvStats, EnvModelState, EnvSoundRepository

```text
app/src/androidTest/java/app/resound/envsound/EnvSoundSpeedDeviceTest.kt:52:                val decision = EnvSoundRules.decide(scores, EnvSoundSettings(enabled = true, skip = SkipMode.MarkOnly, media = MediaMode.MarkMedia), me)
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:26:        assertEquals(60_000L, hours()[9]!!.tagMs[EnvTag.Keyboard])
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:33:        assertEquals(EnvTag.Rain, hours()[14]!!.tag)
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:34:        assertEquals(listOf(EnvTag.Rain), envSpans(speech(14 to 15), hours()).map { it.tag })
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:47:        assertEquals(10 * 60_000L, hours()[10]!!.tagMs[EnvTag.Water])
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:48:        assertEquals(10 * 60_000L, hours()[11]!!.tagMs[EnvTag.Water])
app/src/test/java/app/resound/envsound/EnvSpan081Test.kt:55:        assertEquals(listOf(Triple(EnvTag.Keyboard, 9, 11), Triple(EnvTag.Rain, 12, 12), Triple(EnvTag.Rain, 14, 14)),
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:12:    private val enabled = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.SkipTranscribe)
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:22:        assertEquals(setOf(EnvTag.Keyboard, EnvTag.Pet), decision.tags)
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:23:        assertEquals(setOf(SpecialSound.DogBark), decision.sounds)
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:24:        assertEquals(SegmentVerdict.Keep, decision.verdict)
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:40:                if (next.verdict == SegmentVerdict.Media) assertTrue(next.sounds.isEmpty())
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:79:        assertEquals(setOf(EnvTag.Music), EnvSoundRules.decide(result.first, enabled, result.second).tags)
app/src/test/java/app/resound/envsound/EnvSoundGaps075Test.kt:89:        assertEquals(SegmentVerdict.Skipped, EnvSoundRules.decide(result, enabled, false).verdict)
app/src/test/java/app/resound/envsound/EnvMergeConsistencyTest.kt:14:        assertEquals(setOf(EnvTag.Rain, EnvTag.Music), result.getValue(1).tags)
app/src/test/java/app/resound/envsound/EnvMergeConsistencyTest.kt:15:        assertEquals(setOf(EnvTag.Rain), result.getValue(2).tags)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:30:    private val enabled = EnvSoundSettings(enabled=true)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:36:    private val state = MutableStateFlow<EnvModelState>(EnvModelState.Ready)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:58:        assertEquals(SegmentVerdict.Skippable,EnvSoundRules.decide(scores,enabled,false).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:59:        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(score("Speech" to .9f),enabled,false).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:60:        for(h in listOf("Laughter","Cough")) assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(score("Music" to .8f,h to .05f),enabled,false).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:61:        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(mapOf("Music" to 1f),enabled,false).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:65:        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(music,enabled,true).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:66:        assertEquals(SegmentVerdict.Keep,EnvSoundRules.decide(scores,enabled.copy(skip=SkipMode.On),true).verdict)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:68:        assertEquals(SegmentVerdict.Media,media.verdict);assertTrue(media.sounds.isEmpty())
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:69:        assertTrue(EnvSoundRules.decide(music,enabled.copy(media=MediaMode.MarkMedia),false).markMedia)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:82:        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:90:        repo.update(enabled.copy(media=MediaMode.SkipTranscribe));segment(1);runner().run()
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:98:        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:112:        repo.update(enabled);state.value=EnvModelState.NotDownloaded;segment(2);runner().run();assertEquals(0,calls)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:113:        state.value=EnvModelState.Ready;segment(3,clock-1);runner().run();assertEquals(0,calls)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:124:        assertEquals(setOf(EnvTag.Rain),merged.tags);assertEquals(setOf(SpecialSound.Knock),merged.sounds)
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:125:        assertEquals(listOf(id),repo.eventsWith(EnvTag.Rain,SpecialSound.Knock).first())
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:132:        repo.update(enabled.copy(skip=SkipMode.On));segment(1);runner().run()
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:151:            EventSounds(id,setOf(EnvTag.Rain),setOf(SpecialSound.Knock),false),
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:152:            EventSounds(id+1,setOf(EnvTag.Rain),setOf(SpecialSound.Knock),false)))
app/src/test/java/app/resound/envsound/EnvSound070Test.kt:157:        val many = EnvSoundPresentation.weekly(card, listOf(EventSounds(id, EnvTag.entries.toSet(), SpecialSound.entries.toSet(), false)))
app/src/test/java/app/resound/envsound/EnvSoundSpeedTest.kt:29:            for (skip in SkipMode.entries) for (media in MediaMode.entries) {
app/src/test/java/app/resound/envsound/EnvSoundSpeedTest.kt:30:                val settings = EnvSoundSettings(enabled = true, skip = skip, media = media)
app/src/test/java/app/resound/envsound/EnvSoundSpeedTest.kt:42:                for (skip in SkipMode.entries) for (media in MediaMode.entries) {
app/src/test/java/app/resound/envsound/EnvSoundSpeedTest.kt:43:                    val settings = EnvSoundSettings(enabled = true, skip = skip, media = media)
app/src/test/java/app/resound/transcribe/local/LocalAsrFailure063Test.kt:36:            MutableStateFlow(app.resound.envsound.EnvModelState.NotDownloaded), {}, { error("禁止模型调用") }, {})
app/src/test/java/app/resound/data/TableObservers182Test.kt:58:        MutableStateFlow(EnvModelState.Ready),{}, { error("不运行识别") },{},now={10_000L})
app/src/main/java/app/resound/AppGraph.kt:78:    lateinit var envSound: app.resound.envsound.EnvSoundRepository
app/src/main/java/app/resound/context/LocalSituationLookup.kt:9:import app.resound.envsound.EnvTag
app/src/main/java/app/resound/context/LocalSituationLookup.kt:10:import app.resound.envsound.SpecialSound
app/src/main/java/app/resound/context/LocalSituationLookup.kt:81:                val labels = names<EnvTag>(row.tags).map { it.title } + names<SpecialSound>(row.sounds).map { it.title }
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:13:        EnvTag.Music to (.8f to listOf("Music")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:14:        EnvTag.Keyboard to (.02f to listOf("Computer keyboard", "Typing")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:15:        EnvTag.Rain to (.2f to listOf("Rain", "Rain on surface", "Raindrop")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:16:        EnvTag.Pet to (.1f to listOf("Dog", "Bark", "Cat", "Meow", "Purr")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:17:        EnvTag.Water to (.05f to listOf("Water", "Water tap, faucet", "Pour", "Drip")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:18:        EnvTag.Appliance to (.005f to listOf("Vacuum cleaner", "Air conditioning", "Mechanical fan")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:19:        EnvTag.Footsteps to (.02f to listOf("Walk, footsteps")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:20:        EnvTag.AlarmClock to (.01f to listOf("Alarm clock")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:21:        EnvTag.Traffic to (.3f to listOf("Vehicle", "Traffic noise, roadway noise", "Car", "Train", "Aircraft", "Helicopter", "Vehicle horn, car horn, honking", "Siren")))
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:23:        SpecialSound.BabyCry to (.05f to listOf("Baby cry, infant cry")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:24:        SpecialSound.Knock to (.3f to listOf("Knock")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:25:        SpecialSound.DogBark to (.1f to listOf("Dog", "Bark")),
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:26:        SpecialSound.Siren to (.3f to listOf("Siren", "Police car (siren)", "Ambulance (siren)", "Fire engine, fire truck (siren)")))
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:27:    data class Decision(val verdict: SegmentVerdict, val reason: String?, val tags: Set<EnvTag>, val sounds: Set<SpecialSound>, val skip: Boolean, val markMedia: Boolean)
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:28:    fun decide(evidence: EnvSoundEvidence, settings: EnvSoundSettings, me: Boolean): Decision =
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:32:    fun decide(scores: Map<String, Float>, settings: EnvSoundSettings, me: Boolean, gapScores: Map<String, Float> = emptyMap()): Decision {
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:40:        val skip = settings.enabled && valid && !me && settings.skip != SkipMode.Off && s < speechLimit && h < humanLimit && max(tags.values.flatMap { it.second } + sounds.values.flatMap { it.second }) >= contentLimit
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:41:        val verdict = when { media -> SegmentVerdict.Media; skip && settings.skip == SkipMode.On -> SegmentVerdict.Skipped; skip -> SegmentVerdict.Skippable; else -> SegmentVerdict.Keep }
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:42:        return Decision(verdict, when(verdict) { SegmentVerdict.Media -> "疑似影视"; SegmentVerdict.Keep -> null; else -> if(music >= contentLimit) "音乐" else "环境音" },
app/src/main/java/app/resound/envsound/EnvSoundRules.kt:45:            verdict == SegmentVerdict.Skipped || media && settings.media == MediaMode.SkipTranscribe, media && settings.media == MediaMode.MarkMedia)
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:18:    private val modelState: StateFlow<EnvModelState>,
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:24:) : EnvSoundRepository {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:28:    override val settings = MutableStateFlow(EnvSoundSettings(prefs.getBoolean("enabled", false),
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:29:        enumValue(prefs.getString("skip", null), SkipMode.MarkOnly), enumValue(prefs.getString("media", null), MediaMode.Keep),
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:37:    internal fun presentationSnapshot(start: Long, end: Long): Pair<List<MemoryEventEntity>, Map<Long, EventSounds>> = runBlocking(Dispatchers.IO) {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:42:    internal fun eventSound(id: Long): EventSounds? = runBlocking(Dispatchers.IO) {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:46:    @Synchronized override fun update(settings: EnvSoundSettings) {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:65:        if(model.value != EnvModelState.Ready) { log("环境音 未就绪，照常转写"); return false }
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:84:    override fun flagged(date: LocalDate): Flow<List<SegmentSound>> {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:88:    override fun eventSounds(date: LocalDate): Flow<Map<Long, EventSounds>> {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:96:    override fun eventsWith(tag: EnvTag?, sound: SpecialSound?): Flow<List<Long>> = combine(dao.events().distinctUntilChanged(), dao.observe().distinctUntilChanged()) { events, rows ->
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:100:    override fun hourly(date: LocalDate): Flow<List<HourSound>> {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:107:    override fun stats(days: Int): Flow<EnvStats> {
app/src/main/java/app/resound/envsound/LocalEnvSoundRepository.kt:114:                EnvStats(n, selected.count { it.verdict != "Keep" || it.reportedWrong }, selected.count { it.restored }, selected.count { it.reportedWrong },
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:14:enum class SkipMode(val title: String) { Off("关"), MarkOnly("只标记"), On("开") }
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:17:enum class MediaMode(val title: String) { Keep("照常记录"), MarkMedia("标记为媒体"), SkipTranscribe("不转写") }
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:19:data class EnvSoundSettings(
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:22:    val skip: SkipMode = SkipMode.MarkOnly,
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:23:    val media: MediaMode = MediaMode.Keep,
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:33:enum class EnvTag(val title: String) {
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:39:enum class SpecialSound(val title: String) { BabyCry("婴儿哭声"), Knock("敲门"), DogBark("狗叫"), Siren("警笛") }
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:42:enum class SegmentVerdict {
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:49:    /** 疑似影视外放（按 MediaMode 处理：照常 / 标记为媒体 / 不转写） */
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:53:data class SegmentSound(
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:57:    val verdict: SegmentVerdict,
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:60:    val tags: Set<EnvTag>,
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:61:    val sounds: Set<SpecialSound>,
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:71:data class EventSounds(val eventId: Long, val tags: Set<EnvTag>, val sounds: Set<SpecialSound>, val media: Boolean)
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:74:data class HourSound(val hour: Int, val tag: EnvTag?, val sounds: Set<SpecialSound>, val speechMinutes: Int, val tagMs: Map<EnvTag, Long> = emptyMap())
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:77:data class EnvSpan(val tag: EnvTag, val from: Int, val to: Int, val ms: Long)
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:80:data class EnvStats(val days: Int, val marked: Int, val restored: Int, val reportedWrong: Int, val savedMinutes: Int)
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:82:sealed interface EnvModelState {
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:83:    data object NotDownloaded : EnvModelState
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:84:    data class Downloading(val progress: Float) : EnvModelState
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:85:    data object Ready : EnvModelState
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:86:    data class Failed(val message: String) : EnvModelState
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:89:interface EnvSoundRepository {
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:90:    val settings: StateFlow<EnvSoundSettings>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:91:    fun update(settings: EnvSoundSettings)
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:93:    val model: StateFlow<EnvModelState>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:98:    fun flagged(date: LocalDate): Flow<List<SegmentSound>>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:101:    fun eventSounds(date: LocalDate): Flow<Map<Long, EventSounds>>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:104:    fun eventsWith(tag: EnvTag?, sound: SpecialSound?): Flow<List<Long>>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:106:    fun hourly(date: LocalDate): Flow<List<HourSound>>
app/src/main/java/app/resound/envsound/EnvSoundContract.kt:108:    fun stats(days: Int = 7): Flow<EnvStats>
app/src/main/java/app/resound/envsound/EnvSoundPresentation.kt:39:    internal fun weekly(card: SkillCard, sounds: List<EventSounds>): SkillCard {
app/src/main/java/app/resound/envsound/EnvSoundPresentation.kt:41:        val special = sounds.flatMap { it.sounds }.distinct().sortedBy { it.ordinal }.map { if(it == SpecialSound.Knock) "敲门声" else it.title }
app/src/main/java/app/resound/envsound/EnvSoundPresentation.kt:55:    private fun line(value: EventSounds): String = listOfNotNull(value.tags.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="环境：") { it.title },
app/src/main/java/app/resound/envsound/EnvSoundPresentation.kt:56:            value.sounds.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="期间有") { if(it == SpecialSound.Knock) "敲门声" else it.title }).joinToString("；")
app/src/main/java/app/resound/envsound/EnvSoundAnalysis.kt:38:            checked = EnvSoundRules.decide(scores, protectionSettings, false).verdict != SegmentVerdict.Keep
app/src/main/java/app/resound/envsound/EnvSoundAnalysis.kt:70:        val protectionSettings = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.SkipTranscribe)
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:9:internal fun EnvSoundRow.view() = SegmentSound(segmentId, startAt, endAt, SegmentVerdict.valueOf(verdict), reason,
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:11:internal fun mergeSounds(events: List<MemoryEventEntity>, rows: List<EnvSoundRow>): Map<Long, EventSounds> {
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:20:            if (matches.isNotEmpty()) put(e.id, EventSounds(e.id,
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:21:                matches.flatMap { names<EnvTag>(it.tags) }.toSet(),
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:22:                matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(),
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:27:internal fun hourlySounds(date: LocalDate, segments: List<SegmentEntity>, rows: List<EnvSoundRow>, zone: ZoneId): List<HourSound> {
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:30:    val result = mutableListOf<HourSound>(); var at = start
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:37:            val tagMs = matches.flatMap { r -> names<EnvTag>(r.tags).map { it to minOf(to,r.endAt)-maxOf(from,r.startAt) } }
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:40:            result += HourSound(at.hour, tagMs.maxByOrNull { it.value }?.key, matches.flatMap { names<SpecialSound>(it.sounds) }.toSet(), (millis/60000).toInt(), tagMs)
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:52:internal fun hourTag(speechSec: Int, hour: HourSound?): Pair<EnvTag, Long>? {
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:58:internal fun envSpans(speechSecByHour: List<Int>, hours: Map<Int, HourSound>): List<EnvSpan> {
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:59:    val out = mutableListOf<EnvSpan>()
app/src/main/java/app/resound/envsound/EnvSoundAggregation.kt:64:        else out += EnvSpan(tag, h, h, ms)
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:17:    val state = MutableStateFlow<EnvModelState>(EnvModelState.NotDownloaded)
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:22:        if (verified) state.value = EnvModelState.Ready
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:27:        state.value = EnvModelState.Downloading(0f)
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:36:                        state.value = EnvModelState.Downloading((index + percent / 100) / files.size)
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:45:                    if(percent != null) state.value = EnvModelState.Downloading(percent/100)
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:60:        } catch(e: CancellationException) { state.value = EnvModelState.NotDownloaded; throw e }
app/src/main/java/app/resound/envsound/EnvSoundModel.kt:61:        catch(_: Exception) { state.value = EnvModelState.Failed("模型下载或校验失败，请重试") }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:44:import app.resound.envsound.EnvModelState
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:45:import app.resound.envsound.EnvSoundRepository
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:46:import app.resound.envsound.EnvSoundSettings
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:47:import app.resound.envsound.EnvTag
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:48:import app.resound.envsound.EventSounds
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:49:import app.resound.envsound.MediaMode
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:50:import app.resound.envsound.SegmentSound
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:51:import app.resound.envsound.SegmentVerdict
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:52:import app.resound.envsound.SkipMode
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:70:    private val days = HashMap<LocalDate, StateFlow<Map<Long, EventSounds>>>()
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:71:    fun eventSounds(repo: EnvSoundRepository, date: LocalDate) = days.getOrPut(date) {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:76:private fun EventSounds.line(): String? {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:137:    val skipped = list.count { it.verdict == SegmentVerdict.Skipped && !it.restored }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:138:    val skippable = list.count { it.verdict == SegmentVerdict.Skippable }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:139:    val media = list.count { it.verdict == SegmentVerdict.Media }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:156:private fun FlaggedSheet(repo: EnvSoundRepository, list: List<SegmentSound>, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:176:                        seg.verdict == SegmentVerdict.Skipped -> "已跳过"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:177:                        seg.verdict == SegmentVerdict.Skippable -> "可跳过"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:198:                    val untranscribed = seg.verdict == SegmentVerdict.Skipped || seg.verdict == SegmentVerdict.Media
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:232:        m is EnvModelState.Downloading -> "下载模型中"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:233:        m is EnvModelState.Failed -> "模型下载失败"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:240:fun EnvSoundPage(repo: EnvSoundRepository, bottomPadding: Dp) {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:245:    fun set(v: EnvSoundSettings) = repo.update(v)
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:252:                EnvModelState.NotDownloaded -> if (s.enabled) "准备下载模型（约 27 MB）" else "打开后下载约 27 MB 的识别模型"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:253:                is EnvModelState.Downloading -> "正在下载模型 ${(m.progress * 100).toInt()}%"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:254:                EnvModelState.Ready -> if (s.enabled) "已开启" else "模型已下载"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:255:                is EnvModelState.Failed -> m.message
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:257:            (model as? EnvModelState.Downloading)?.let { d ->
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:263:            if (model is EnvModelState.Failed && s.enabled) {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:272:                    SkipMode.Off -> "全部照常转写"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:273:                    SkipMode.MarkOnly -> "照常转写，只在原话页标出“可跳过”，先看看准不准"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:274:                    SkipMode.On -> "没人说话的片段不转写，省钱省电；在原话页可找回"
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:277:                Segmented(SkipMode.entries.map { it.title }, SkipMode.entries.indexOf(s.skip)) { set(s.copy(skip = SkipMode.entries[it])) }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:278:                stats?.takeIf { s.skip != SkipMode.Off && it.marked > 0 }?.let { st ->
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:291:                Segmented(MediaMode.entries.map { it.title }, MediaMode.entries.indexOf(s.media)) { set(s.copy(media = MediaMode.entries[it])) }
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:331:/** 某天每小时的环境（hour → HourSound）；没开环境音识别或不显示时为空 */
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:333:fun rememberHourSounds(date: LocalDate): Map<Int, app.resound.envsound.HourSound> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:346:fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEnvIcon(tag: EnvTag, color: Color, center: androidx.compose.ui.geometry.Offset, side: Float) {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:357:        EnvTag.Music -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:361:        EnvTag.Keyboard -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:366:        EnvTag.Rain -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:372:        EnvTag.Pet -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:376:        EnvTag.Water -> drawPath(androidx.compose.ui.graphics.Path().apply {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:384:        EnvTag.Appliance -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:391:        EnvTag.Footsteps -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:395:        EnvTag.AlarmClock -> {
app/src/main/java/app/resound/ui/envsound/EnvSoundViews.kt:401:        EnvTag.Traffic -> {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:137:                        onEnv: (app.resound.envsound.EnvSpan) -> Unit = {}) {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:208:private fun HourBars(r: DayRhythm, date: LocalDate, rules: List<QuietRule>, onHour: (Int) -> Unit, onEnv: (app.resound.envsound.EnvSpan) -> Unit) {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:219:    var lit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<app.resound.envsound.EnvSpan?>(null) }
app/src/main/java/app/resound/ui/today/RhythmViews.kt:311:private fun envHint(h: Int, spans: List<app.resound.envsound.EnvSpan>, env: Map<Int, app.resound.envsound.HourSound>): List<String> =
app/src/main/java/app/resound/ui/today/RhythmViews.kt:320:private fun EnvIcons(spans: List<app.resound.envsound.EnvSpan>, lit: app.resound.envsound.EnvSpan?,
app/src/main/java/app/resound/ui/today/RhythmViews.kt:321:                     onLit: (app.resound.envsound.EnvSpan?) -> Unit, onPick: (app.resound.envsound.EnvSpan) -> Unit) {
app/src/main/java/app/resound/ui/today/RhythmViews.kt:326:    fun spanAt(x: Float): app.resound.envsound.EnvSpan? {
```

## recordings/RecordingArchiveContract.kt

类型：RecordingRange, RecordingPackOptions, RecordingPackEstimate, RecordingPackState, RecordingPacker

```text
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:41:    fun foreground(c: Context, id: String, state: RecordingPackState?): ForegroundInfo {
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:43:        val percent = (((state as? RecordingPackState.Running)?.fraction ?: 0f) * 100).toInt()
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:49:    fun finished(c: Context, id: String, state: RecordingPackState?) {
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:50:        if (state !is RecordingPackState.Done && state !is RecordingPackState.Failed) return
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:53:            .setContentTitle(if (state is RecordingPackState.Done) "录音包已保存" else "录音打包失败")
app/src/main/java/app/resound/recordings/RecordingPackWorker.kt:54:            .setContentText(if (state is RecordingPackState.Done) state.name else (state as RecordingPackState.Failed).message)
app/src/main/java/app/resound/AppGraph.kt:70:    lateinit var recordingPacker: app.resound.recordings.RecordingPacker
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:25:) : RecordingPacker {
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:28:    private val mutableCurrent = MutableStateFlow<Pair<String, RecordingPackState>?>(null)
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:39:    override suspend fun estimate(range: RecordingRange, options: RecordingPackOptions) = withContext(Dispatchers.IO) {
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:42:    @Synchronized override fun start(range: RecordingRange, options: RecordingPackOptions): String {
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:75:            "done" -> RecordingPackState.Done(j.getString("name"), j.getLong("bytes"), j.getInt("total"))
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:76:            "failed" -> RecordingPackState.Failed(j.getString("message"))
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:77:            "cancelled" -> RecordingPackState.Cancelled
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:78:            else -> RecordingPackState.Running(j.optDouble("fraction", 0.0).toFloat(), j.optInt("done"), j.optInt("total"))
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:87:            val range = RecordingRange(LocalDate.parse(request.getString("from")), LocalDate.parse(request.getString("to")),
app/src/main/java/app/resound/recordings/AndroidRecordingPacker.kt:90:            val options = RecordingPackOptions(request.getBoolean("text"), request.getBoolean("skip"))
app/src/main/java/app/resound/ui/today/ListenSheet.kt:60:    var range by remember { mutableStateOf(app.resound.recordings.RecordingRange(today, today, java.time.LocalTime.of(0, 0), java.time.LocalTime.now().withSecond(0).withNano(0))) }
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:50:        assertEquals("涛声录音 2026-10-28 至 2026-10-29.zip", RecordingZip.name(RecordingRange(today.minusDays(1), today)))
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:51:        assertEquals("涛声录音 2026-10-29.zip", RecordingZip.name(RecordingRange(today, today)))
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:55:        val selected = RecordingZip.select(rows, RecordingRange(today, today), RecordingPackOptions(skipMedia = true))
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:56:        assertEquals(RecordingPackEstimate(1, 1, 5, 192000), RecordingZip.estimate(selected))
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:61:        val hit = RecordingRange(today, today, LocalTime.of(14, 0), LocalTime.of(14, 10))
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:62:        assertEquals(listOf(1L), RecordingZip.select(rows, hit, RecordingPackOptions()).map { it.id })
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:63:        assertTrue(RecordingZip.select(rows, hit.copy(fromTime = LocalTime.of(14, 10), toTime = LocalTime.of(23, 59)), RecordingPackOptions()).isEmpty())
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:156:            val id = packer.start(RecordingRange(today, today), RecordingPackOptions())
app/src/test/java/app/resound/recordings/RecordingArchive072Test.kt:158:            assertTrue(provider.deleted); assertFalse(output.exists()); assertEquals(RecordingPackState.Cancelled, packer.current.value?.second)
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:15:data class RecordingRange(val from: LocalDate, val to: LocalDate,
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:22:data class RecordingPackOptions(
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:29:data class RecordingPackEstimate(val days: Int, val segments: Int, val bytes: Long, val audioMs: Long)
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:31:sealed interface RecordingPackState {
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:32:    data class Running(val fraction: Float, val done: Int, val total: Int) : RecordingPackState
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:34:    data class Done(val name: String, val bytes: Long, val segments: Int) : RecordingPackState
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:35:    data class Failed(val message: String) : RecordingPackState
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:36:    data object Cancelled : RecordingPackState
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:39:interface RecordingPacker {
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:42:    suspend fun estimate(range: RecordingRange, options: RecordingPackOptions): RecordingPackEstimate
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:44:    fun start(range: RecordingRange, options: RecordingPackOptions): String
app/src/main/java/app/resound/recordings/RecordingArchiveContract.kt:47:    val current: StateFlow<Pair<String, RecordingPackState>?>
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:34:import app.resound.recordings.RecordingPackEstimate
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:35:import app.resound.recordings.RecordingPackOptions
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:36:import app.resound.recordings.RecordingPackState
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:37:import app.resound.recordings.RecordingPacker
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:38:import app.resound.recordings.RecordingRange
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:58:        is RecordingPackState.Running -> "正在打包 ${(st.fraction * 100).toInt()}%"
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:66:private fun RecordingPackSheet(packer: RecordingPacker, onDismiss: () -> Unit) {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:76:        ("今天" to RecordingRange(today, today)).takeIf { today in days },
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:77:        ("昨天" to RecordingRange(today.minusDays(1), today.minusDays(1))).takeIf { today.minusDays(1) in days },
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:78:        ("最近 3 天" to RecordingRange(today.minusDays(2), today)).takeIf { days.size > 1 },
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:79:        ("全部 ${days.size} 天" to RecordingRange(oldest, today)).takeIf { days.size > 3 },
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:82:    var custom by remember { mutableStateOf(RecordingRange(today, today, java.time.LocalTime.of(0, 0), java.time.LocalTime.now().withSecond(0).withNano(0))) }
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:88:    val estimate by produceState<RecordingPackEstimate?>(null, pick, skipMedia) {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:89:        value = pick?.let { runCatching { packer.estimate(it, RecordingPackOptions(withText, skipMedia)) }.getOrNull() }
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:92:    RsSheet("导出录音包", "原始录音（OGG）按天分文件夹打成一个压缩包，保存到 下载 › Resound › 录音包", onDismiss = onDismiss, fixedHeight = mine?.second !is RecordingPackState.Running && started == null) {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:93:        when (val st = mine?.second.takeIf { started != null || it is RecordingPackState.Running }) {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:94:            is RecordingPackState.Running -> {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:105:            is RecordingPackState.Done -> {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:120:            is RecordingPackState.Failed -> {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:125:            RecordingPackState.Cancelled -> {
app/src/main/java/app/resound/ui/transcript/RecordingPackSheet.kt:155:                    pick?.let { started = packer.start(it, RecordingPackOptions(withText, skipMedia)) }
app/src/main/java/app/resound/ui/transcript/OriginalAudioCard.kt:142:    var range by remember { mutableStateOf(app.resound.recordings.RecordingRange(today, today, java.time.LocalTime.of(0, 0), java.time.LocalTime.now().withSecond(0).withNano(0))) }
app/src/main/java/app/resound/recordings/RecordingSources.kt:54:    fun select(all: List<RecordingSource>, range: RecordingRange, options: RecordingPackOptions): List<RecordingSource> {
app/src/main/java/app/resound/recordings/RecordingSources.kt:60:    fun estimate(rows: List<RecordingSource>) = RecordingPackEstimate(rows.map { it.day }.distinct().size,
app/src/main/java/app/resound/recordings/RecordingSources.kt:62:    fun name(range: RecordingRange) = if (range.wholeDays) "涛声录音 ${range.from}" + (if (range.from == range.to) "" else " 至 ${range.to}") + ".zip"
```

## redo/RedoContract.kt

类型：AsrOption, LlmOption, AsrEstimate, AsrChooser, RedoScope, RedoRequest, RedoEstimate, RedoJobState, RedoJob, RedoService

```text
app/src/main/java/app/resound/AppGraph.kt:74:    lateinit var redo: app.resound.redo.RedoService
app/src/main/java/app/resound/redo/RedoEngine.kt:23:    private val chooser: AsrChooser,
app/src/main/java/app/resound/redo/RedoEngine.kt:36:    suspend fun select(request: RedoRequest): RedoSelection {
app/src/main/java/app/resound/redo/RedoEngine.kt:39:        val picked = mergedRanges(if (request.scope == RedoScope.TranscribeOnly) explicit.map { RedoRange(it.segment.startAt, it.segment.endAt) }
app/src/main/java/app/resound/redo/RedoEngine.kt:41:        val ranges = if (request.scope == RedoScope.TranscribeOnly) picked else joinNearby(picked, chosen.map { it.id }.toSet())
app/src/main/java/app/resound/redo/RedoEngine.kt:42:        val events = if (request.scope == RedoScope.TranscribeOnly) emptyList() else ranges.flatMap { dao.events(it.start, it.end) }.distinctBy { it.id }
app/src/main/java/app/resound/redo/RedoEngine.kt:43:        val segments = if (request.scope == RedoScope.TranscribeOnly) explicit else db.transcribe().exportSegments(ranges.flatMap { dao.segments(it.start, it.end) }.map { it.id }.distinct())
app/src/main/java/app/resound/redo/RedoEngine.kt:66:    suspend fun selectMerge(request: RedoRequest): MergeSelection {
app/src/main/java/app/resound/redo/RedoEngine.kt:78:    suspend fun estimate(request: RedoRequest): RedoEstimate {
app/src/main/java/app/resound/redo/RedoEngine.kt:79:        if (request.scope == RedoScope.Merge) {
app/src/main/java/app/resound/redo/RedoEngine.kt:81:            return RedoEstimate(
app/src/main/java/app/resound/redo/RedoEngine.kt:91:        val asr = if (!request.scope.transcribes) AsrEstimate(0, 0.0) else chooser.estimate(duration, request.asr ?: chooser.lastChoice.value)
app/src/main/java/app/resound/redo/RedoEngine.kt:93:        return RedoEstimate(s.events.size, s.segments.size, duration, expired, s.segments.size - available.size,
app/src/main/java/app/resound/redo/RedoEngine.kt:94:            s.events.count { it.editedAt != null }, asr.etaMillis?.let { it + if (request.scope == RedoScope.TranscribeOnly) 0 else 15_000L * s.ranges.size },
app/src/main/java/app/resound/redo/RedoEngine.kt:95:            if (request.scope == RedoScope.TranscribeOnly) asr.costYuan else null)
app/src/main/java/app/resound/redo/RedoEngine.kt:97:    private fun selectors(s: RedoSelection, scope: RedoScope): Map<String, String> = linkedMapOf<String, String>().apply {
app/src/main/java/app/resound/redo/RedoEngine.kt:98:        if (scope != RedoScope.TranscribeOnly) {
app/src/main/java/app/resound/redo/RedoEngine.kt:121:    suspend fun run(id: String, request: RedoRequest, checkpoint: () -> Unit, progress: suspend (String, Float) -> Unit) {
app/src/main/java/app/resound/redo/RedoEngine.kt:122:        if (request.scope == RedoScope.Merge) { runMerge(id, request, checkpoint, progress); return }
app/src/main/java/app/resound/redo/RedoEngine.kt:191:        if (request.scope == RedoScope.TranscribeOnly) {
app/src/main/java/app/resound/redo/RedoEngine.kt:238:    private suspend fun runMerge(id: String, request: RedoRequest, checkpoint: () -> Unit, progress: suspend (String, Float) -> Unit) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:21:    override val asr: AsrChooser,
app/src/main/java/app/resound/redo/AndroidRedoService.kt:24:    private val options: () -> List<LlmOption>,
app/src/main/java/app/resound/redo/AndroidRedoService.kt:29:) : RedoService {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:39:        if (jobs.value.any { it.state is RedoJobState.Running }) enqueue()
app/src/main/java/app/resound/redo/AndroidRedoService.kt:53:                            val fresh = jobs.value.filter { it.state is RedoJobState.Running || it.createdAt >= now() - 86_400_000 }.map {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:55:                                if (state is RedoJobState.Done) it.copy(state = state.copy(undoable = it.id == undoId)) else it
app/src/main/java/app/resound/redo/AndroidRedoService.kt:58:                            nextExpiry = (fresh.filter { it.state !is RedoJobState.Running }.map { BackgroundRefreshPolicy.redoDeadline(it.createdAt) } + listOfNotNull(nextExpiry)).minOrNull()
app/src/main/java/app/resound/redo/AndroidRedoService.kt:67:    override suspend fun estimate(request: RedoRequest): RedoEstimate {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:71:    @Synchronized override fun start(request: RedoRequest): String {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:78:        publish(jobs.value + RedoJob(id, when (request.scope) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:79:            RedoScope.TranscribeOnly -> "重新转写"
app/src/main/java/app/resound/redo/AndroidRedoService.kt:80:            RedoScope.Merge -> "合并事件"
app/src/main/java/app/resound/redo/AndroidRedoService.kt:83:            RedoJobState.Running("排队中", 0f), now()))
app/src/main/java/app/resound/redo/AndroidRedoService.kt:84:        try { enqueue() } catch (_: Exception) { update(id, RedoJobState.Failed("无法安排后台重做，请重试")) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:88:        if (job(id)?.state !is RedoJobState.Running) return
app/src/main/java/app/resound/redo/AndroidRedoService.kt:89:        update(id, RedoJobState.Cancelled)
app/src/main/java/app/resound/redo/AndroidRedoService.kt:94:        if (job(id)?.state is RedoJobState.Running) return
app/src/main/java/app/resound/redo/AndroidRedoService.kt:98:    @Synchronized private fun update(id: String, state: RedoJobState) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:99:        if (job(id)?.state !is RedoJobState.Running) return
app/src/main/java/app/resound/redo/AndroidRedoService.kt:103:        if (jobs.value.any { it.state is RedoJobState.Running }) return@withLock Result.failure(IllegalStateException("请等待重做结束后再撤销"))
app/src/main/java/app/resound/redo/AndroidRedoService.kt:106:        if (result.isSuccess) synchronized(this) { publish(jobs.value.map { if (it.id == id) it.copy(state = RedoJobState.Undone) else it }) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:109:    internal suspend fun drain(progress: suspend (RedoJob) -> Unit) = gate.withLock {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:111:            val next = synchronized(this) { jobs.value.firstOrNull { it.state is RedoJobState.Running } } ?: break
app/src/main/java/app/resound/redo/AndroidRedoService.kt:117:                            fun checkpoint() { if (job(next.id)?.state !is RedoJobState.Running) throw CancellationException("已取消") }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:124:                                update(next.id, RedoJobState.Running(stage, fraction)); job(next.id)?.let { progress(it) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:128:                                publish(jobs.value.map { if (it.id == next.id) it.copy(state = RedoJobState.Done(
app/src/main/java/app/resound/redo/AndroidRedoService.kt:130:                                        RedoScope.TranscribeOnly -> "原话已更新，可以再去重新整理"
app/src/main/java/app/resound/redo/AndroidRedoService.kt:131:                                        RedoScope.Merge -> "合并完成"
app/src/main/java/app/resound/redo/AndroidRedoService.kt:134:                                    else if (it.state is RedoJobState.Done) it.copy(state = it.state.copy(undoable = false)) else it })
app/src/main/java/app/resound/redo/AndroidRedoService.kt:138:                    catch (e: Exception) { update(next.id, RedoJobState.Failed(when (e) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:150:            if (db.redoData().snapshotMeta()?.let { it.jobId == next.id && it.applied } == true && job(next.id)?.state !is RedoJobState.Done) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:151:                synchronized(this) { publish(jobs.value.map { if (it.id == next.id) it.copy(state = RedoJobState.Done("重做完成", true)) else it }) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:156:    private fun publish(value: List<RedoJob>) {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:162:                is RedoJobState.Running -> put("state", "running")
app/src/main/java/app/resound/redo/AndroidRedoService.kt:163:                is RedoJobState.Done -> { put("state", "done"); put("message", s.summary); put("undo", s.undoable) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:164:                is RedoJobState.Failed -> { put("state", "failed"); put("message", s.message) }
app/src/main/java/app/resound/redo/AndroidRedoService.kt:165:                RedoJobState.Cancelled -> put("state", "cancelled")
app/src/main/java/app/resound/redo/AndroidRedoService.kt:166:                RedoJobState.Undone -> put("state", "undone")
app/src/main/java/app/resound/redo/AndroidRedoService.kt:172:    private fun load(): List<RedoJob> {
app/src/main/java/app/resound/redo/AndroidRedoService.kt:177:            val request = RedoRequest(ids("events"), ids("segments"), RedoScope.valueOf(j.getString("scope")),
app/src/main/java/app/resound/redo/AndroidRedoService.kt:180:                "done" -> RedoJobState.Done(j.getString("message"), j.optBoolean("undo"))
app/src/main/java/app/resound/redo/AndroidRedoService.kt:181:                "failed" -> RedoJobState.Failed(j.getString("message"))
app/src/main/java/app/resound/redo/AndroidRedoService.kt:182:                "cancelled" -> RedoJobState.Cancelled
app/src/main/java/app/resound/redo/AndroidRedoService.kt:183:                "undone" -> RedoJobState.Undone
app/src/main/java/app/resound/redo/AndroidRedoService.kt:184:                else -> RedoJobState.Running("等待恢复", 0f)
app/src/main/java/app/resound/redo/AndroidRedoService.kt:186:            RedoJob(j.getString("id"), j.getString("title"), request, state, j.getLong("at"))
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:12:    private val price: () -> Double?) : AsrChooser {
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:14:    private val mutableOptions = MutableStateFlow(emptyList<AsrOption>())
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:22:            AsrOption(source, source.title, ready, if (ready) null else if (source == TranscribeSource.Local) "模型未下载" else "服务未配置")
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:43:    override fun estimate(durationMs: Long, source: TranscribeSource): AsrEstimate {
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:53:        return AsrEstimate(eta, cost)
app/src/main/java/app/resound/redo/AndroidAsrChooser.kt:60:    LlmOption(preset.id, preset.name, ready, note = if (ready) null else "未填密钥或服务未配置")
app/src/main/java/app/resound/redo/RedoWorker.kt:32:    fun foreground(context: Context, job: RedoJob?): ForegroundInfo {
app/src/main/java/app/resound/redo/RedoWorker.kt:34:        val state = job?.state as? RedoJobState.Running
app/src/main/java/app/resound/redo/RedoWorker.kt:43:    fun finished(context: Context, job: RedoJob) {
app/src/main/java/app/resound/redo/RedoWorker.kt:45:            is RedoJobState.Done -> state.summary
app/src/main/java/app/resound/redo/RedoWorker.kt:46:            is RedoJobState.Failed -> state.message
app/src/main/java/app/resound/redo/RedoWorker.kt:54:            RedoScope.Merge -> "合并"
app/src/main/java/app/resound/redo/RedoWorker.kt:58:            .setSmallIcon(R.drawable.ic_notify).setContentTitle(if (job.state is RedoJobState.Done) "${title}完成" else "${title}失败")
app/src/main/java/app/resound/redo/RedoContract.kt:11:data class AsrOption(val source: TranscribeSource, val title: String, val ready: Boolean, val note: String? = null)
app/src/main/java/app/resound/redo/RedoContract.kt:14:data class LlmOption(val presetId: String, val title: String, val ready: Boolean, val checked: Boolean = false, val note: String? = null)
app/src/main/java/app/resound/redo/RedoContract.kt:17:data class AsrEstimate(val etaMillis: Long?, val costYuan: Double?)
app/src/main/java/app/resound/redo/RedoContract.kt:20:interface AsrChooser {
app/src/main/java/app/resound/redo/RedoContract.kt:21:    val options: StateFlow<List<AsrOption>>
app/src/main/java/app/resound/redo/RedoContract.kt:25:    fun estimate(durationMs: Long, source: TranscribeSource): AsrEstimate
app/src/main/java/app/resound/redo/RedoContract.kt:28:enum class RedoScope {
app/src/main/java/app/resound/redo/RedoContract.kt:40:val RedoScope.transcribes: Boolean get() = this == RedoScope.TranscribeAndDigest || this == RedoScope.TranscribeOnly
app/src/main/java/app/resound/redo/RedoContract.kt:43:val RedoScope.digests: Boolean get() = this == RedoScope.DigestOnly || this == RedoScope.TranscribeAndDigest || this == RedoScope.Merge
app/src/main/java/app/resound/redo/RedoContract.kt:49:data class RedoRequest(
app/src/main/java/app/resound/redo/RedoContract.kt:52:    val scope: RedoScope,
app/src/main/java/app/resound/redo/RedoContract.kt:57:data class RedoEstimate(
app/src/main/java/app/resound/redo/RedoContract.kt:76:sealed interface RedoJobState {
app/src/main/java/app/resound/redo/RedoContract.kt:78:    data class Running(val stage: String, val fraction: Float) : RedoJobState
app/src/main/java/app/resound/redo/RedoContract.kt:80:    data class Done(val summary: String, val undoable: Boolean) : RedoJobState
app/src/main/java/app/resound/redo/RedoContract.kt:81:    data class Failed(val message: String) : RedoJobState
app/src/main/java/app/resound/redo/RedoContract.kt:82:    data object Cancelled : RedoJobState
app/src/main/java/app/resound/redo/RedoContract.kt:83:    data object Undone : RedoJobState
app/src/main/java/app/resound/redo/RedoContract.kt:86:data class RedoJob(val id: String, val title: String, val request: RedoRequest, val state: RedoJobState, val createdAt: Long)
app/src/main/java/app/resound/redo/RedoContract.kt:88:interface RedoService {
app/src/main/java/app/resound/redo/RedoContract.kt:89:    val asr: AsrChooser
app/src/main/java/app/resound/redo/RedoContract.kt:90:    val llmOptions: StateFlow<List<LlmOption>>
app/src/main/java/app/resound/redo/RedoContract.kt:91:    suspend fun estimate(request: RedoRequest): RedoEstimate
app/src/main/java/app/resound/redo/RedoContract.kt:93:    val jobs: StateFlow<List<RedoJob>>
app/src/main/java/app/resound/redo/RedoContract.kt:95:    fun start(request: RedoRequest): String
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:26:    private val chooser = object : AsrChooser {
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:27:        override val options = MutableStateFlow(TranscribeSource.entries.map { AsrOption(it, it.title, true) })
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:30:        override fun estimate(durationMs: Long, source: TranscribeSource) = AsrEstimate(durationMs / 2, 0.0)
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:57:    private suspend fun run(e: RedoEngine, id: String = "one", request: RedoRequest = RedoRequest(listOf(1, 2), scope = RedoScope.Merge)) =
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:81:        try { run(engine(), request = RedoRequest(listOf(1), scope = RedoScope.Merge)); fail() }
app/src/test/java/app/resound/redo/RedoMerge105Test.kt:152:        val estimate = e.estimate(RedoRequest(listOf(1, 2), scope = RedoScope.Merge))
app/src/test/java/app/resound/redo/Redo071Test.kt:28:    private val chooser = object : AsrChooser {
app/src/test/java/app/resound/redo/Redo071Test.kt:29:        override val options = MutableStateFlow(TranscribeSource.entries.map { AsrOption(it, it.title, true) })
app/src/test/java/app/resound/redo/Redo071Test.kt:32:        override fun estimate(durationMs: Long, source: TranscribeSource) = AsrEstimate(durationMs / 2, 0.0)
app/src/test/java/app/resound/redo/Redo071Test.kt:59:    private suspend fun run(e: RedoEngine, id: String = "one", request: RedoRequest = RedoRequest(eventIds = listOf(1), scope = RedoScope.DigestOnly)) = e.run(id, request, {}, { _, _ -> })
app/src/test/java/app/resound/redo/Redo071Test.kt:74:        val e = engine(); val request = RedoRequest(listOf(1, 2), scope = RedoScope.TranscribeAndDigest, asr = TranscribeSource.Local)
app/src/test/java/app/resound/redo/Redo071Test.kt:81:        try { run(failed, "failed", RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly, asr = TranscribeSource.Custom)); fail() }
app/src/test/java/app/resound/redo/Redo071Test.kt:99:        run(e, request = RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
app/src/test/java/app/resound/redo/Redo071Test.kt:107:        run(e, "two", RedoRequest(listOf(newId), scope = RedoScope.DigestOnly))
app/src/test/java/app/resound/redo/Redo071Test.kt:114:        try { run(e, request = RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest)); fail() } catch (_: CancellationException) { }
app/src/test/java/app/resound/redo/Redo071Test.kt:146:        e.run("split", RedoRequest(listOf(1), scope = RedoScope.DigestOnly, llmPresetId = "split-preset"), {}, { text, _ -> progress += text })
app/src/test/java/app/resound/redo/Redo071Test.kt:161:        run(engine(onRecognize = { used = it }), request = RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest, asr = TranscribeSource.Custom, llmPresetId = "custom"))
app/src/test/java/app/resound/redo/Redo071Test.kt:195:        val service = AndroidRedoService(context, db, chooser, engine(), scope, { listOf(LlmOption("x", "x", true)) }, { "x" }, enqueue = {}, now = { 100 })
app/src/test/java/app/resound/redo/Redo071Test.kt:196:        val a = service.start(RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
app/src/test/java/app/resound/redo/Redo071Test.kt:197:        val b = service.start(RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
app/src/test/java/app/resound/redo/Redo071Test.kt:198:        val restored = AndroidRedoService(context, db, chooser, engine(), scope, { listOf(LlmOption("x", "x", true)) }, { "x" }, enqueue = {}, now = { 100 })
app/src/test/java/app/resound/redo/Redo071Test.kt:201:        assertTrue(restored.jobs.value.all { it.state is RedoJobState.Done })
app/src/test/java/app/resound/redo/Redo071Test.kt:202:        assertFalse((restored.job(a)!!.state as RedoJobState.Done).undoable)
app/src/test/java/app/resound/redo/Redo071Test.kt:224:        e.run("near", RedoRequest(listOf(1, 2, 4), scope = RedoScope.DigestOnly), {}, { _, _ -> })
app/src/test/java/app/resound/redo/Redo071Test.kt:230:        val e = engine().estimate(RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest))
app/src/main/java/app/resound/ui/redo/RedoViews.kt:36:import app.resound.redo.AsrChooser
app/src/main/java/app/resound/ui/redo/RedoViews.kt:37:import app.resound.redo.AsrEstimate
app/src/main/java/app/resound/ui/redo/RedoViews.kt:38:import app.resound.redo.RedoEstimate
app/src/main/java/app/resound/ui/redo/RedoViews.kt:39:import app.resound.redo.RedoJob
app/src/main/java/app/resound/ui/redo/RedoViews.kt:40:import app.resound.redo.RedoJobState
app/src/main/java/app/resound/ui/redo/RedoViews.kt:41:import app.resound.redo.RedoRequest
app/src/main/java/app/resound/ui/redo/RedoViews.kt:42:import app.resound.redo.RedoScope
app/src/main/java/app/resound/ui/redo/RedoViews.kt:43:import app.resound.redo.RedoService
app/src/main/java/app/resound/ui/redo/RedoViews.kt:69:fun AsrChoice(chooser: AsrChooser, durationMs: Long, selected: TranscribeSource, onSelect: (TranscribeSource) -> Unit) {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:75:            val est: AsrEstimate? = remember(o.source, durationMs) { if (o.ready) chooser.estimate(durationMs, o.source) else null }
app/src/main/java/app/resound/ui/redo/RedoViews.kt:122:    var scope by remember { mutableStateOf(if (merge) RedoScope.Merge else if (segmentsOnly) RedoScope.TranscribeOnly else RedoScope.DigestOnly) }
app/src/main/java/app/resound/ui/redo/RedoViews.kt:130:    fun request() = RedoRequest(eventIds, segmentIds, scope, if (needsAsr) asr else null, if (scope != RedoScope.TranscribeOnly) llm else null)
app/src/main/java/app/resound/ui/redo/RedoViews.kt:131:    val estimate by produceState<RedoEstimate?>(null, scope, asr, llm) { value = runCatching { repo.estimate(request()) }.getOrNull() }
app/src/main/java/app/resound/ui/redo/RedoViews.kt:141:            Segmented(listOf("只重新整理", "重新转写并整理"), if (scope == RedoScope.DigestOnly) 0 else 1) {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:142:                scope = if (it == 0) RedoScope.DigestOnly else RedoScope.TranscribeAndDigest
app/src/main/java/app/resound/ui/redo/RedoViews.kt:144:            Text(if (scope == RedoScope.DigestOnly) "原话不动，用选的模型把这段时间重新整理成事件" else "先用选的方式把录音重新转成文字，再重新整理",
app/src/main/java/app/resound/ui/redo/RedoViews.kt:151:        if (scope != RedoScope.TranscribeOnly) {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:177:                if (scope != RedoScope.TranscribeOnly && e.editedEvents > 0) add("${e.editedEvents} 件事你手动改过，${if (merge) "合并" else "重做"}会覆盖你的修改")
app/src/main/java/app/resound/ui/redo/RedoViews.kt:190:                if (scope != RedoScope.TranscribeOnly) Text("重点和已确认、已完成的待办会保留；${if (merge) "合并" else "做"}完可以撤销", style = RsType.caption, color = c.textTertiary)
app/src/main/java/app/resound/ui/redo/RedoViews.kt:195:            (scope == RedoScope.TranscribeOnly || llm != null) && !allExpired &&
app/src/main/java/app/resound/ui/redo/RedoViews.kt:215:private fun JobBody(repo: RedoService, job: RedoJob, onClose: () -> Unit) {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:220:        is RedoJobState.Running -> {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:231:        is RedoJobState.Done -> {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:236:                if (st.undoable) ActionButton(if (job.request.scope == RedoScope.Merge) "撤销合并" else "撤销重做", ActionKind.Secondary, Modifier.weight(1f)) {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:242:        is RedoJobState.Failed -> {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:248:        RedoJobState.Cancelled -> {
app/src/main/java/app/resound/ui/redo/RedoViews.kt:253:        RedoJobState.Undone -> {
```

