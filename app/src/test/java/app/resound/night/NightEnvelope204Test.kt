package app.resound.night

import android.app.Application
import app.resound.data.CaptureDatabase
import app.resound.data.open
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NightEnvelope204Test {
    @Test fun durablePrefixAndFinalCompressedMapMatchOldEncoding() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val db = CaptureDatabase.open(context,"envelope204.db")
        try {
            val id = db.night().insert(NightRow(date="2026-11-17",start=0,end=0,state="Active",payload="{}",signals="{}",createdAt=0,updatedAt=0))
            val writer = NightEnvelopeWriter(db,id,100)
            val expected = mutableListOf<Byte>()
            repeat(64) { i ->
                val point=byteArrayOf((-i).toByte(),(-2*i).toByte(),-78)
                writer.append(point[0],point[1],point[2])
                expected.addAll(point.toList())
                assertArrayEquals(expected.toByteArray(),NightFiles.unpack(db.night().envelope(id)!!.points))
            }
            writer.finish()
            val row=db.night().envelope(id)!!
            assertEquals(100L,row.start)
            assertArrayEquals(NightFiles.envelope(expected.toByteArray()),row.points)
        } finally { db.close();context.deleteDatabase("envelope204.db") }
    }
}
