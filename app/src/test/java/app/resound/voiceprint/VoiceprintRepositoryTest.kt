package app.resound.voiceprint

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class VoiceprintRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: LocalVoiceprintRepository
    private val scheduled = Channel<Unit>(Channel.UNLIMITED)
    private val audio = mutableMapOf<String, ShortArray>()
    private val files = mutableListOf<File>()
    private var computations = 0
    private var failDecode = false
    private var clock = 100_000_000L
    private fun vector(index: Int) = FloatArray(192).apply { this[index] = 1f }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = createRepository()
    }
    private fun createRepository() = LocalVoiceprintRepository(db, scope, { file ->
        if (failDecode) error("解码失败")
        audio.getValue(file.path)
    }, { SpeakerEmbedding { pcm -> computations++; vector(if (pcm.first() > 0) 0 else 1) } }, { scheduled.trySend(Unit); Unit }, { clock })
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); files.forEach { it.deleteRecursively() }
    }
    private suspend fun enroll() {
        repeat(3) { repository.enrollPcm(ShortArray(160_000) { 1000 }).getOrThrow(); scheduled.receive() }
    }
    private suspend fun segment(start: Long = 1000, sign: Int = 1, seconds: Int = 5): Pair<SegmentEntity, List<TranscriptLineEntity>> {
        val file = File.createTempFile("voiceprint-test-", ".ogg", context.cacheDir).also { files += it }
        audio[file.path] = ShortArray(seconds * 16000) { (sign * 1000).toShort() }
        db.capture().segment(SegmentEntity(startAt = start, endAt = start + seconds * 1000, sourceId = "mic", path = file.path,
            sizeBytes = 1, status = "Done", completedAt = 1))
        val segment = db.transcribe().observeSegments().first().first { it.path == file.path }
        db.transcribe().insertLines(listOf(
            TranscriptLineEntity(segmentId = segment.id, startAt = start, endAt = start + seconds * 500, text = "第一句", speakerTag = "0"),
            TranscriptLineEntity(segmentId = segment.id, startAt = start + seconds * 500, endAt = start + seconds * 1000, text = "第二句", speakerTag = "0")))
        return segment to db.voiceprint().lines(segment.id)
    }
    private suspend fun action(block: () -> Unit) { block(); withTimeout(5000) { scheduled.receive() } }

    @Test fun multiplePeopleKeepCorrectionsRenameAndDeleteWithoutLosingAudio() = runBlocking {
        enroll()
        val person = repository.addPerson("同事").getOrThrow()
        repeat(3) { repository.enrollPerson(person, ShortArray(160_000) { -1000 }).getOrThrow(); scheduled.receive() }
        val (other, lines) = segment(sign = -1)
        repository.run()
        assertTrue(db.voiceprint().lines(other.id).all { it.speakerName == "同事" && it.speakerPersonId == person })
        assertFalse(db.voiceprint().allSamples().any { it.source == "auto" })
        action { repository.assignPerson(lines.first().id, 0) }
        repository.run()
        assertTrue(db.voiceprint().lines(other.id).all { it.speakerName == "我" && it.speakerManual })
        action { repository.assignPerson(lines.first().id, person) }
        action { repository.renamePerson(person, "老王") }
        assertEquals("老王", db.voiceprint().line(lines.first().id)!!.speakerName)
        action { repository.clearIdentity(lines.first().id) }
        repository.run()
        assertNull(db.voiceprint().line(lines.first().id)!!.speakerName)
        action { repository.assignPerson(lines.first().id, person) }
        action { repository.deletePerson(person) }
        assertTrue(db.voiceprint().people().isEmpty())
        assertTrue(db.voiceprint().allSamples().all { it.personId == 0L })
        assertNull(db.voiceprint().line(lines.first().id)!!.speakerName)
        assertTrue(File(other.path).isFile)
        assertEquals(2, db.voiceprint().lines(other.id).size)
    }

    @Test fun expiredAudioStillAllowsManualIdentityWithoutInventingSample() = runBlocking {
        val person = repository.addPerson("家人").getOrThrow()
        val (segment, lines) = segment()
        File(segment.path).delete()
        action { repository.assignPerson(lines.first().id, person) }
        assertEquals("家人", db.voiceprint().line(lines.first().id)!!.speakerName)
        assertTrue(db.voiceprint().line(lines.first().id)!!.speakerManual)
        assertTrue(db.voiceprint().allSamples().isEmpty())
    }

    @Test fun enteringNameCreatesPersonAndReusesItForNextRecording() = runBlocking {
        val (first, lines) = segment()
        repository.nameAndAssign(lines.first().id, "小王").getOrThrow(); scheduled.receive()
        val person = db.voiceprint().people().single()
        assertEquals("小王", db.voiceprint().line(lines.first().id)!!.speakerName)
        assertEquals(person.id,db.voiceprint().allSamples().single().personId)
        val (second, next) = segment(start=9000)
        repository.nameAndAssign(next.first().id, " 小王 ").getOrThrow(); scheduled.receive()
        assertEquals(1, db.voiceprint().people().size)
        assertEquals(2, db.voiceprint().allSamples().size)
        assertEquals(person.id,db.voiceprint().line(next.first().id)!!.speakerPersonId)
        assertTrue(File(first.path).isFile && File(second.path).isFile)
    }

    @Test fun notReadySkipsModelThenScoresEachGroupOnceAndSurvivesColdStart() = runBlocking {
        val (segment, _) = segment()
        assertFalse(repository.run()); assertEquals(0, computations)
        assertNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
        enroll()
        val cold = createRepository()
        assertTrue(cold.isReady()) // 不能用尚未刷新的 StateFlow 初值做整理门禁。
        assertFalse(cold.run()); assertEquals(5, computations)
        val lines = db.voiceprint().lines(segment.id)
        assertTrue(lines.all { it.speakerName == "我" && it.speakerScore == 1f })
        assertEquals(clock, db.transcribe().segment(segment.id).voiceprintProcessedAt)
        assertFalse(cold.run()); assertEquals(5, computations)
        assertTrue(DigestPrompt.transcript(lines).first.contains("我："))
        Unit
    }
    @Test fun differentSpeakerGetsScoreButNoNameAndShortGroupIsSkipped() = runBlocking {
        enroll()
        val (different, _) = segment(sign = -1)
        val (short, _) = segment(start = 10000, seconds = 1)
        assertFalse(repository.run())
        assertTrue(db.voiceprint().lines(different.id).all { it.speakerName == null && it.speakerScore == 0f })
        assertTrue(db.voiceprint().lines(short.id).all { it.speakerScore == null })
        assertNotNull(db.transcribe().segment(short.id).voiceprintProcessedAt)
        assertEquals(5, computations)
    }
    @Test fun markMeIsGroupScopedDeduplicatedAndNotMeRemovesPoisonedSamplePermanently() = runBlocking {
        enroll()
        val (segment, lines) = segment()
        val (other, _) = segment(start = 10000, seconds = 2)
        action { repository.markMe(lines[0].id) }
        assertEquals(4, db.voiceprint().samples().size)
        assertEquals(2500, db.voiceprint().samples().last().durationMillis) // 只补所点句子，不再拼整组。
        action { repository.markMe(lines[1].id) }
        assertEquals(4, db.voiceprint().samples().size)
        action { repository.markNotMe(lines[1].id) }
        assertEquals(3, db.voiceprint().samples().size)
        assertEquals(listOf("tag:0"), db.voiceprint().exclusions(segment.id))
        assertTrue(db.voiceprint().lines(segment.id).all { it.speakerName == null && it.speakerScore == null })
        repository.run() // 纠正只提交并调度，历史重打分交给后台。
        assertTrue(db.voiceprint().lines(other.id).all { it.speakerName == "我" })
        repository.enrollPcm(ShortArray(160_000) { 1000 }).getOrThrow(); scheduled.receive()
        repository.run()
        assertTrue(db.voiceprint().lines(segment.id).all { it.speakerName == null })
        // 明确重新确认可以撤回排除；自动重算不可以。
        action { repository.markMe(lines[0].id) }
        assertTrue(db.voiceprint().exclusions(segment.id).isEmpty())
        assertTrue(db.voiceprint().lines(segment.id).all { it.speakerName == "我" })
    }
    @Test fun resetClearsSamplesNamesScoresExclusionsAndReadiness() = runBlocking {
        enroll(); val (segment, lines) = segment()
        action { repository.markNotMe(lines[0].id) }
        action { repository.reset() }
        assertFalse(repository.isReady()); assertTrue(db.voiceprint().samples().isEmpty())
        assertTrue(db.voiceprint().exclusions(segment.id).isEmpty())
        assertTrue(db.voiceprint().lines(segment.id).all { it.speakerName == null && it.speakerScore == null })
        assertNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
    }
    @Test fun failedProcessingRetainsAudioThenRetriesBeforeCleanupAndMissingFilesDoNotBlockForever() = runBlocking {
        enroll(); val (segment, _) = segment()
        val transcribe = TranscribeRunner(db.transcribe(), object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = error("不应上传")
        }, { "" }, { _, _ -> }, now = { clock }, deferAudioCleanup = true)
        transcribe.run()
        assertTrue(File(segment.path).exists()) // App 的转写开始阶段不再提前删待比对录音。
        failDecode = true
        assertTrue(repository.run()); repository.cleanupAudio()
        assertTrue(File(segment.path).exists()); assertNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
        failDecode = false
        assertFalse(repository.run()); repository.cleanupAudio()
        assertFalse(File(segment.path).exists())
        db.voiceprint().invalidate(); assertFalse(repository.run())
        assertNotNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
        assertTrue(db.voiceprint().lines(segment.id).all { it.speakerScore == 1f }) // 音频已删仍可复用缓存。
    }
    @Test fun digestWaitsForWholeSessionOnlyWhenReadyIncludingForceRun() = runBlocking {
        val (segment, lines) = segment()
        val client = LlmClient()
        val digest = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client), { "" }, { true },
            now = { clock }, voiceprintReady = repository::isReady)
        assertTrue(digest.closed(lines, true))
        enroll(); assertFalse(digest.closed(lines, true))
        repository.run(); assertTrue(digest.closed(lines, true))
        db.voiceprint().invalidate(); assertFalse(digest.closed(lines, false))
        assertEquals(1, db.memory().voiceprintPending(listOf(segment.id)))
        Unit
    }
    @Test fun concurrentRoundsShareOneGateAndDigestHoldsStableResults() = runBlocking {
        enroll(); val (segment, lines) = segment()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var decodes = 0
        val serial = LocalVoiceprintRepository(db, scope, { file ->
            decodes++; entered.complete(Unit); release.await(); audio.getValue(file.path)
        }, { SpeakerEmbedding { vector(0) } }, { scheduled.trySend(Unit); Unit }, { clock })
        val first = async { serial.run() }; entered.await()
        val second = async { serial.run() }
        yield(); assertEquals(1, decodes)
        release.complete(Unit); first.await(); second.await()
        assertEquals(1, decodes)
        serial.withStableResults {
            serial.markNotMe(lines[0].id)
            delay(30)
            assertTrue(db.voiceprint().exclusions(segment.id).isEmpty())
            assertTrue(db.voiceprint().lines(segment.id).all { it.speakerName == "我" })
        }
        withTimeout(5000) { scheduled.receive() }
        assertEquals(listOf("tag:0"), db.voiceprint().exclusions(segment.id))
    }

    @Test fun insufficientOrSilentEnrollmentDoesNotPersistAndCancellationIsPropagated() = runBlocking {
        assertTrue(repository.enrollPcm(ShortArray(160_000)).isFailure)
        assertTrue(repository.enrollPcm(ShortArray(23999) { 1000 }).isFailure)
        assertTrue(db.voiceprint().samples().isEmpty()); assertEquals(0, computations)
        val cancelled = LocalVoiceprintRepository(db, scope, { shortArrayOf() },
            { SpeakerEmbedding { throw CancellationException() } }, {}, { clock })
        try { cancelled.enrollPcm(ShortArray(24000) { 1000 }); fail("必须传播取消") } catch (_: CancellationException) { }
        assertTrue(db.voiceprint().samples().isEmpty())
    }

    private fun scoredVector(score: Float) = FloatArray(192).apply {
        this[0] = score; this[1] = kotlin.math.sqrt(1f - score * score)
    }

    @Test fun autoSamplesRequireScoreAndEffectiveSpeechAndRespectExclusionsAndDeduplicate() = runBlocking {
        enroll()
        val (boundary, _) = segment(seconds = 3)
        val (belowScore, _) = segment(start = 10000, seconds = 3)
        val (belowTime, _) = segment(start = 20000, seconds = 3)
        val (silentPart, _) = segment(start = 30000, seconds = 3)
        val (excluded, excludedLines) = segment(start = 40000, seconds = 3)
        audio[boundary.path] = ShortArray(32000) { 850 }
        audio[belowScore.path] = ShortArray(48000) { 849 }
        audio[belowTime.path] = ShortArray(31999) { 1000 }
        audio[silentPart.path] = ShortArray(32000) { if (it < 320) 0 else 1000 }
        repository = LocalVoiceprintRepository(db, scope, { audio.getValue(it.path) },
            { SpeakerEmbedding { scoredVector(it.first() / 1000f) } }, { scheduled.trySend(Unit); Unit }, { clock })
        action { repository.markNotMe(excludedLines.first().id) }
        assertFalse(repository.run())
        val auto = db.voiceprint().samples().filter { it.source == "auto" }
        assertEquals(listOf(boundary.id), auto.map { it.segmentId })
        assertEquals(2000L, auto.single().durationMillis)
        assertEquals(0.85f, db.voiceprint().lines(boundary.id).first().speakerScore!!, 0.00001f)
        assertNull(db.voiceprint().lines(excluded.id).first().speakerScore)
        // 自动样本不参与打分；再次排队已入样本的组，验证同组去重。
        db.transcribe().update(db.transcribe().segment(boundary.id).copy(voiceprintProcessedAt = null))
        assertFalse(repository.run())
        assertEquals(1, db.voiceprint().samples().count { it.segmentId == boundary.id })
    }

    @Test fun autoLimitEvictsOldestByTimeThenIdAndNeverManualSamples() = runBlocking {
        enroll()
        val (_, manualLines) = segment(start = 500, seconds = 3)
        action { repository.markMe(manualLines.first().id) }
        repeat(40) { i ->
            db.voiceprint().saveSample(VoiceprintSampleEntity(source = "auto", sourceLineId = null,
                segmentId = 1000L + i, groupKey = "tag:0", vector = VoiceprintMath.encode(vector(0)),
                durationMillis = 3000, createdAt = if (i == 39) 1 else 2))
        }
        val (newest, _) = segment(start = 10000, seconds = 3)
        val (next, _) = segment(start = 20000, seconds = 3)
        assertFalse(repository.run())
        val samples = db.voiceprint().samples()
        val auto = samples.filter { it.source == "auto" }
        assertEquals(40, auto.size)
        assertFalse(auto.any { it.segmentId == 1039L || it.segmentId == 1000L })
        assertTrue(auto.any { it.segmentId == newest.id })
        assertTrue(auto.any { it.segmentId == next.id })
        assertEquals(3, samples.count { it.source == "enroll" })
        assertEquals(1, samples.count { it.source == "line" })
        assertTrue(samples.filter { it.source == "enroll" }.all { it.createdAt == clock })
    }

    @Test fun confirmingAutoSamplePromotesItWithoutDecodingAndProtectsItFromEviction() = runBlocking {
        enroll(); val (segment, lines) = segment(seconds = 3)
        segment(start = 10000, seconds = 1) // 保留另一个有缓存的段，让后续处理执行自动样本淘汰。
        repository.run()
        val original = db.voiceprint().samples().single { it.source == "auto" }
        assertTrue(File(segment.path).delete()) // 音频过期才复用已有样本，不重新解码。
        failDecode = true
        action { repository.markMe(lines.first().id) }
        val promoted = db.voiceprint().samples().single { it.segmentId == segment.id }
        assertEquals(original.id, promoted.id)
        assertEquals("line", promoted.source)
        repeat(41) { i ->
            db.voiceprint().saveSample(VoiceprintSampleEntity(source = "auto", sourceLineId = null,
                segmentId = 1000L + i, groupKey = "tag:0", vector = VoiceprintMath.encode(vector(0)),
                durationMillis = 3000, createdAt = clock + i))
        }
        repository.run()
        assertEquals(40, db.voiceprint().samples().count { it.source == "auto" })
        assertEquals("line", db.voiceprint().samples().single { it.segmentId == segment.id }.source)
    }

    @Test fun correctionsCompleteWithinTwoSecondsWhileBackgroundEngineIsBlockedAndOldResultsCannotOverwrite() = runBlocking {
        enroll()
        val (slowSegment, slowLines) = segment(seconds = 3)
        val (_, manualLines) = segment(start = 10000, seconds = 3, sign = -1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        repository = LocalVoiceprintRepository(db, scope, { audio.getValue(it.path) }, {
            object : SpeakerEmbedding, AutoCloseable {
                override fun compute(pcm: ShortArray): FloatArray {
                    if (pcm.first() > 0) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return vector(if (pcm.first() > 0) 0 else 1)
                }
                override fun close() { closed.incrementAndGet() }
            }
        }, { scheduled.trySend(Unit); Unit }, { clock })
        val background = async(Dispatchers.IO) { repository.run() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            val start = System.nanoTime()
            withTimeout(2000) { action { repository.markMe(manualLines.first().id) } }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
            assertTrue(db.voiceprint().samples().any { it.sourceLineId == manualLines.first().id && it.source == "line" })
            withTimeout(2000) { action { repository.markNotMe(slowLines.first().id) } }
            assertFalse(background.isCompleted)
            assertEquals(listOf("tag:0"), db.voiceprint().exclusions(slowSegment.id))
            assertNull(db.voiceprint().lines(slowSegment.id).first().speakerScore)
        } finally { release.countDown() }
        assertTrue(background.await())
        assertFalse(db.voiceprint().samples().any { it.segmentId == slowSegment.id })
        assertNull(db.voiceprint().lines(slowSegment.id).first().speakerScore)
        assertEquals(2, closed.get()) // 人工与后台各自拥有并释放引擎。
        assertFalse(repository.run())
        assertNull(db.voiceprint().lines(slowSegment.id).first().speakerName)
    }

    @Test fun rescoringUsesCachedVectorsAndCurrentThresholdAndPreservesOtherNames() = runBlocking {
        enroll()
        val (target, _) = segment(seconds = 2)
        audio[target.path] = ShortArray(32000) { 700 }
        val (named, namedLines) = segment(start = 10000, seconds = 2)
        db.transcribe().deleteLines(named.id)
        db.transcribe().insertLines(namedLines.map { it.copy(speakerName = "同事") })
        repository = LocalVoiceprintRepository(db, scope, { file ->
            check(!failDecode); audio.getValue(file.path)
        }, { SpeakerEmbedding { pcm -> computations++; if (pcm.first() == 700.toShort()) scoredVector(0.55f) else vector(0) } },
            { scheduled.trySend(Unit); Unit }, { clock })
        repository.run()
        assertEquals("我", db.voiceprint().lines(target.id).first().speakerName)
        assertEquals(0.55f, db.voiceprint().lines(target.id).first().speakerScore!!, 0.00001f)
        val (correction, correctionLines) = segment(start = 20000, seconds = 3, sign = -1)
        assertTrue(File(correction.path).delete()) // 此段仅触发排除，不引入未缓存音频。
        // Top-3 不按时长加权：替换两条可信模板才会使 0.55 降到当前 SURE=0.50 以下。
        val originalSamples = db.voiceprint().samples().filter { it.source == "enroll" }
        val correctedVector = FloatArray(192).apply { this[1] = -1f }
        originalSamples.take(2).forEach {
            db.voiceprint().saveSample(it.copy(vector = VoiceprintMath.encode(correctedVector)))
        }
        // 排除纠正段，无需提取新声音；目标录音保留，后台必须命中窗口缓存。
        failDecode = true
        val before = computations
        action { repository.markNotMe(correctionLines.first().id) }
        assertFalse(repository.run())
        assertEquals(before, computations)
        assertNull(db.voiceprint().lines(target.id).first().speakerName)
        assertTrue(db.voiceprint().lines(target.id).first().speakerScore!! < WindowedVoiceprint.SURE)
        assertTrue(db.voiceprint().lines(named.id).all { it.speakerName == "同事" })
        originalSamples.forEach { db.voiceprint().saveSample(it) }
        action { repository.markNotMe(correctionLines.first().id) }
        assertFalse(repository.run())
        assertEquals(before, computations)
        assertEquals("我", db.voiceprint().lines(target.id).first().speakerName)
        action { repository.reset() }
        assertTrue(db.voiceprint().lines(named.id).all { it.speakerName == "同事" && it.speakerScore == null })
    }

    @Test fun persistentFeatureCacheSurvivesDatabaseReopenWithoutAudioOrEngine() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        val file = File.createTempFile("voiceprint-db-", ".db", context.cacheDir).also { files += it }
        files += File("${file.path}.voiceprint-features")
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, file.path).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = createRepository()
        enroll()
        val (segment, _) = segment(seconds = 3)
        // 单句 1.5 秒，窗口键与点击范围一致，且低于自动补样本的 2 秒门槛。
        db.transcribe().deleteLines(segment.id)
        db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = segment.id,
            startAt = segment.startAt, endAt = segment.startAt + 1500, text = "短句", speakerTag = "0")))
        val lines = db.voiceprint().lines(segment.id)
        repository.run()
        assertFalse(db.voiceprint().samples().any { it.segmentId == segment.id })
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, file.path).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = LocalVoiceprintRepository(db, scope, { error("不得重新解码") }, { error("不得重新加载模型") },
            { scheduled.trySend(Unit); Unit }, { clock })
        assertTrue(File(segment.path).delete())
        action { repository.markMe(lines.first().id) }
        assertFalse(repository.run())
        assertEquals("line", db.voiceprint().samples().single { it.segmentId == segment.id }.source)
        assertEquals(1f, db.voiceprint().lines(segment.id).first().speakerScore)
        action { repository.reset() }
        assertTrue(File("${file.path}.voiceprint-features").listFiles()!!.isEmpty())
    }

    @Test fun resetDuringBackgroundDecodeDoesNotRestoreSamplesResultsOrFeatures() = runBlocking {
        enroll(); val (segment, lines) = segment(seconds = 3)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var decodes = 0
        repository = LocalVoiceprintRepository(db, scope, { file ->
            decodes++
            if (decodes == 1) { entered.complete(Unit); release.await() }
            audio.getValue(file.path)
        }, { SpeakerEmbedding { vector(0) } }, { scheduled.trySend(Unit); Unit }, { clock })
        val background = async { repository.run() }
        try {
            withTimeout(3000) { entered.await() }
            withTimeout(2000) { action { repository.reset() } }
        } finally { release.complete(Unit) }
        assertTrue(background.await())
        assertTrue(db.voiceprint().samples().isEmpty())
        assertNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
        assertNull(db.voiceprint().lines(segment.id).first().speakerScore)
        action { repository.markMe(lines.first().id) }
        assertEquals(2, decodes) // reset 后旧轮次没有复活缓存，需重新提取。
    }

    @Test fun cleanupDoesNotDeleteAudioBeingReadByCorrectionBeforeSamplesAreReady() = runBlocking {
        val (segment, lines) = segment(seconds = 3)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository = LocalVoiceprintRepository(db, scope, { file ->
            entered.complete(Unit); release.await(); audio.getValue(file.path)
        }, { SpeakerEmbedding { vector(0) } }, { scheduled.trySend(Unit); Unit }, { clock })
        repository.markMe(lines.first().id)
        try {
            withTimeout(3000) { entered.await() }
            repository.cleanupAudio()
            assertTrue(File(segment.path).isFile)
        } finally { release.complete(Unit) }
        withTimeout(3000) { scheduled.receive() }
        repository.cleanupAudio()
        assertFalse(File(segment.path).exists())
    }
}
