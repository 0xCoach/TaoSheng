package app.resound.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** 只读、固定的版本差异白名单。未列入的快照（查询/引擎）仍逐字比较。 */
internal object Index183FixtureDelta {
    val spec: JSONObject by lazy { JSONObject(File("src/test/resources/index183/expected-delta.json").readText()) }
    private val snapshots get() = spec.getJSONArray("snapshots").let { a -> (0 until a.length()).map(a::getString).toSet() }
    val indexRows get() = spec.getJSONArray("indices").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("row") } }
    val tableMasterRows get() = spec.getJSONArray("tables").let { a -> (0 until a.length()).map { n -> a.getJSONObject(n).getJSONArray("masterRows").getString(0) } }
    val tableBlocks get() = spec.getJSONArray("tables").let { a -> (0 until a.length()).map { n ->
        a.getJSONObject(n).getJSONArray("block").let { b -> (0 until b.length()).map(b::getString) } } }

    fun assertSame(name: String, old: String, actual: String) {
        val sqlRules = spec.getJSONArray("querySql")
        val relevant = (0 until sqlRules.length()).map(sqlRules::getJSONObject).filter { it.getString("snapshot") == name }
        if (relevant.isNotEmpty()) {
            val expected = old.split('\n')
            val observed = actual.split('\n').toMutableList()
            for (rule in relevant) {
                fun find(lines: List<String>, exact: String): Int {
                    val header = "CASE ${rule.getString("case")}"
                    assertEquals("$name 查询案例次数", 1, lines.count { it == header })
                    val start = lines.indexOf(header)
                    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("CASE ") } ?: lines.size
                    val matches = (start + 1 until end).filter { lines[it] == exact }
                    assertEquals("$name 精确SQL行次数", rule.getInt("occurrences"), matches.size)
                    return matches.single()
                }
                find(expected, rule.getString("oldLine"))
                val at = find(observed, rule.getString("newLine"))
                observed[at] = rule.getString("oldLine")
            }
            assertEquals(name, old, observed.joinToString("\n"))
            return
        }
        if (name !in snapshots) { assertEquals(name, old, actual); return }
        val expected = old.split('\n')
        val observed = actual.split('\n').toMutableList()
        val version = spec.getJSONObject("version")
        assertEquals("固定新版本", "user_version=${CaptureDatabase.VERSION}", version.getString("newLine"))
        assertEquals("$name 旧版本行次数", version.getInt("occurrences"), expected.count { it.startsWith("user_version=") })
        assertEquals("$name 新版本行次数", version.getInt("occurrences"), observed.count { it.startsWith("user_version=") })
        assertEquals("$name 旧版本", version.getString("oldLine"), expected.first())
        assertEquals("$name 新版本", version.getString("newLine"), observed.first())
        observed[0] = version.getString("oldLine")

        val identity = spec.getJSONObject("identity")
        fun identityRow(lines: List<String>, row: String): Int {
            val header = "TABLE ${identity.getString("table")}"
            assertEquals("$name 身份表次数", 1, lines.count { it == header })
            val start = lines.indexOf(header)
            assertEquals("$name 身份列", identity.getString("columns"), lines[start + 1])
            val end = (start + 2 until lines.size).firstOrNull { lines[it].startsWith("TABLE ") } ?: lines.size
            val rows = lines.subList(start + 2, end).filter(String::isNotEmpty)
            assertEquals("$name 身份行次数", identity.getInt("occurrences"), rows.size)
            assertEquals("$name 身份完整行", listOf(row), rows)
            return start + 2
        }
        identityRow(expected, identity.getString("oldRow"))
        val row = identityRow(observed, identity.getString("newRow"))
        observed[row] = identity.getString("oldRow")

        val indices = spec.getJSONArray("indices")
        repeat(indices.length()) { n ->
            val rule = indices.getJSONObject(n)
            val exact = rule.getString("row")
            val prefix = "[\"index\",\"${rule.getString("name")}\","
            assertFalse("$name 旧夹具不应有新索引", expected.any { it.startsWith(prefix) })
            assertEquals("$name 索引名称次数", rule.getInt("occurrences"), observed.count { it.startsWith(prefix) })
            assertEquals("$name 索引完整行次数", rule.getInt("occurrences"), observed.count { it == exact })
            observed.removeAt(observed.indexOf(exact))
        }
        val triggers = spec.optJSONArray("triggers")
        if (triggers != null) repeat(triggers.length()) { i ->
            val exact = triggers.getString(i)
            assertEquals("$name 卡199触发器精确SQL", 1, observed.count { it == exact })
            observed.removeAt(observed.indexOf(exact))
        }
        val tables = spec.getJSONArray("tables")
        repeat(tables.length()) { n ->
            val rule = tables.getJSONObject(n)
            val prefix = "[\"table\",\"${rule.getString("name")}\","
            assertFalse("$name 旧夹具不应有新表", expected.any { it.startsWith(prefix) })
            val candidates = rule.getJSONArray("masterRows").let { a -> (0 until a.length()).map(a::getString) }
            assertEquals("$name 表 sqlite_master 行次数", rule.getInt("occurrences"), candidates.sumOf { c -> observed.count { it == c } })
            observed.removeAt(observed.indexOfFirst { it in candidates })
            val block = rule.getJSONArray("block").let { b -> (0 until b.length()).map(b::getString) }
            assertEquals("$name 表数据块次数", rule.getInt("occurrences"), observed.count { it == block[0] })
            val at = observed.indexOf(block[0])
            block.forEachIndexed { i, line -> assertEquals("$name 表数据块第${i + 1}行", line, observed[at + i]) }
            repeat(block.size) { observed.removeAt(at) }
        }
        // 已存在表加列：master 旧行取自旧夹具，新行必须等于「旧行+迁移插入」或「新装形态」，再还原回旧行。
        val add = spec.getJSONObject("columnAdds")
        val masterPrefix = "[\"table\",\"${add.getString("table")}\""
        val masterOld = expected.first { it.startsWith(masterPrefix) }
        assertEquals("$name segments master旧行次数", 1, expected.count { it.startsWith(masterPrefix) })
        val fkAt = masterOld.indexOf(", FOREIGN KEY(")
        val cut = if (fkAt < 0) masterOld.lastIndexOf(')') else fkAt
        val inserted = "," + add.getString("insertSql").substringAfter("ADD COLUMN")
        val migrated = masterOld.substring(0, cut) + inserted + masterOld.substring(cut)
        assertEquals("$name segments master新行次数", 1, observed.count { it.startsWith(masterPrefix) })
        val masterNews = observed.filter { it.startsWith(masterPrefix) }
        assertEquals("$name segments master新行次数", 1, masterNews.size)
        val masterNew = masterNews.single()
        assertTrue("$name segments master新行=旧行+迁移插入或新装形态", masterNew == migrated || masterNew == add.getString("masterFreshLine"))
        observed[observed.indexOfFirst { it.startsWith(masterPrefix) }] = masterOld
        val columnsOld = add.getString("columnsOldLine")
        val columnsNew = add.getString("columnsNewLine")
        assertEquals("$name segments columns旧行次数", 1, expected.count { it == columnsOld })
        assertEquals("$name segments columns新行次数", 1, observed.count { it == columnsNew })
        observed[observed.indexOf(columnsNew)] = columnsOld
        val suffix = add.getString("dataSuffix")
        fun block(lines: List<String>): List<String> {
            val at = lines.indexOf("TABLE ${add.getString("table")}")
            val end = (at + 2 until lines.size).firstOrNull { lines[it].isEmpty() || lines[it].startsWith("TABLE ") } ?: lines.size
            return lines.subList(at + 2, end)
        }
        val dataAt = observed.indexOf("TABLE ${add.getString("table")}")
        val dataEnd = (dataAt + 2 until observed.size).firstOrNull { observed[it].isEmpty() || observed[it].startsWith("TABLE ") } ?: observed.size
        val oldData = block(expected)
        assertEquals("$name segments数据行数", oldData.size, dataEnd - dataAt - 2)
        for (k in dataAt + 2 until dataEnd) {
            assertTrue("$name segments数据行=旧行+新列默认值", observed[k].endsWith(suffix))
            observed[k] = observed[k].removeSuffix(suffix) + "]"
        }
        assertEquals("$name segments数据行=旧行+新列默认值", oldData.sorted(), observed.subList(dataAt + 2, dataEnd).sorted())
        assertEquals(name, old, observed.joinToString("\n"))
    }
}
