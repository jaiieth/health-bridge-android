package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewIntentRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun baseCompletedCleanupRetainsValidMalformedAndMissingIntentEvidence() = runBlocking {
        for (mutation in listOf("none", "malformed", "missing-reservation", "missing-intent")) {
            val work = temporary.newFolder()
            val root = File(work, "outbox")
            val cursors = DiskTestCursors(File(work, "cursors"))
            repeat(5) { cycle ->
                var id = 0
                val runner = ChunkSync(ChunkOutbox(root), cursors,
                    { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                    newId = { "retained-$cycle-${id++}" }, clock = { cycle.toLong() })
                assertEquals(ChunkPhase.DONE, runner.run(true, "https://synthetic.invalid", syntheticMapping) {
                    FreshChunkInput(listOf(syntheticRecord("r-$cycle")), mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
                }.phase)
            }
            val oldest = root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.first()
            when (mutation) {
                "malformed" -> File(oldest, ATTEMPT_INTENT_NAME).writeText("{")
                "missing-reservation" -> File(oldest, "reservation").delete()
                "missing-intent" -> File(oldest, ATTEMPT_INTENT_NAME).delete()
            }
            val retained = retainedInventory(root)
            repeat(3) {
                val reopened = ChunkOutbox(root, releaseFault = { _, _ -> error("No new release authority") })
                assertNull(reopened.recoverStaging())
                reopened.reclaimDone(cursors)
                assertEquals(retained, retainedInventory(root))
                assertEquals(5, root.listFiles()!!.count { it.isDirectory })
                assertEquals(ChunkPhase.DONE, reopened.status()!!.phase)
            }
            assertTrue(root.listFiles()!!.none { it.name.startsWith("receipt-") })
        }
    }
}
