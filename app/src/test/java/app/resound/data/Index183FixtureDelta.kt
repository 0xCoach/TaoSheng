package app.resound.data

import org.json.JSONArray
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
        val old = old.replace("\r\n", "\n")
        val actual = actual.replace("\r\n", "\n")
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
        val allowedNewRows = identity.optJSONArray("extraNewRows")?.let { a -> (0 until a.length()).map(a::getString).toSet() }
            .orEmpty() + setOf(identity.getString("newRow"))
        fun identityRow(lines: List<String>, expectedRow: String?, allowed: Set<String>): Int {
            val header = "TABLE ${identity.getString("table")}"
            assertEquals("$name 身份表次数", 1, lines.count { it == header })
            val start = lines.indexOf(header)
            assertEquals("$name 身份列", identity.getString("columns"), lines[start + 1])
            val end = (start + 2 until lines.size).firstOrNull { lines[it].startsWith("TABLE ") } ?: lines.size
            val rows = lines.subList(start + 2, end).filter(String::isNotEmpty)
            assertEquals("$name 身份行次数", identity.getInt("occurrences"), rows.size)
            if (expectedRow != null) assertEquals("$name 身份完整行", listOf(expectedRow), rows)
            else assertTrue("$name 身份行不在允许集内：$rows", rows.all { it in allowed })
            return start + 2
        }
        identityRow(expected, identity.getString("oldRow"), emptySet())
        val row = identityRow(observed, null, allowedNewRows)
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
        spec.optJSONArray("tables")?.let { tables ->
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
        }
        spec.optJSONObject("columnAdds")?.let { add ->
            // 已存在表加列：master 旧行取自旧夹具，新行必须等于「旧行+迁移插入」或「新装形态」，再还原回旧行。
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
        }
        val dropped = spec.optJSONArray("droppedTables") ?: JSONArray()
        repeat(dropped.length()) { n ->
            val table = dropped.getString(n)
            val prefixes = listOf("[\"table\",\"$table\",", "[\"index\",\"index_${table}_", "[\"index\",\"sqlite_autoindex_${table}_")
            assertFalse("$name 旧夹具不应有新表", expected.any { line -> prefixes.any(line::startsWith) })
            observed.removeAll { line -> prefixes.any(line::startsWith) }
            val at = observed.indexOf("TABLE $table")
            if (at >= 0) {
                var end = at + 1
                while (end < observed.size && observed[end].isNotEmpty()) end++
                if (end < observed.size) end++
                observed.subList(at, end).clear()
            }
        }

        // 存量表加列：建表语句与 columns 行都换成旧夹具对应行（按表名对齐，不把整行抄进白名单）。
        val changedTables = spec.optJSONArray("changedTables") ?: JSONArray()
        repeat(changedTables.length()) { n ->
            val table = changedTables.getString(n)
            val createPrefix = "[\"table\",\"$table\","
            val eCreate = expected.firstOrNull { it.startsWith(createPrefix) }
            val oAt = observed.indexOfFirst { it.startsWith(createPrefix) }
            assertTrue("$name 新表结构未在旧夹具找到 $table", oAt < 0 || eCreate != null)
            if (oAt >= 0 && eCreate != null) observed[oAt] = eCreate
            val eT = expected.indexOf("TABLE $table"); val oT = observed.indexOf("TABLE $table")
            if (eT >= 0 && oT >= 0 && eT + 1 < expected.size && oT + 1 < observed.size &&
                expected[eT + 1].startsWith("columns=") && observed[oT + 1].startsWith("columns=")) {
                observed[oT + 1] = expected[eT + 1]
                // 数据行：新列值追加在行尾，按旧夹具的行宽截掉（存量行除新列外逐值相同）。
                val eRows = expected.subList(eT + 2, blockEnd(expected, eT + 2)).filter { it.startsWith("[{") }
                for ((k, i) in (oT + 2 until blockEnd(observed, oT + 2)).filter { observed[it].startsWith("[{") }.withIndex()) {
                    if (k < eRows.size) observed[i] = truncateCells(observed[i], cellCount(eRows[k]))
                }
            }
        }
        assertEquals(name, old, observed.joinToString("\n"))
    }

    private fun blockEnd(lines: List<String>, from: Int): Int =
        (from until lines.size).firstOrNull { lines[it].isEmpty() || lines[it].startsWith("TABLE ") } ?: lines.size

    private fun cellCount(row: String): Int {
        var depth = 0; var inStr = false; var cells = 0
        for (c in row) {
            if (inStr) { if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> depth--
                ',' -> if (depth == 1) cells++
            }
        }
        return cells + 1
    }

    private fun truncateCells(row: String, keep: Int): String {
        var depth = 0; var inStr = false; var cells = 0
        for (i in row.indices) {
            val c = row[i]
            if (inStr) { if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return row }
                ',' -> if (depth == 1) { cells++; if (cells == keep) return row.substring(0, i) + "]" }
            }
        }
        return row
    }
}
