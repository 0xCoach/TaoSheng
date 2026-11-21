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
    private val old get() = File("src/test/resources/driver175/$name").readText().replace("\r\n", "\n")
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
        val triggers=Index183FixtureDelta.spec.optJSONArray("triggers")
        if (triggers != null) repeat(triggers.length()) { n ->
            val trigger=triggers.getString(n)
            val at=(1 until rows.size).first { rows[it] > trigger }
            rows.add(at,trigger)
        }
        for (master in Index183FixtureDelta.tableMasterRows) {
            val after = (1 until rows.size).first { rows[it] > master }
            rows.add(after, master)
        }
        for (block in Index183FixtureDelta.tableBlocks) {
            val at = rows.withIndex().first { it.value.startsWith("TABLE ") && it.value > block[0] }.index
            rows.addAll(at, block)
        }
        for (add in Index183FixtureDelta.columnAdds) {
            val table = add.getString("table")
            val masterOld = rows.first { it.startsWith("[\"table\",\"$table\"") }
            val masterAt = rows.indexOf(masterOld)
            val constraintAt = listOf(", FOREIGN KEY(", ", PRIMARY KEY(").map { masterOld.indexOf(it) }.filter { it >= 0 }.minOrNull()
            val cut = constraintAt ?: masterOld.lastIndexOf(')')
            val inserted = "," + add.getString("insertSql").substringAfter("ADD COLUMN")
            rows[masterAt] = masterOld.substring(0, cut) + inserted + masterOld.substring(cut)
            rows[rows.indexOf(add.getString("columnsOldLine"))] = add.getString("columnsNewLine")
            val dataAt = rows.indexOf("TABLE $table")
            val dataEnd = (dataAt + 2 until rows.size).firstOrNull { rows[it].isEmpty() || rows[it].startsWith("TABLE ") } ?: rows.size
            val suffix = add.getString("dataSuffix")
            for (k in dataAt + 2 until dataEnd) rows[k] = rows[k].dropLast(1) + suffix
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
        val schema = org.json.JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/${CaptureDatabase.VERSION}.json").readText()).getJSONObject("database")
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
        rejected(actual.replaceFirst("user_version=${CaptureDatabase.VERSION}", "user_version=${CaptureDatabase.VERSION + 1}"))
        rejected(actual.substringAfter('\n'))
        rejected("user_version=${CaptureDatabase.VERSION - 1}\n$actual")
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
        val original = File("src/test/resources/driver175/queries.txt").readText().replace("\r\n", "\n")
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
