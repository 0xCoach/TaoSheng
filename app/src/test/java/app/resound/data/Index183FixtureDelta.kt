package app.resound.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File

/** 只读、固定的版本差异白名单。未列入的快照（查询/引擎）仍逐字比较。 */
internal object Index183FixtureDelta {
    val spec: JSONObject by lazy { JSONObject(File("src/test/resources/index183/expected-delta.json").readText()) }
    private val snapshots get() = spec.getJSONArray("snapshots").let { a -> (0 until a.length()).map(a::getString).toSet() }
    val indexRows get() = spec.getJSONArray("indices").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("row") } }

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
        assertEquals(name, old, observed.joinToString("\n"))
    }
}
