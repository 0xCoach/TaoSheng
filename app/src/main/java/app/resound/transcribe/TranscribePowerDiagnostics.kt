package app.resound.transcribe

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId

/** 主进程计执行次数，:local_asr 计持锁时间。文件锁 + 原子替换，避免跨进程偏好缓存和丢增量。 */
internal object TranscribePowerDiagnostics {
    private const val DAYS = 30

    fun execution(context: Context, batched: Boolean, at: Long = System.currentTimeMillis()) = update(context) { days ->
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        add(days, day, if (batched) "batched" else "expedited", 1)
    }

    /** 用单调时钟算出的实际持有时间；跨午夜按本地日期拆分。 */
    fun held(context: Context, startedAt: Long, millis: Long) {
        if (millis <= 0) return
        update(context) { days ->
            var at = startedAt
            var remaining = millis
            val zone = ZoneId.systemDefault()
            while (remaining > 0) {
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val part = minOf(remaining, midnight - at)
                add(days, day.toString(), "heldMillis", part)
                remaining -= part
                at += part
            }
        }
    }

    private fun add(days: JSONObject, day: String, key: String, value: Long) {
        val row = days.optJSONObject(day) ?: JSONObject().also { days.put(day, it) }
        row.put(key, row.optLong(key) + value)
    }

    private fun update(context: Context, change: (JSONObject) -> Unit) {
        runCatching {
            access(context) { file ->
                val days = read(file)
                change(days)
                days.keys().asSequence().toList().sortedDescending().drop(DAYS).forEach(days::remove)
                val output = file.startWrite()
                try {
                    output.write(days.toString().toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) { file.failWrite(output); throw e }
            }
        }.onFailure { Log.w("TranscribePower", "省电诊断写入失败") }
    }

    fun dump(context: Context): String = runCatching {
        access(context) { file ->
            val days = read(file)
            days.keys().asSequence().toList().sorted().joinToString("\n") { day ->
                val row = days.getJSONObject(day)
                val expedited = row.optLong("expedited")
                val batched = row.optLong("batched")
                "转写省电 day=$day localAsrHeldMillis=${row.optLong("heldMillis")} " +
                    "realtimeExecutions=${expedited + batched} expedited=$expedited batched=$batched"
            }
        }
    }.getOrElse { "转写省电 诊断读取失败" }

    private fun read(file: AtomicFile): JSONObject =
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) JSONObject()
        else JSONObject(file.openRead().bufferedReader().use { it.readText() })

    @Synchronized private fun <T> access(context: Context, block: (AtomicFile) -> T): T {
        val directory = File(context.noBackupFilesDir, "transcribe-power").apply { mkdirs() }
        return RandomAccessFile(File(directory, "daily.lock"), "rw").use { handle ->
            handle.channel.lock().use { block(AtomicFile(File(directory, "daily.json"))) }
        }
    }
}
