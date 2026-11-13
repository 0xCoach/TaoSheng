package app.resound.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Index183ComparatorTest {
    private val name = "queries-seed.txt"
    private val old get() = File("src/test/resources/driver175/$name").readText()
    /** 仅为反例构建合法输入；真实Room快照由原175的全部重放和新迁移用例校验。 */
    private fun allowed(): String {
        val rows = old.split('\n').toMutableList()
        rows[0] = Index183FixtureDelta.spec.getJSONObject("version").getString("newLine")
        val id = Index183FixtureDelta.spec.getJSONObject("identity")
        val start = rows.indexOf("TABLE room_master_table")
        rows[start + 2] = id.getString("newRow")
        for (index in Index183FixtureDelta.indexRows) {
            val after = (1 until rows.size).first { rows[it] > index }
            rows.add(after, index)
        }
        return rows.joinToString("\n")
    }
    private fun rejected(text: String) {
        assertThrows(AssertionError::class.java) { Index183FixtureDelta.assertSame(name, old, text) }
    }
    @Test fun exactDeltaPassesAndUnlistedSnapshotsStayExact() {
        Index183FixtureDelta.assertSame(name, old, allowed())
        Index183FixtureDelta.assertSame("engine.txt", "untouched", "untouched")
        assertThrows(AssertionError::class.java) { Index183FixtureDelta.assertSame("engine.txt", "untouched", "changed") }
        val schema = org.json.JSONObject(File("schemas/app.resound.data.CaptureDatabase/${CaptureDatabase.VERSION}.json").readText()).getJSONObject("database")
        assertEquals(schema.getString("identityHash"), Index183FixtureDelta.spec.getString("newIdentityHash"))
    }
    @Test fun missingExtraAndWrongIndexDefinitionsFail() {
        val actual = allowed(); val index = Index183FixtureDelta.indexRows.first()
        rejected(actual.replace(index + "\n", ""))
        rejected(actual.replace(index, "$index\n$index"))
        rejected(actual.replace(index, index.replace("CREATE INDEX", "CREATE UNIQUE INDEX")))
    }
    @Test fun wrongMissingOrDuplicateVersionAndWrongIdentityFail() {
        val actual = allowed()
        rejected(actual.replaceFirst("user_version=34", "user_version=35"))
        rejected(actual.substringAfter('\n'))
        rejected("user_version=34\n$actual")
        val identity = Index183FixtureDelta.spec.getJSONObject("identity")
        rejected(actual.replace(identity.getString("newRow"), identity.getString("oldRow")))
        rejected(actual.replace(identity.getString("newRow"), identity.getString("newRow") + "\n" + identity.getString("newRow")))
    }
    @Test fun aSingleBusinessValueChangeFails() {
        val actual = allowed()
        assertTrue(actual.contains("交通发票1"))
        rejected(actual.replaceFirst("交通发票1", "被改动的一句话"))
    }
    @Test fun sqlDeltaIsLimitedToNamedCasesAndNeverChangesArgumentsOrResults() {
        val original = File("src/test/resources/driver175/queries.txt").readText()
        val lines = original.split('\n').toMutableList()
        val rules = Index183FixtureDelta.spec.getJSONArray("querySql")
        repeat(rules.length()) { i ->
            val rule = rules.getJSONObject(i)
            val start = lines.indexOf("CASE ${rule.getString("case")}")
            val at = (start + 1 until lines.size).first { lines[it] == rule.getString("oldLine") }
            lines[at] = rule.getString("newLine")
        }
        val actual = lines.joinToString("\n")
        Index183FixtureDelta.assertSame("queries.txt", original, actual)
        assertThrows(AssertionError::class.java) {
            Index183FixtureDelta.assertSame("queries.txt", original, actual.replaceFirst("ARGS [1]", "ARGS [2]"))
        }
        assertThrows(AssertionError::class.java) {
            Index183FixtureDelta.assertSame("queries.txt", original, actual.replaceFirst("NOT INDEXED WHERE", "NOT INDEXED WHERE 0 AND"))
        }
    }
}
