package app.resound.night

import android.app.Application
import app.resound.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** 卡 210：没确认的到期删；确认过的录下满 90 天也删（原先永久保留） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NightClipRetention210Test {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun confirmedClipsExpireNinetyDaysAfterRecording()=runBlocking(Dispatchers.IO) {
        val file=File(context.cacheDir,"night210.db").apply { delete() }
        val db=CaptureDatabase.open(context,file.path)
        try {
            val day=86_400_000L;val now=200*day
            val night=db.night().insert(NightRow(date="2026-11-17",start=0,end=1,state="Ready",payload="{}",signals="{}",createdAt=0,updatedAt=0))
            fun clip(start: Long,confirmed: Boolean,expires: Long)=runBlocking { db.night().insertClip(NightClipRow(nightId=night,start=start,end=start+1,
                path="/x/$start",scores=ByteArray(0),features="{}",confirmed=confirmed,expiresAt=expires,candidate=false)) }
            val oldConfirmed=clip(now-91*day,true,now-84*day)
            clip(now-89*day,true,now-82*day)            // 确认过、录下 89 天：保留
            val dueUnconfirmed=clip(now-8*day,false,now-day)
            clip(now-2*day,false,now+5*day)             // 没确认、未到期：保留
            assertEquals(setOf(oldConfirmed,dueUnconfirmed),db.night().expired(now).map { it.id }.toSet())
        } finally { db.close();file.delete() }
    }
}
