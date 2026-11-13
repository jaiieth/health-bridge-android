package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncWiringTest {
    @get:Rule val temporary = TemporaryFolder()

    private class ObservedStore : Store {
        val legacy = Pending(batchId = "retained", body = byteArrayOf(0, -1, 10, 34),
            cursors = "{\"steps\":\"old\"}", attempts = 4, nextAttempt = 123, isTest = true)
        var row: Pending? = legacy
        var pendingReads = 0
        var cursorReads = 0
        var cursorWrites = 0
        var values = listOf(Cursor("steps", "old"), Cursor("sleep", "old"))
        var saved = SyncStatus(lastSuccess = "yesterday", result = "Stale success")
        override suspend fun pending(): Pending? { pendingReads++; return row }
        override suspend fun put(p: Pending) { error("Pending write") }
        override suspend fun deletePending() { error("Pending delete") }
        override suspend fun cursors(): List<Cursor> { cursorReads++; return values }
        override suspend fun cursors(c: List<Cursor>) { cursorWrites++; values = c }
        override suspend fun status(): SyncStatus = saved
        override suspend fun status(s: SyncStatus) { saved = s }
    }

    private fun settings(enabled: Boolean) = object : SyncSettings {
        override val syncEnabled = enabled
        override val endpoint: String get() = error("Credentials read before health path")
        override val token: String get() = error("Credentials read before health path")
    }

    @Test fun switchOnSchedulesThenWorkerContainmentDeliversFreshChunksWithoutPending() = runBlocking {
        val fixture = SyncControlTest.Fixture(false)
        val store = ObservedStore().apply { row = null }
        val chunks = ChunkHarness(File(temporary.newFolder(), "outbox"))
        assertTrue(fixture.control.setEnabled(true))
        assertEquals(listOf("schedule:75"), fixture.calls)
        var result: ChunkStatus? = null
        Containment.run(store, fixture.settings, false, {
            result = chunks.run(enabled = fixture.settings.syncEnabled)
        }) { _, _, _, _ -> error("Empty Test transport") }
        assertEquals(ChunkPhase.DONE, result?.phase)
        assertEquals(1, chunks.collectCalls)
        assertEquals(2, chunks.sent.size)
        assertEquals(1, chunks.cursors.commits)
        assertEquals(1, store.pendingReads)
        assertNull(store.row)
    }

    @Test fun offStopsHealthBeforePendingCredentialsCollectionAndTransport() = runBlocking {
            val store = ObservedStore()
            Containment.run(store, settings(false), false, { error("Health collection") }) { _, _, _, _ -> error("Transport") }
            assertEquals("Sync OFF", store.saved.result)
            assertEquals("yesterday", store.saved.lastSuccess)
            assertEquals(0, store.pendingReads)
            assertEquals(0, store.cursorReads)
            assertEquals(0, store.cursorWrites)
    }

    @Test fun onTestWithPendingAndActiveOutboxSendsOnlyEmptyTestAndPreservesBoth() = runBlocking {
        val store = ObservedStore()
        val before = store.legacy.copy(body = store.legacy.body.copyOf())
        val chunks = ChunkHarness(File(temporary.newFolder(), "outbox"))
        chunks.fault = { boundary, name ->
            if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath()
        }
        try { chunks.run(); fail("Expected staged process death") } catch (_: ProcessDeath) {}
        assertEquals(ChunkPhase.STAGE, chunks.outbox.load()!!.phase)
        val outboxBefore = chunks.root.walkTopDown().filter { it.isFile }
            .associate { it.path to it.readBytes().toList() }
        val settings = object : SyncSettings {
            override val syncEnabled = true
            override val endpoint = "https://synthetic.invalid/hook"
            override val token = "synthetic-key"
        }
        var sends = 0
        Containment.run(store, settings, true, { error("Health collection") }) { endpoint, token, batch, marker ->
            Transport.sendEmpty(endpoint, token, batch, marker) { request ->
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                assertEquals("{\"schemaVersion\":1,\"batchId\":\"${batch.batchId}\",\"records\":[]}", buffer.readUtf8())
                val execute: () -> Int = { sends++; 204 }
                execute
            }
        }
        assertEquals(1, sends)
        assertEquals(outboxBefore, chunks.root.walkTopDown().filter { it.isFile }
            .associate { it.path to it.readBytes().toList() })
        assertTrue(chunks.sent.isEmpty())
        assertEquals(0, chunks.cursors.commits)
        assertEquals(0, store.pendingReads)
        assertEquals(0, store.cursorReads)
        assertEquals(0, store.cursorWrites)
        assertEquals(before.copy(body = store.row!!.body), store.row)
        assertArrayEquals(before.body, store.row!!.body)
        assertEquals("Empty webhook test: ACCEPTED HTTP 204; one shot, no automatic retry", store.saved.result)
    }

    @Test fun onPendingRefusesHealthBeforeCursorsCollectionCredentialsOrTransport() = runBlocking {
        val store = ObservedStore()
        Containment.run(store, settings(true), false, { error("Health collection") }) { _, _, _, _ -> error("Transport") }
        assertEquals("Health sync PAUSED: legacy Pending preserved; delivery blocked", store.saved.result)
        assertEquals(1, store.pendingReads)
        assertEquals(0, store.cursorReads)
        assertEquals(0, store.cursorWrites)
        assertSame(store.legacy, store.row)
        assertArrayEquals(byteArrayOf(0, -1, 10, 34), store.row!!.body)
    }

    @Test fun completeDurableAckSetCommitsOnceEvenAfterCrashBetweenRoomAndDone() = runBlocking {
        val store = ObservedStore().apply { row = null }
        val root = File(temporary.newFolder(), "outbox")
        var outbox = ChunkOutbox(root)
        var collections = 0
        var sends = 0
        var nextId = 0
        var crashAfterCommit = true
        val adapter = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = store.pending() != null
            override suspend fun commit(targets: Map<String, String>): Boolean {
                val result = store.commitChunkCursors(targets.map { Cursor(it.key, it.value) })
                if (crashAfterCommit) throw ProcessDeath()
                return result
            }
        }
        suspend fun run() = Containment.run(store, settings(true), false, {
            val runner = ChunkSync(outbox, adapter, send = { id, _ ->
                val staged = outbox.load()!!
                assertEquals(ChunkPhase.SEND, staged.phase)
                assertEquals(0, store.cursorWrites)
                sends++
                ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\"}")
            }, newId = { "wiring-${nextId++}" }, limits = ChunkLimits(count = 1))
            runner.run(true, "https://synthetic.invalid/hook", syntheticMapping) {
                collections++
                FreshChunkInput(listOf(syntheticRecord("a"), syntheticRecord("b")), mapOf("steps" to "new", "sleep" to "new"))
            }
            Unit
        }) { _, _, _, _ -> error("Empty Test transport") }
        try { run(); fail("Expected process death") } catch (_: ProcessDeath) {}
        assertEquals(1, store.cursorWrites)
        assertEquals(setOf(Cursor("steps", "new"), Cursor("sleep", "new")), store.values.toSet())
        assertEquals(ChunkPhase.COMMIT, outbox.load()!!.phase)
        assertTrue(outbox.load()!!.children.all { it.acked && it.ackedAt != null })
        outbox = ChunkOutbox(root)
        crashAfterCommit = false
        run()
        assertEquals(ChunkPhase.DONE, outbox.load()!!.phase)
        assertEquals(1, collections)
        assertEquals(2, sends)
        assertEquals(1, store.cursorWrites)
    }
}
