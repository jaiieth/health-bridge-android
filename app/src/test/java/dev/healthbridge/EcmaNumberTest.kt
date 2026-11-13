package dev.healthbridge

import org.junit.Assert.*
import org.junit.Test

class EcmaNumberTest {
    @Test fun matchesFixedSeedNodeJsonStringifyOracle() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ecma-number-fixtures.tsv"))
        val lines = stream.bufferedReader().use { it.readLines() }
        assertEquals(2016, lines.size)
        lines.forEach { line ->
            val (hex, expected) = line.split('\t')
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, 16))
            assertEquals(hex, expected, ecmaNumber(value))
        }
    }

    @Test fun roundsLongValuesLikeJavascriptBeforeStringifying() {
        assertEquals("9223372036854776000", ecmaNumber(Long.MAX_VALUE.toDouble()))
        assertEquals("9007199254740992", ecmaNumber(9007199254740993L.toDouble()))
    }

    @Test fun usesJavascriptDecimalAndExponentBoundaries() {
        assertEquals("1e-7", ecmaNumber(1e-7))
        assertEquals("0.000001", ecmaNumber(1e-6))
        assertEquals("100000000000000000000", ecmaNumber(1e20))
        assertEquals("1e+21", ecmaNumber(1e21))
        assertEquals("1e+23", ecmaNumber(1e23))
        assertEquals("5e-324", ecmaNumber(Double.MIN_VALUE))
    }
}
