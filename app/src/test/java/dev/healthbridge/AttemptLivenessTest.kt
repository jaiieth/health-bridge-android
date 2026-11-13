package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttemptLivenessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedUnpublishedCrashesRemainQuotaBoundedButEventuallyRefuseAdmission() = runBlocking {
        val work = temporary.newFolder()
        val root = File(work, "outbox")
        val cursorFile = File(work, "test-cursors")
        val input = FreshChunkInput(List(42) { syntheticRecord("r-$it", "x".repeat(520_000)) },
            mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
        val rows = mutableListOf<String>()
        var crashes = 0
        var refused = 0
        var previousBytes = 0L
        for (cycle in 1..7) {
            val before = retainedInventory(root)
            var ids = 0
            var sends = 0
            val outbox = ChunkOutbox(root, DurableFiles(fault = { at, name ->
                if (name == "child-41.json" && at == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath()
            }), releaseFault = { _, _ -> error("No release authority") })
            val runner = ChunkSync(outbox, DiskTestCursors(cursorFile), { _, _ -> sends++; error("No sends before publication") },
                newId = { "cycle-$cycle-${ids++}" }, limits = ChunkLimits(count = 1))
            val outcome = try {
                val status = runner.run(true, "https://synthetic.invalid", syntheticMapping) { input }
                assertEquals(ChunkReason.OUTBOX_QUOTA, status.reason)
                refused++
                "OUTBOX_QUOTA"
            } catch (_: ProcessDeath) { crashes++; "ADMITTED_CRASH" }
            assertEquals(0, sends)
            val restarted = ChunkOutbox(root, releaseFault = { _, _ -> error("No release") })
            assertNull(restarted.recoverStaging())
            restarted.reclaimDone(DiskTestCursors(cursorFile))
            val after = retainedInventory(root)
            before.forEach { (path, bytes) -> assertEquals(bytes, after[path]) }
            val retained = restarted.retainedBytes()
            val newBytes = retained - previousBytes
            assertTrue(retained + restarted.controlBytes() <= 64L * 1024 * 1024)
            assertEquals(3, RETAINED_DONE_GENERATIONS)
            if (outcome == "OUTBOX_QUOTA") assertEquals(0L, newBytes)
            rows += "$cycle\t$cycle\t$crashes\t$newBytes\t$retained\t$outcome"
            previousBytes = retained
        }
        assertEquals(3, crashes)
        assertEquals(4, refused)
        assertFalse(cursorFile.exists())
        val snapshot = retainedInventory(root)
        repeat(3) { assertNull(ChunkOutbox(root).recoverStaging()); assertEquals(snapshot, retainedInventory(root)) }
        println("LIVENESS cycle\tattempts\towned\tnew_retained_bytes\tretained_bytes\tadmission\n" + rows.joinToString("\n"))
        println("BLOCKER=unpublished-intents-have-no-release-authority; bounded-storage-is-not-forward-progress")
    }
}
