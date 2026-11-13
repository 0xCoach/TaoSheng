package app.resound.search

import kotlin.math.sqrt

object Vectors {
    fun normalize(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        require(norm > 0)
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }
    fun encode(values: FloatArray): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { i, value ->
            val bits = value.toRawBits()
            repeat(4) { shift -> bytes[i * 4 + shift] = (bits ushr (shift * 8)).toByte() }
        }
    }
    fun decode(bytes: ByteArray, dim: Int): FloatArray {
        require(dim > 0 && bytes.size == dim * 4)
        return FloatArray(dim) { i ->
            var bits = 0
            repeat(4) { shift -> bits = bits or ((bytes[i * 4 + shift].toInt() and 255) shl (shift * 8)) }
            Float.fromBits(bits)
        }
    }
    fun cosine(a: FloatArray, b: FloatArray): Double = if (a.size != b.size) Double.NEGATIVE_INFINITY
        else a.indices.sumOf { a[it].toDouble() * b[it] }
    /** 两路等权 RRF，稳定去重；仅接收各路前 20 条。 */
    fun <T> merge(keyword: List<T>, semantic: List<T>): List<T> {
        val scores = linkedMapOf<T, Double>()
        for (list in listOf(keyword, semantic)) list.distinct().take(20).forEachIndexed { i, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (60 + i + 1)
        }
        return scores.keys.sortedByDescending { scores[it] }.take(10)
    }
}
