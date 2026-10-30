package app.resound.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmBalanceTest {
    @Test fun deepSeekFormatPrefersCny() {
        val b = LlmBalanceChecker.parse("""{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"1.00","granted_balance":"0","topped_up_balance":"1.00"},{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""", 1)
        assertEquals(110.0, b.total, 0.001); assertEquals(10.0, b.granted!!, 0.001); assertEquals("CNY", b.currency)
    }

    @Test fun customPathReadsStringsNumbersAndArrays() {
        assertEquals(88.88, LlmBalanceChecker.pick("""{"code":20000,"data":{"totalBalance":"88.88"}}""", "data.totalBalance"), 0.001)
        assertEquals(49.58, LlmBalanceChecker.pick("""{"data":{"available_balance":49.58}}""", "data.available_balance"), 0.001)
        assertEquals(3.0, LlmBalanceChecker.pick("""{"list":[{"b":3}]}""", "list.0.b"), 0.001)
        val missing = runCatching { LlmBalanceChecker.pick("""{"data":{}}""", "data.balance") }
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("balance"))
    }
}
