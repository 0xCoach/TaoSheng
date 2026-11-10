package app.resound.devices

import app.resound.devices.core.CardParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeviceCards129Test {
    private val parser = CardParser("0.2.21")
    private val directory = File("src/main/assets/devices")

    @Test fun everyBundledJsonParsesAndIdsAreUnique() {
        val files = requireNotNull(directory.listFiles()) { "找不到内置说明卡目录" }
            .filter { it.isFile && it.extension == "json" }.sortedBy { it.name }
        assertTrue("说明卡目录不能为空", files.isNotEmpty())
        val ids = mutableSetOf<String>()
        files.forEach { file ->
            val result = parser.parse(file.readText())
            assertTrue("${file.name}：${result.exceptionOrNull()?.message}", result.isSuccess)
            val card = result.getOrThrow()
            assertTrue("${file.name}：重复 id ${card.id}", ids.add(card.id))
        }
    }

    @Test fun friendNameAloneDoesNotClaimOmiButLegacyServiceStillMatches() {
        val card = parser.parse(File(directory, "omi.json").readText()).getOrThrow()
        val enabled = InstalledCard(card, CardSource.Bundled, enabled = true)
        val friend = DeviceAdvertisement("test", "Friend Pendant",
            setOf("1a3fd0e7-b1f3-ac9e-2e49-b647b2c4f8da"), emptySet(), null)
        assertNull(matchingCard(friend, listOf(enabled)))
        assertEquals(enabled, matchingCard(friend.copy(services = card.match.serviceUuid.toSet()), listOf(enabled)))
    }
}
