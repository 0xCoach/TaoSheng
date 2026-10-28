package app.resound.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsManifestAndUsesOnlyHigherVersionAndMatchingInstalledHash() {
        val release = UpdateManifest.parse(fixtureManifest().json())
        assertEquals("0.2.11", release.versionName)
        assertEquals("涛声更新\n支持应用内更新", release.notes)
        assertTrue(release.newerThan(25))
        assertFalse(release.newerThan(26))
        assertFalse(release.newerThan(27))
        val patch = release.patchFor(fixture("old.bin").updateSha256().uppercase())
        assertNotNull(patch)
        assertTrue(release.info(patch).incremental)
        assertEquals(fixture("update.patch").length(), release.info(patch).downloadBytes)
        assertNull(release.patchFor("0".repeat(64)))
        assertEquals(release.apk.size, release.info(null).downloadBytes)
    }

    @Test fun missingFieldsAndMalformedHashesRejectTheWholeSource() {
        val json = fixtureManifest().json()
        val missing = JSONObject(json).apply { remove("notes") }
        val badHash = JSONObject(json).apply { getJSONObject("apk").put("sha256", "g".repeat(64)) }
        val missingPatchField = JSONObject(json).apply { getJSONArray("patches").getJSONObject(0).remove("fromSha256") }
        for (invalid in listOf(missing, badHash, missingPatchField)) {
            assertThrows(Exception::class.java) { UpdateManifest.parse(invalid.toString()) }
        }
    }

    @Test fun streamsStandardMacBsdiffFixtureIncludingInitialZeroLengthSeek() {
        val output = File(temporary.root, "new.apk")
        val hash = Bspatch.apply(fixture("old.bin"), fixture("update.patch"), output, fixture("new.bin").length())
        assertArrayEquals(fixture("new.bin").readBytes(), output.readBytes())
        assertEquals(fixture("new.bin").updateSha256(), hash)
    }

    @Test fun rejectsTruncatedPatchAndDeletesPartialOutput() {
        val patch = temporary.newFile("broken.patch").apply { writeBytes(fixture("update.patch").readBytes().dropLast(12).toByteArray()) }
        val output = File(temporary.root, "partial.apk")
        assertThrows(Exception::class.java) { Bspatch.apply(fixture("old.bin"), patch, output, fixture("new.bin").length()) }
        assertFalse(output.exists())
    }
}

internal fun fixture(name: String) = File(requireNotNull(UpdateFilesTest::class.java.getResource("/update/$name")).toURI())
internal fun fixtureManifest(patchFile: File? = fixture("update.patch"), newFile: File = fixture("new.bin")) = UpdateManifest(
    26, "0.2.11", "涛声更新\n支持应用内更新",
    UpdateAsset("TaoSheng-v0.2.11-arm64.apk", newFile.updateSha256(), newFile.length()),
    patchFile?.let { listOf(UpdatePatch(fixture("old.bin").updateSha256(), UpdateAsset("update.patch", it.updateSha256(), it.length()))) }.orEmpty(),
)
