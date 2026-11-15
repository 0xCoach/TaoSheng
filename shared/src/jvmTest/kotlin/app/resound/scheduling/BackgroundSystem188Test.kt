package app.resound.scheduling

import org.junit.Assert.*
import org.junit.Test

class BackgroundSystem188Test {
    @Test fun manufacturersAndProperties() {
        assertEquals(BackgroundSystem.Type.Android, BackgroundSystem.identify(BackgroundSystem.Signals("Xiaomi", "redmi")))
        assertEquals(BackgroundSystem.Type.Huawei, BackgroundSystem.identify(BackgroundSystem.Signals("other", "HUAWEI")))
        assertEquals(BackgroundSystem.Type.Honor, BackgroundSystem.identify(BackgroundSystem.Signals("other", "other", magic = "8")))
    }
    @Test fun harmonyAndContainerOverrideVendor() {
        assertEquals(BackgroundSystem.Type.Harmony, BackgroundSystem.identify(BackgroundSystem.Signals("huawei", "huawei", osBrand = "Harmony")))
        assertEquals(BackgroundSystem.Type.Container, BackgroundSystem.identify(BackgroundSystem.Signals("unknown", "unknown", installer = "com.zhuoyi.appstore.lite")))
    }
    @Test fun failedJumpsFallBackAndNeverThrow() {
        val seen = mutableListOf<String>()
        assertFalse(BackgroundSystem.openFirst(listOf("startup", "legacy", "details")) { seen += it; error("没有页面") })
        assertEquals(listOf("startup", "legacy", "details"), seen)
    }
}
