package dev.healthbridge

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class ContainmentTest {
    private class SentinelStore : Store {
        val legacy = Pending(batchId = "retained", body = byteArrayOf(0, -1, 10),
            cursors = "{\"steps\":\"old\"}", attempts = 4, nextAttempt = 123, isTest = true)
        var saved = SyncStatus(lastSuccess = "yesterday", result = "Stale success")
        val events = mutableListOf<String>()
        override suspend fun pending(): Pending? = error("Pending read")
        override suspend fun put(p: Pending) { error("Pending write") }
        override suspend fun deletePending() { error("Pending delete") }
        override suspend fun cursors(): List<Cursor> = error("Cursor read")
        override suspend fun cursors(c: List<Cursor>) { error("Cursor write") }
        override suspend fun status(): SyncStatus = saved
        override suspend fun status(s: SyncStatus) { saved = s; events.add(s.result) }
    }

    private fun settings(enabled: Boolean = false, invalid: Boolean = false) = object : SyncSettings {
        override val syncEnabled = enabled
        override val endpoint: String get() = if (invalid) error("Endpoint read") else "https://example.com/hook"
        override val token: String get() = if (invalid) error("Token read") else "test-key"
    }

    @Test fun offReplacesStaleStatusBeforeEndpointOrPendingReads() = runBlocking {
        val store = SentinelStore()
        Containment.run(store, settings(invalid = true), false, { error("collect") }) { _, _, _, _ -> error("send") }
        assertEquals("Sync OFF", store.saved.result)
        assertEquals("yesterday", store.saved.lastSuccess)
        assertEquals(listOf("Sync OFF"), store.events)
    }

    @Test fun classification() {
        (200..299).forEach { assertEquals(Delivery.ACCEPTED, Containment.classify(it)) }
        (500..599).plus(listOf(0, 408, 425, 429)).forEach {
            assertEquals(Delivery.RETRY, Containment.classify(it))
        }
        (400..499).filter { it !in listOf(408, 425, 429) }
            .plus(listOf(-1, 100, 199, 300, 301, 399, 600)).forEach {
                assertEquals(Delivery.BLOCKED, Containment.classify(it))
            }
    }

    @Test fun isolatedTestBypassesOffAndSendsExactEmptyBytesAndHeadersOnce() = runBlocking {
        val store = SentinelStore()
        var sends = 0
        val batch = EmptyTest("123e4567-e89b-12d3-a456-426614174000")
        val expected = "{\"schemaVersion\":1,\"batchId\":\"123e4567-e89b-12d3-a456-426614174000\",\"records\":[]}"
        assertArrayEquals(expected.toByteArray(), batch.body)
        Containment.run(store, settings(), true, { error("collect") }) { endpoint, token, _, marker ->
            Transport.sendEmpty(endpoint, token, batch, marker) { request ->
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                assertEquals(expected, buffer.readUtf8())
                assertEquals("Bearer test-key", request.header("Authorization"))
                assertEquals(batch.batchId, request.header("X-Health-Bridge-Batch-Id"))
                assertEquals("sha256=" + Transport.signature(expected.toByteArray(), token),
                    request.header("X-Health-Bridge-Signature"))
                assertEquals("Stale success", store.saved.result)
                store.events.add("prepared")
                val execute: () -> Int = {
                    assertTrue(store.saved.result.contains("acceptance unknown"))
                    store.events.add("execute")
                    sends++
                    204
                }
                execute
            }
        }
        assertEquals(1, sends)
        assertEquals("prepared", store.events[0])
        assertTrue(store.events[1].contains("acceptance unknown"))
        assertEquals("execute", store.events[2])
        assertEquals("Empty webhook test: ACCEPTED HTTP 204; one shot, no automatic retry", store.saved.result)
        assertEquals("yesterday", store.saved.lastSuccess)
    }

    @Test fun pretransportFailuresNeverWriteUnknownAndAllowAnotherTest() = runBlocking {
        listOf("http://invalid", "https://example.com").forEach { endpoint ->
            val store = SentinelStore()
            var executions = 0
            Containment.run(store, settings(true), true, { error("collect") }) { _, token, batch, marker ->
                Transport.sendEmpty(endpoint, token, batch, marker) { error("Call construction failed") }
            }
            assertEquals(listOf("Empty webhook test: RETRY; request not sent; no automatic retry"), store.events)
            Containment.run(store, settings(true), true, { error("collect") }) { _, _, _, marker -> marker(); executions++; 200 }
            assertEquals(1, executions)
            assertTrue(store.saved.result.contains("ACCEPTED"))
        }
    }

    @Test fun blockedStatusIsDurableBeforeReturnWithoutSelfCancellationOrLegacyChanges() = runBlocking {
        listOf(400, 401, 405, 409, 413, 302).forEach { code ->
            val store = SentinelStore()
            val original = store.legacy.copy(body = store.legacy.body.copyOf())
            var sends = 0
            Containment.run(store, settings(true), true, { error("collect") }) { _, _, _, marker -> marker(); sends++; code }
            assertTrue(currentCoroutineContext().isActive)
            assertEquals("Empty webhook test: BLOCKED HTTP $code; one shot, no automatic retry", store.status().result)
            assertEquals(1, sends)
            assertEquals(original.batchId, store.legacy.batchId)
            assertArrayEquals(original.body, store.legacy.body)
            assertEquals(original.cursors, store.legacy.cursors)
            assertEquals(original.attempts, store.legacy.attempts)
            assertEquals(original.nextAttempt, store.legacy.nextAttempt)
            assertEquals(original.isTest, store.legacy.isTest)
        }
    }

    @Test fun retryableTestIsOneShot() = runBlocking {
        listOf(0, 408, 425, 429, 503).forEach { code ->
            val store = SentinelStore()
            var sends = 0
            Containment.run(store, settings(true), true, { error("collect") }) { _, _, _, marker -> marker(); sends++; code }
            assertEquals(1, sends)
            assertEquals("Empty webhook test: RETRY HTTP $code; one shot, no automatic retry", store.saved.result)
        }
    }

    @Test fun failureAfterSendEntryRetainsDurableUnknown() = runBlocking {
        val store = SentinelStore()
        Containment.run(store, settings(true), true, { error("collect") }) { endpoint, token, batch, marker ->
            Transport.sendEmpty(endpoint, token, batch, marker) {
                val execute: () -> Int = { error("Interrupted after entry") }
                execute
            }
        }
        assertEquals(listOf("BLOCKED: empty Test acceptance unknown; no automatic retry"), store.events)
    }

    @Test fun failedDurableMarkerPreventsExecution() = runBlocking {
        val saved = SentinelStore()
        val store = object : Store by saved {
            override suspend fun status(s: SyncStatus) {
                if (s.result.contains("acceptance unknown")) error("Status write failed")
                saved.status(s)
            }
        }
        var executed = false
        Containment.run(store, settings(true), true, { error("collect") }) { endpoint, token, batch, marker ->
            Transport.sendEmpty(endpoint, token, batch, marker) {
                val execute: () -> Int = { executed = true; 200 }
                execute
            }
        }
        assertFalse(executed)
        assertEquals(listOf("Empty webhook test: RETRY; request not sent; no automatic retry"), saved.events)
    }

    @Test fun settingsDefaultOffAndFalseCommitDoesNotThrowOrChangeSaveContract() {
        val values = mutableMapOf<String, Any>()
        var commits = 0
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putLong" -> { values[args!![0] as String] = args[1]; editor }
                "commit" -> { commits++; false }
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        val settings = Settings(prefs)
        assertFalse(settings.syncEnabled)
        settings.syncEnabled = true
        assertTrue(Settings(prefs).syncEnabled)
        settings.syncEnabled = false
        assertFalse(Settings(prefs).syncEnabled)
        assertEquals(2, commits)
        assertTrue(runCatching { settings.save("https://example.com", "key", 360) }.isFailure)
    }
}
