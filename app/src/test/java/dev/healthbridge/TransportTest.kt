package dev.healthbridge

import org.junit.Assert.*
import org.junit.Test

class TransportTest {
    @Test
    fun strictHttps() {
        assertEquals(
            "https://example.com/hook?q=1",
            Transport.validateUrl("https://example.com/hook?q=1"),
        )
        listOf(
                "http://example.com",
                "https://user:pass@example.com",
                "https://example.com/#fragment",
                "https:///missing",
                "https://example.com:0",
                "https://example.com/ space",
            )
            .forEach { assertTrue(runCatching { Transport.validateUrl(it) }.isFailure) }
    }

    @Test
    fun hmacUsesExactBytes() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            Transport.signature("The quick brown fox jumps over the lazy dog".toByteArray(), "key"),
        )
        assertNotEquals(
            Transport.signature("{}".toByteArray(), "key"),
            Transport.signature("{}\n".toByteArray(), "key"),
        )
    }

    @Test
    fun retryStopsAtFive() {
        assertEquals(
            listOf(30000L, 60000L, 120000L, 240000L, 480000L),
            (1..5).map(RetryPolicy::delayMillis),
        )
        assertFalse(RetryPolicy.exhausted(4))
        assertTrue(RetryPolicy.exhausted(5))
    }
}
