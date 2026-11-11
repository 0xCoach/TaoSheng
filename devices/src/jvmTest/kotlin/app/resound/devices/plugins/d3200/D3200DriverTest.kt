package app.resound.devices.plugins.d3200

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 合成向量由 PR 的编码函数构造；假密码插口只验证编排，真实加密向量另测。 */
class D3200DriverTest {
    private val file = 1730000000L
    private class Store : DriverStore {
        private val strings=mutableMapOf("platformHandle" to "handle", "platformIdentity" to "test:handle")
        override fun getString(key:String)=strings[key]
        override fun putString(key:String,value:String?) { if (value==null) strings.remove(key) else strings[key]=value }
        override fun getBytes(key:String):ByteArray?=null
        override fun putBytes(key:String,value:ByteArray?) {}
        override fun keys():Set<String> = strings.keys
    }
    private class FakeCrypto : CryptoPort {
        val shared=ByteArray(32) { 7 }
        override fun randomBytes(size:Int)=ByteArray(size)
        override fun sha256(data:ByteArray)=ByteArray(32)
        override fun hmacSha256(key:ByteArray,data:ByteArray)=ByteArray(32)
        override fun hkdfSha256(ikm:ByteArray,salt:ByteArray,info:ByteArray,length:Int)=ByteArray(length)
        override fun p256KeyPair()=object:P256KeyPair { override val publicKey=byteArrayOf(4)+ByteArray(64) }
        override fun p256Ecdh(own:P256KeyPair,peerPublic:ByteArray)=shared
        override fun p256VerifyDer(spkiPublicKey:ByteArray,data:ByteArray,derSignature:ByteArray)=true
        override fun aesCtr(key:ByteArray,iv:ByteArray,data:ByteArray)=data
        override fun aesGcmEncrypt(key:ByteArray,nonce:ByteArray,plain:ByteArray,aad:ByteArray)=plain
        override fun aesGcmDecrypt(key:ByteArray,nonce:ByteArray,sealed:ByteArray,aad:ByteArray)=sealed
    }
    private inner class Link(private val crypto: FakeCrypto): GattLink {
        val notifications=MutableSharedFlow<ByteArray>(extraBufferCapacity=64)
        var recording=true
        var firmware="04.92"
        var serial:String?=null
        var requests=0
        private fun reply(command:Int,p:ByteArray):ByteArray {
            val size=p.size+10
            val result=byteArrayOf(9,0xff.toByte(),0,0,1,(command ushr 8).toByte(),command.toByte(),size.toByte(),(size ushr 8).toByte())+p+byteArrayOf(0)
            result[result.lastIndex]=result.dropLast(1).sumOf { it.toInt() and 255 }.toByte()
            return result
        }
        private fun unit(seq:Long):ByteArray = D3200Protocol.le32(seq)+byteArrayOf(0)+ByteArray(160).apply { this[0]=0xf8.toByte() }+byteArrayOf(0)
        override fun maxWriteLength(withResponse:Boolean)=514
        override suspend fun read(service:String,characteristic:String)=byteArrayOf()
        override fun notifications(service:String,characteristic:String):Flow<ByteArray> = notifications
        override suspend fun write(service:String,characteristic:String,bytes:ByteArray,withResponse:Boolean) {
            when (val command=D3200Protocol.command(bytes)) {
                0x0101 -> {
                    val p=ByteArray(51); p[1]=7; p[2]=1; firmware.encodeToByteArray().copyInto(p,3)
                    serial?.encodeToByteArray()?.copyInto(p,8); p[50]=if(recording) 1 else 0
                    notifications.emit(reply(command,p))
                }
                0x2e01 -> notifications.emit(reply(command,byteArrayOf(4)+ByteArray(64)+crypto.shared))
                0x1a0e -> notifications.emit(reply(command,byteArrayOf(0,0)+D3200Protocol.le32(file)+D3200Protocol.le32(40)))
                0x1b0e -> notifications.emit(reply(command,byteArrayOf(1,0)+D3200Protocol.le32(file)+D3200Protocol.le32(file+1)+D3200Protocol.le32(40)+ByteArray(8)))
                0x1a0f -> notifications.emit(reply(command,byteArrayOf()))
                0x1882 -> {
                    recording = D3200Protocol.payload(bytes).single().toInt() == 1
                    notifications.emit(reply(command,byteArrayOf(if (recording) 1 else 2)))
                }
                0x1a07 -> {
                    requests++
                    val p=ByteArray(87); D3200Protocol.le32(file).copyInto(p,0); D3200Protocol.le32(332).copyInto(p,4)
                    ("soundcored3200".encodeToByteArray()+ByteArray(32)).copyInto(p,24)
                    notifications.emit(reply(command,p))
                    notifications.emit(reply(0x1a08,unit(0)+unit(1)))
                }
                else -> error("unexpected command $command")
            }
        }
    }
    private inner class Session(val crypto:FakeCrypto=FakeCrypto(), serial: ClassicSerialPort? = null) : DriverSession {
        val fakeLink=Link(crypto)
        override val link:GattLink=fakeLink
        override val store=Store()
        override val clock=object:ClockPort { override fun nowEpochMillis()=1731000000000L; override fun monotonicMillis()=0L; override fun zoneOffsetMinutes()=0 }
        override val extras=object:PlatformExtras { override val classicSerial = serial }
        var heartbeat: (suspend () -> Unit)? = null
        var heartbeatMillis = 0L
        override val toolbox=object:Toolbox {
            override val crypto:CryptoPort=this@Session.crypto
            override fun opusDecoder(sampleRate:Int,channels:Int)=object:OpusDecoder {
                override fun decode(packet:ByteArray)=ShortArray(320*channels) { 1 }
                override fun close() {}
            }
        }
        override fun schedule(name:String,everyMillis:Long,task:suspend ()->Unit)=object:ScheduledTask { override fun cancel() {} }
        override fun keepAlive(everyMillis:Long,task:suspend ()->Unit): ScheduledTask {
            heartbeatMillis=everyMillis; heartbeat=task
            return object:ScheduledTask { override fun cancel() { heartbeat=null } }
        }
        override fun log(level:LogLevel,message:String) {}
    }
    @Test fun handshakeLiveAndRefreshKeepAudioAndCoverage() = runTest {
        val session=Session(); val driver=D3200Driver(); val connection=driver.open(session)
        assertEquals(80,connection.status.value.batteries.first().percent)
        val frame=Channel<AudioChunk>(1)
        val collector=backgroundScope.launch { connection.live!!.frames.collect { frame.send(it) } }
        val chunk=withTimeout(5_000) { frame.receive() }
        assertEquals(480,chunk.pcm.size); assertEquals(ChunkOrigin(file.toString(),0),chunk.origin)
        connection.controls.refresh()
        connection.live!!.processed(requireNotNull(chunk.origin),chunk.pcm.size)
        connection.live!!.captureFinished(false)
        collector.cancelAndJoin()
        session.fakeLink.recording=false
        val files=connection.files!!.list()
        assertEquals(listOf(MillisRange(0,30)),files.single().liveCovered)
        connection.close()
        val reopened=driver.open(session)
        assertEquals(listOf(MillisRange(0,30)),reopened.files!!.list().single().liveCovered)
        reopened.close()
    }
    @Test fun savedFileListReadAndRecordingConflict() = runTest {
        val session=Session(); session.fakeLink.recording=false
        val connection=D3200Driver().open(session)
        val file=connection.files!!.list().single()
        assertEquals(40L,file.durationMillis); assertEquals(320L,file.bytes)
        val chunks=connection.files!!.read(file,0).toList()
        assertEquals(320,chunks.sumOf { it.size }); assertEquals(1,session.fakeLink.requests)
        session.fakeLink.recording=true
        try { connection.files!!.list(); fail("recording must prevent backfill") }
        catch (e:DeviceException) { assertEquals(ErrorCode.StateConflict,e.code) }
        connection.close()
    }
    @Test fun lightCommandsRequireVerifiedFirmwareAndUseTwoSecondHeartbeat() = runTest {
        val sent=mutableListOf<ByteArray>(); val receipts=MutableSharedFlow<ByteArray>(extraBufferCapacity=4)
        var closed=false
        val serial=object:ClassicSerialPort {
            override suspend fun open(serviceUuid:String):SerialChannel = object:SerialChannel {
                override val incoming = receipts.asSharedFlow()
                override suspend fun send(bytes:ByteArray) { sent += bytes; receipts.emit(byteArrayOf(0xaa.toByte(),0x55,2,0x2c,1,2,1,0,0,0x57)) }
                override suspend fun close() { closed=true }
            }
        }
        val session=Session(serial=serial)
        val connection=D3200Driver().open(session)
        assertEquals(listOf(0,1,2),connection.controls.lightModes().map { it.id })
        connection.controls.setLight(1)
        assertEquals(2000L,session.heartbeatMillis); assertArrayEquals(BeanLight.command(false),sent.single())
        session.heartbeat!!.invoke(); assertEquals(2,sent.size)
        connection.controls.setLight(0); assertTrue(closed); assertNull(session.heartbeat)
        connection.close()
        val other=Session(serial=serial); other.fakeLink.firmware="04.91"
        val unverified=D3200Driver().open(other)
        assertEquals(listOf(0),unverified.controls.lightModes().map { it.id })
        try { unverified.controls.setLight(1); fail("unverified firmware") } catch (e:DeviceException) { assertEquals(ErrorCode.Unsupported,e.code) }
        assertEquals(2,sent.size); unverified.close()
    }
    @Test fun manualPauseSurvivesReconnectAndBlocksAutomaticStart() = runTest {
        val session=Session(); val driver=D3200Driver(); val first=driver.open(session)
        first.settings!!.set("autoStart",SettingValue.Bool(true))
        assertEquals(RecordingState.Stopped,first.controls.setRecording(RecordingState.Paused))
        assertFalse(session.fakeLink.recording); first.close()
        val second=driver.open(session)
        second.live!!.start()
        assertFalse(session.fakeLink.recording)
        assertEquals(RecordingState.Recording,second.controls.setRecording(RecordingState.Recording))
        assertTrue(session.fakeLink.recording); second.close()
    }
    @Test fun stoppingWhileWaitingForDeviceRecordingReleasesTheStream() = runTest {
        val session=Session(); session.fakeLink.recording=false
        val connection=D3200Driver().open(session); val live=connection.live!!
        live.start()
        val waiting=async { live.frames.toList() }
        yield(); live.stop()
        assertTrue(withTimeout(1000) { waiting.await() }.isEmpty())
        assertEquals(0,session.fakeLink.requests)
        connection.close()
    }
    @Test fun validReportedSerialUsesGlobalIdentityOtherwiseFallsBack() = runTest {
        val session=Session(); session.fakeLink.serial="TESTSERIAL001234"
        val connection=D3200Driver().open(session)
        assertEquals(DeviceId("d3200:testserial001234"),connection.identity.id)
        assertEquals(IdScope.Global,connection.identity.scope); connection.close()
        val unknown=Session(); unknown.fakeLink.serial="0000000000000000"
        val fallback=D3200Driver().open(unknown)
        assertEquals(DeviceId("test:handle"),fallback.identity.id)
        assertEquals(IdScope.PlatformLocal,fallback.identity.scope); fallback.close()
    }
}
