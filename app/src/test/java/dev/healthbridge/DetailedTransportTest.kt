package dev.healthbridge

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DetailedTransportTest {
    @Test fun detailedRequestSignsAndSendsTheSameExactUtf8Bytes() {
        val bytes = "{\"schemaVersion\":1,\"batchId\":\"child_1\",\"records\":[{\"recordId\":\"😀中\"}]}".toByteArray(Charsets.UTF_8)
        val request = Transport.detailedRequest("https://synthetic.invalid/hook", "key", "child_1", bytes)
        assertEquals("https://synthetic.invalid/hook", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer key", request.header("Authorization"))
        assertEquals("child_1", request.header("X-Health-Bridge-Batch-Id"))
        assertEquals("sha256=1a9fd18580762a6b45794a5cff53c5288b986cd0142c1de81787dde293673d53", request.header("X-Health-Bridge-Signature"))
        val body = checkNotNull(request.body)
        assertEquals(bytes.size.toLong(), body.contentLength())
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
        val buffer = Buffer()
        body.writeTo(buffer)
        assertArrayEquals(bytes, buffer.readByteArray())
    }

    @Test fun invalidChildIdsCannotBuildAnOutboundRequest() {
        listOf("", "a b", "quote\"", "x".repeat(129)).forEach { id ->
            assertTrue(runCatching { Transport.detailedRequest("https://synthetic.invalid/hook", "key", id, byteArrayOf()) }.isFailure)
        }
    }
}
