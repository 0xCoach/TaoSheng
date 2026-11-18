package app.resound.night

import app.resound.data.CaptureDatabase
import app.resound.data.writeTransaction

internal class NightEnvelopeWriter(private val db: CaptureDatabase, private val id: Long, private val start: Long) {
    private val dao = db.night()
    private var started = false

    suspend fun append(peak: Byte, median: Byte, background: Byte) {
        val point = byteArrayOf(peak, median, background)
        if (started) dao.appendEnvelope(id, point)
        else {
            dao.envelope(NightEnvelopeRow(id, start, byteArrayOf(0) + point))
            started = true
        }
    }

    suspend fun finish() {
        if (!started) return
        db.writeTransaction {
            val row = dao.envelope(id) ?: return@writeTransaction
            dao.envelope(row.copy(points = NightFiles.envelope(NightFiles.unpack(row.points))))
        }
    }
}
