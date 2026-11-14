package app.resound.devices.plugins.d3200

import app.resound.devices.driver.*
import app.resound.devices.plugins.d3200.D3200Driver
import app.resound.devices.replay.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Private capture adapter: recorded public/shared test values, real HKDF/AES, structural Opus stub.
 * Does not validate fresh ECDH or acoustic decoding; listening tests cover the saved audio separately. */
class D3200CapturedReplayTest {
    private val root = System.getProperty("d3200.fixture.dir")?.let(::File)
        ?: File(requireNotNull(javaClass.getResource("/d3200-captured")) { "missing D3200 capture fixtures" }.toURI())
    private fun properties(kind: String) = Properties().apply { File(root,"replay-$kind.properties").inputStream().use { load(it) } }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private class RecordedCrypto(val public: ByteArray, val shared: ByteArray) : CryptoPort {
        override fun randomBytes(size: Int): ByteArray = error("not used")
        override fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length == 32)
            return hmacSha256(hmacSha256(salt, ikm), info + byteArrayOf(1))
        }
        override fun p256KeyPair() = object : P256KeyPair { override val publicKey = public }
        override fun p256Ecdh(own: P256KeyPair, peerPublic: ByteArray) = shared.copyOf()
        override fun p256VerifyDer(spkiPublicKey: ByteArray, data: ByteArray, derSignature: ByteArray): Boolean = error("not used")
        override fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
        }
        override fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray = error("not used")
        override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray = error("not used")
    }
    private suspend fun replay(kind: String): ReplayResult {
        val p = properties(kind)
        val crypto = RecordedCrypto(p.getProperty("public").unhex(),p.getProperty("shared").unhex())
        val toolbox = object : Toolbox {
            override val crypto = crypto
            override fun opusDecoder(sampleRate: Int, channels: Int) = object : OpusDecoder {
                override fun decode(packet: ByteArray) = ShortArray(320 * channels)
                override fun close() {}
            }
        }
        val clock = object : ClockPort {
            override fun nowEpochMillis() = p.getProperty("epoch").toLong()
            override fun monotonicMillis() = 0L
            override fun zoneOffsetMinutes() = 0
        }
        val actual = D3200Driver()
        val capture = File(root,"replay-$kind-prefix.txt").readText()
        // ReplayGattLink pumps notifications immediately, rather than advancing wall time.
        // For a long real recording, invoke the actual scheduled status task at each recorded
        // 0101 write barrier. No masks, capture edits or synthetic device replies are used.
        val recorded = CaptureText(capture).records
        var requestedAudio = false
        val statusGates = recorded.mapIndexedNotNull { i, record ->
            if (record.kind in "Ww" && record.bytes.size >= 10) {
                val command = record.bytes.copyOfRange(5,7)
                if (command.contentEquals(byteArrayOf(0x1a,0x07))) requestedAudio = true
                if (requestedAudio && command.contentEquals(byteArrayOf(1,1))) recorded.getOrNull(i-1)?.at else null
            } else null
        }
        // The real list is replayed in full; select only the first downloaded file for this bounded action.
        val selected = object : DeviceDriver by actual {
            override suspend fun open(session: DriverSession): DriverConnection {
                session.store.putString("platformHandle", "local-fixture")
                session.store.putString("platformIdentity", "local-fixture-identity")
                val statusJobs = mutableListOf<Job>()
                val replayScope = CoroutineScope(currentCoroutineContext())
                val replaySession = object : DriverSession by session {
                    override fun schedule(name: String, everyMillis: Long, task: suspend () -> Unit): ScheduledTask {
                        if (kind != "live" || name != "status" || statusGates.isEmpty())
                            return session.schedule(name,everyMillis,task)
                        val link = session.link as ReplayGattLink
                        val job = replayScope.launch {
                            for (gate in statusGates) {
                                while (link.elapsedMillis < gate) delay(1)
                                task()
                            }
                        }.also { statusJobs += it }
                        return object : ScheduledTask { override fun cancel() { job.cancel() } }
                    }
                }
                val c = try { actual.open(replaySession) } catch (e: Exception) {
                    withContext(NonCancellable) { statusJobs.forEach { it.cancelAndJoin() } }
                    throw e
                }
                if (kind == "live") return object : DriverConnection by c {
                    override suspend fun close() {
                        statusJobs.forEach { it.cancelAndJoin() }
                        c.close()
                    }
                }
                val f = requireNotNull(c.files)
                return object : DriverConnection by c {
                    override val files = object : DeviceFiles by f {
                        override suspend fun list() = f.list().filter { it.id == p.getProperty("target") }.also { require(it.size == 1) }
                        override fun read(file: RecordingFile, fromOffset: Long) = f.read(file, fromOffset).let { data ->
                            p.getProperty("maxChunks")?.toInt()?.let { data.take(it) } ?: data
                        }
                    }
                }
            }
        }
        val action = if (kind == "live") ReplayAction.Live(p.getProperty("frames", "20").toInt())
            else ReplayAction.Files(p.getProperty("fromOffset", "0").toLong())
        return ReplayHarness(toolbox, clock).run(selected, capture, action, timeoutMillis = 10_000)
    }
    @Test fun capturedLivePrefixThroughOfficialHarness() = runTest {
        val result = replay("live")
        assertTrue(result.errors.map { it.stage to it.code }.toString(), result.errors.isEmpty())
        val expectedFrames = properties("live").getProperty("frames", "20").toInt()
        assertEquals(expectedFrames, result.frameCount)
        assertEquals(expectedFrames * 480L, result.sampleCount)
        assertEquals(0L, result.lostSamples)
        println("live: official harness, $expectedFrames frames, no errors; Opus stub")
    }
    @Test fun capturedFileActionThroughOfficialHarness() = runTest {
        val result = replay("files")
        assertTrue(result.errors.map { it.stage to it.code }.toString(), result.errors.isEmpty())
        assertEquals(1, result.downloads.size)
        val p = properties("files")
        val expectedBytes = p.getProperty("expectedBytes")?.toLong() ?: p.getProperty("packets").toLong() * 160
        assertEquals(expectedBytes, result.downloads.single().bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        result.downloads.single().chunks.forEach { digest.update(it) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (p.getProperty("expectedSha256") != null) assertEquals(p.getProperty("expectedSha256"), hash)
        else File(root,"replay-file-plaintext.sha256").writeText(hash)
        println("files: official harness, selected bounded action, $expectedBytes decrypted bytes, no errors")
    }
}
