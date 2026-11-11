package app.resound.devices

import app.resound.update.UpdateRemote
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteDeviceCardsTest {
    private val key = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private class Store : CardsStore {
        var data = emptyList<SignedCards>(); val choices = mutableMapOf<String, Boolean>()
        override fun cached() = data
        override fun save(tables: List<SignedCards>) { data = tables }
        override fun enabled(id: String) = choices[id]
        override fun enable(id: String, enabled: Boolean) { choices[id] = enabled }
        override var lastAttempt = 0L
    }
    private fun card(v: Int = 1, status: String = "trial", id: String = "test-card") = """{"id":"$id","name":"设备","version":$v,"minApp":"0.2.21","status":"$status","transport":"ble","match":{"namePrefix":["Test"]}}"""
    private fun signed(text: String): SignedCards {
        val bytes = text.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(bytes); sign()
        }
        return SignedCards(bytes, signature)
    }
    private fun table(v: Int = 1, c: String = card()) = signed("""{"version":$v,"issuedAt":"2026-11-09T00:00:00Z","cards":[$c]}""")
    private fun engine(store: Store = Store(), bundled: List<String> = emptyList(), logs: MutableList<String> = mutableListOf()) =
        RemoteDeviceCards(bundled, store, key.public.encoded, "0.2.21", { logs.add(it) })

    @Test fun signatureTamperAndMalformedKeepPrevious() {
        val store = Store(); val logs = mutableListOf<String>(); val e = engine(store, logs = logs)
        assertTrue(e.accept(table()))
        val bad = table(2, card(2)); bad.json[10] = (bad.json[10].toInt() xor 1).toByte()
        assertFalse(e.accept(bad)); assertFalse(e.accept(signed("{}")))
        assertEquals(1, e.cards.value.single().card.version); assertEquals(1, store.data.size)
        assertTrue(logs.contains("signature_invalid"))
    }
    @Test fun rollbackPerCardAndTableSurvivesRestart() {
        val s = Store(); val e = engine(s)
        assertTrue(e.accept(table(3, card(5))))
        assertFalse(e.accept(table(2, card(6))))
        assertTrue(e.accept(table(4, card(2))))
        val restored = engine(s)
        assertEquals(5, restored.cards.value.single().card.version)
        assertFalse(restored.accept(table(3, card(9))))
    }
    @Test fun defaultsAndExplicitChoicesSurviveUpdateAndRestart() {
        val s = Store(); val e = engine(s, listOf(card(), card(status="verified", id="verified-card")))
        assertFalse(e.cards.value.first { it.card.id == "test-card" }.enabled)
        assertTrue(e.cards.value.first { it.card.id == "verified-card" }.enabled)
        e.setEnabled("test-card", true); e.setEnabled("verified-card", false)
        assertTrue(e.accept(table(1, card(2) + "," + card(2, "verified", "verified-card"))))
        val restored = engine(s)
        assertTrue(restored.cards.value.first { it.card.id == "test-card" }.enabled)
        assertFalse(restored.cards.value.first { it.card.id == "verified-card" }.enabled)
    }
    @Test fun failuresFallbackToCacheOrBundleAndThrottle() {
        val s = Store(); val e = engine(s, listOf(card()))
        var calls = 0
        e.refresh(RemoteDeviceCards.DAY) { calls++; error("offline") }
        e.refresh(RemoteDeviceCards.DAY + 1) { calls++ }
        assertEquals(1, calls); assertEquals(CardSource.Bundled, e.cards.value.single().source)
        assertTrue(e.accept(table(1, card(2))))
        val restored = engine(s, listOf(card()))
        restored.refresh(RemoteDeviceCards.DAY * 2) { error("offline") }
        assertEquals(2, restored.cards.value.single().card.version)
    }
    @Test fun unknownFieldsAndMinAppAreHandled() {
        val e = engine()
        assertTrue(e.parse(card().replace("\"match\":{", "\"match\":{\"execute\":\"x\",")).isFailure)
        assertTrue(e.accept(table(1, card().replace("0.2.21", "0.2.22"))))
        assertTrue(e.cards.value.isEmpty())
        assertFalse(e.accept(table(2, card() + "," + card())))
    }
    @Test fun corruptedCacheFallsBackAndWriteFailureDoesNotPublish() {
        val s = Store(); val signed = table(); signed.signature[0] = (signed.signature[0].toInt() xor 1).toByte()
        s.data = listOf(signed)
        assertEquals(CardSource.Bundled, engine(s, listOf(card())).cards.value.single().source)
        val failedStore = object : CardsStore by s { override fun save(tables: List<SignedCards>) { error("disk") } }
        val e = RemoteDeviceCards(listOf(card()), failedStore, key.public.encoded, "0.2.21")
        assertFalse(e.accept(table(2, card(2))))
        assertEquals(1, e.cards.value.single().card.version)
    }
    @Test fun mirrorFallbackUsesMatchingSignature() {
        MockWebServer().use { github -> MockWebServer().use { mirror ->
            github.enqueue(MockResponse().setResponseCode(503))
            val t = table()
            mirror.enqueue(MockResponse().setBody(okio.Buffer().write(t.json)))
            mirror.enqueue(MockResponse().setBody(okio.Buffer().write(t.signature)))
            val e = engine()
            UpdateRemote().deviceCards({ e.accept(it) }, listOf(github.url("/device-cards.json").toString(), mirror.url("/device-cards.json").toString()))
            assertEquals(1, e.cards.value.size)
            assertEquals("/device-cards.json", mirror.takeRequest().path)
            assertEquals("/device-cards.json.sig", mirror.takeRequest().path)
        } }
    }
    @Test fun bundledRealCardsLoadAndPlaceholderWithoutMatchStaysIsolated() {
        // FoloToy 占位卡已从包里移除（实时声音走 Wi-Fi，见卡 129 调研）；没有识别规则的卡仍应被隔离
        val texts = listOf(java.io.File("src/main/assets/devices/omi.json").readText(), javaClass.getResource("/devices/synthetic.json")!!.readText()) +
            """{"id":"placeholder","name":"占位","version":1,"minApp":"0.2.21","status":"trial","transport":"ble","match":{}}"""
        val e = engine(bundled = texts)
        assertEquals(listOf("omi", "synthetic"), e.cards.value.map { it.card.id })
        assertTrue(e.cards.value.all { it.card.status == CardStatus.Trial && !it.enabled })
        assertTrue(e.parse(texts.last()).exceptionOrNull()!!.message!!.contains("识别规则"))
    }

    @Test fun pythonDerSignatureWorksAndInvalidKeysAndSignaturesKeepBundle() {
        val fixture = JSONObject(java.io.File("src/test/resources/devices/p256-python-vector.json").readText())
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val table = SignedCards(fixture.getString("table").toByteArray(), hex(fixture.getString("signature")))
        val public = hex(fixture.getString("publicKey"))
        val logs = mutableListOf<String>()
        fun loader(keyBytes: ByteArray) = RemoteDeviceCards(listOf(card()), Store(), keyBytes, "0.2.21", { logs.add(it) })
        val good = loader(public)
        assertTrue(good.accept(table))
        assertEquals(2, good.cards.value.single().card.version)
        assertFalse(good.accept(table.copy(signature = byteArrayOf(0x30, 0))))
        assertEquals(2, good.cards.value.single().card.version)
        val wrongCurve = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(wrongCurve.private); update(table.json); sign()
        }
        assertFalse(loader(wrongCurve.public.encoded).accept(table.copy(signature = signature)))
        for (badKey in listOf(byteArrayOf(), ByteArray(32), key.public.encoded)) {
            val bad = loader(badKey)
            assertFalse(bad.accept(table))
            assertEquals(CardSource.Bundled, bad.cards.value.single().source)
        }
        assertTrue(logs.contains("signature_invalid"))
    }
}
