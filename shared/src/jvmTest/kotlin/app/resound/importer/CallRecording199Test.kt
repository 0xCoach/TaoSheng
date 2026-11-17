package app.resound.importer

import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDateTime
import java.time.ZoneId

class CallRecording199Test {
    private val zone = TimeZone.of("Asia/Shanghai")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(ZoneId.of(zone.id)).toInstant().toEpochMilli()
    private val now = at("2026-11-17T15:31:00")
    private fun parse(name: String, end: Long? = now, duration: Long? = null) = CallFileNames.parse(name,end,null,duration,zone)
    @Test fun requiredTwelveExamples() {
        data class Case(val file: String,val number: String?,val name: String?,val start: Long,val source: CallTimeSource=CallTimeSource.FileName,val direction: CallDirection?=null)
        val date = at("2026-11-17T15:30:12")
        val cases = listOf(
            Case("通话录音@中国联通(10010)20170330120101.mp3","10010","中国联通",at("2017-03-30T12:01:01")),
            Case("13800138000_20261117153012.mp3","13800138000",null,date),
            Case("通话录音@13800138000_20261117153012.mp3","13800138000",null,date),
            Case("张三(13800138000)_20261117153012.mp3","13800138000","张三",date),
            Case("张三 13800138000_20261117_153012.m4a","13800138000","张三",date),
            Case("Call recording 张三_261117_153012.m4a",null,"张三",date),
            Case("Call_20261117_153012_+86 138 0013 8000.m4a","13800138000",null,date),
            Case("张总@138-0013-8000 15-30.amr","13800138000","张总",at("2026-11-17T15:30:00"),CallTimeSource.FileNameTimeOnly),
            Case("来电 李四 下午3点30分.mp3",null,"李四",at("2026-11-17T15:30:00"),CallTimeSource.FileNameTimeOnly,CallDirection.Incoming),
            Case("021-12345678_1731828612000.mp3","02112345678",null,1731828612000L),
            Case("未知号码_20261117153012.mp3",null,null,date),
            Case("录音 001.mp3",null,null,now-3000,CallTimeSource.FileClock))
        cases.forEach { c -> val r=parse(c.file,duration=if (c.file=="录音 001.mp3") 3000 else null)
            assertEquals(c.file,c.number,r.number); assertEquals(c.file,c.name,r.name); assertEquals(c.file,c.start,r.startAt)
            assertEquals(c.file,c.source,r.timeSource); assertEquals(c.file,c.direction,r.direction) }
    }
    @Test fun ambiguousTwelveDigitsChooseReferenceOrFallBack() {
        val first = at("2012-01-01T01:01:00"); val second=at("2020-12-01T01:01:01")
        assertEquals(first,parse("201201010101.mp3",first).startAt)
        assertEquals(second,parse("201201010101.mp3",second).startAt)
        assertEquals(CallTimeSource.FileClock,parse("201201010101.mp3").timeSource)
    }
    @Test fun midnightTimeOnlyAndMetadataPrecedence() {
        val clock=at("2026-11-18T00:02:00")
        assertEquals(at("2026-11-17T23:59:00"),parse("23-59.wav",clock).startAt)
        assertEquals(now,CallFileNames.parse("录音.mp3",clock,now,3000,zone).startAt)
        assertNull(parse("录音.mp3",null).startAt)
    }
    @Test fun supportedDatePartsAndInvalidDates() {
        listOf("2026-11-17 15-30-12", "2026-11-17 15:30:12", "20261117_1530", "202611171530", "【20261117】-153012", "153012＠20261117").forEach {
            assertEquals(it,CallTimeSource.FileName,parse("$it.mp3").timeSource)
        }
        listOf("2026.11.17","2026年11月17日","11月17日").forEach { assertEquals(it,at("2026-11-17T00:00:00"),parse("$it.mp3").startAt) }
        listOf("15.30.12","1530","15点30分","15时30分12秒","晚上3点","15:30").forEach { assertEquals(it,CallTimeSource.FileNameTimeOnly,parse("$it.mp3").timeSource) }
        assertNotEquals(CallTimeSource.FileName,parse("20261317253012.mp3").timeSource)
        assertEquals("13800138000",CallFileNames.normalizeNumber("+86 138-0013-8000"))
        assertNull(CallFileNames.normalizeNumber("abcd"))
    }
    @Test fun brandGuidesAndFolderRecognition() {
        assertEquals("小米 / 红米",CallRecordingFolders.guide("XIAOMI","poco").brand)
        assertEquals(3,CallRecordingFolders.guide("HONOR","").folders.size)
        assertTrue(CallRecordingFolders.guide("unknown","").folders.isEmpty())
        assertTrue(CallRecordingFolders.guide("oneplus","").note!!.contains("ColorOS"))
        listOf("Sounds/CallRecord","a\\Recordings\\Call","MIUI/sound_recorder/call_rec").forEach { assertTrue(CallRecordingFolders.looksLikeCallFolder(it)) }
        assertFalse(CallRecordingFolders.looksLikeCallFolder("Music/Podcasts"))
    }
    @Test fun previewCountsWindowsAndDurations() {
        val files = listOf(1,10,40).map { days -> FolderMediaSnapshot("$days","录音",3,now,days*1000L,null,CallFileInfo(null,"张三",now-days*86400_000L,null,CallTimeSource.FileClock)) }
        val p=FolderPreviewPolicy.count(files,now)
        assertEquals(3,p.total);assertEquals(51_000L,p.millis);assertEquals(1,p.last7);assertEquals(1000L,p.millis7)
        assertEquals(2,p.last30);assertEquals(11_000L,p.millis30);assertEquals(3,p.callLike)
    }
    @Test fun queueOrderPauseAndHistoryExpansion() {
        fun row(id: Long,start: Long,history: Boolean)=FolderRecordingRow(id,"f","$id",fileName="a",size=1,modified=1,startAt=start,durationMillis=1,state="Queued",createdAt=1,kind="Call",backfill=history)
        val history=row(1,20,true);val manual=row(2,10,false)
        assertEquals(2L,FolderHistoryPolicy.next(listOf(history,manual),false)!!.id)
        assertNull(FolderHistoryPolicy.next(listOf(history),true))
        assertTrue(FolderHistoryPolicy.expands(FolderHistory.OnlyNew,FolderHistory.ListAll))
        assertFalse(FolderHistoryPolicy.expands(FolderHistory.Days30,FolderHistory.Days7))
    }
    @Test fun promptNeverFallsBackToNumber() {
        assertEquals("对方",CallNamePolicy.prompt(null));assertEquals("张总",CallNamePolicy.prompt("张总"))
    }
}
