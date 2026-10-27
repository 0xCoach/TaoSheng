package app.resound.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicYieldTest {
    @Test fun keepsRecordingUnlessSystemSilences() {
        // 没被静音时，哪怕在通话（微信电话）也照常收，且不去问通话状态
        assertNull(MicYield.whileOpen(silenced = false) { error("不该问通话状态") })
        assertEquals(MicYield.OTHER_APP, MicYield.whileOpen(silenced = true) { false })
        assertEquals(MicYield.IN_CALL, MicYield.whileOpen(silenced = true) { true })
    }
}
