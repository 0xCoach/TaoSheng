package app.resound.devices

import app.resound.devices.core.CardParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeviceCards129Test {
    private val parser = CardParser("0.2.21")
    private val directory = File("src/main/assets/devices")

    @Test fun friendNameAloneDoesNotClaimOmiButLegacyServiceStillMatches() {
        val card = parser.parse(File(directory, "omi.json").readText()).getOrThrow()
        val enabled = InstalledCard(card, CardSource.Bundled, enabled = true)
        val friend = DeviceAdvertisement("test", "Friend Pendant",
            setOf("1a3fd0e7-b1f3-ac9e-2e49-b647b2c4f8da"), emptySet(), null)
        assertNull(matchingCard(friend, listOf(enabled)))
        assertEquals(enabled, matchingCard(friend.copy(services = card.match.serviceUuid.toSet()), listOf(enabled)))
    }
}
