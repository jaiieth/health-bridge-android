package dev.healthbridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkRestartMatrixTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restartAfterEveryAcknowledgedPrefixKeepsIdsBytesAndCursorBarrier() {
        for (acked in 0..4) {
            val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
            h.input = h.input.copy(records = List(4) { syntheticRecord("r$it", "nested\"\\\n中😀") })
            h.fault = { boundary, name ->
                if (boundary == DurableBoundary.DIRECTORY_SYNCED) {
                    if (acked == 0 && name == "active") throw ProcessDeath()
                    if (acked > 0 && name == "manifest.json") {
                        val manifest = h.outbox.load()
                        if (manifest?.phase == ChunkPhase.ACK && manifest.children.count { it.acked } == acked) throw ProcessDeath()
                    }
                }
            }
            try { h.run(); fail("Expected process death after $acked ACKs") } catch (_: ProcessDeath) {}
            val staged = checkNotNull(h.outbox.load())
            val bodies = staged.children.indices.map { h.outbox.body(staged, it) }
            assertEquals(acked, h.sent.size)
            assertEquals(0, h.cursors.commits)
            assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
            h.fault = { _, _ -> }
            assertEquals(ChunkPhase.DONE, h.run().phase)
            assertEquals(staged.children.map { it.batchId }, h.sent.map { it.first })
            assertEquals(4, h.sent.size)
            bodies.zip(h.sent).forEach { (expected, actual) -> assertArrayEquals(expected, actual.second) }
            assertEquals(1, h.cursors.commits)
            assertEquals(mapOf("steps" to "new", "sleep" to "new"), h.cursors.values)
        }
    }

    @Test fun everySendIntentWriteBoundaryEitherResumesUnsentOrPausesUnknown() {
        DurableBoundary.entries.forEach { injected ->
            val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
            h.fault = { boundary, name ->
                if (name == "manifest.json" && boundary == injected) {
                    val published = boundary == DurableBoundary.RENAMED || boundary == DurableBoundary.DIRECTORY_SYNCED
                    val target = h.root.walkTopDown().firstOrNull { it.name == if (published) "manifest.json" else "manifest.json.tmp" }
                    if (target != null && JSONObject(target.readText()).getString("phase") == "SEND") throw ProcessDeath()
                }
            }
            try { h.run(); fail("Expected process death at $injected") } catch (_: ProcessDeath) {}
            assertTrue(h.sent.isEmpty())
            h.fault = { _, _ -> }
            val status = h.run()
            if (injected == DurableBoundary.TEMP_WRITTEN || injected == DurableBoundary.FILE_SYNCED) {
                assertEquals(ChunkPhase.DONE, status.phase)
                assertEquals(2, h.sent.size)
                assertEquals(1, h.cursors.commits)
            } else {
                assertEquals(ChunkPhase.PAUSED, status.phase)
                assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, status.reason)
                assertTrue(h.sent.isEmpty())
                assertEquals(0, h.cursors.commits)
            }
        }
    }
}
