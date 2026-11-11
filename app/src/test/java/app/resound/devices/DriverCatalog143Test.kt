package app.resound.devices

import app.resound.devices.android.DriverCatalog
import app.resound.devices.plugins.d3200.D3200Ogg
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DriverCatalog143Test {
    @Test fun packetFileBecomesValidStereoOggWithOriginalBytes() {
        val raw=File.createTempFile("driver-opus",".part"); val ogg=File.createTempFile("driver-opus",".ogg")
        try {
            val packet=ByteArray(160).apply { this[0]=0xfc.toByte() }
            raw.writeBytes(packet+packet)
            assertTrue(DriverCatalog.encode("d3200",raw,ogg))
            val bytes=ogg.readBytes(); var at=0; var pages=0
            while(at<bytes.size) {
                assertEquals("OggS",String(bytes,at,4))
                val count=bytes[at+26].toInt() and 255
                val size=(0 until count).sumOf { bytes[at+27+it].toInt() and 255 }
                val page=bytes.copyOfRange(at,at+27+count+size)
                val expected=ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
                repeat(4) { page[22+it]=0 }; assertEquals(expected,D3200Ogg.crc(page))
                if(pages==0) assertEquals(2,bytes[at+28+9].toInt())
                if(pages==2) {
                    assertEquals(1920L,ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getLong(6))
                    assertEquals(4,page[5].toInt()); assertEquals(320,size)
                    assertArrayEquals(packet+packet,page.copyOfRange(27+count,page.size))
                }
                pages++; at+=page.size
            }
            assertEquals(3,pages)
        } finally { raw.delete(); ogg.delete() }
    }
}
