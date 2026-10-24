import app.resound.voiceprint.KaldiFbank
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args: Array<String>) {
    val b = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val pcm = ShortArray(b.remaining()).also { b.get(it) }
    val f = KaldiFbank().compute(pcm, args.getOrNull(2) == "normalized")
    val out = ByteBuffer.allocate(f.size * 80 * 4).order(ByteOrder.LITTLE_ENDIAN)
    f.forEach { frame -> frame.forEach { out.putFloat(it) } }
    File(args[1]).writeBytes(out.array())
}
