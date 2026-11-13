package dev.healthbridge

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkOversizedRecordTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun singleOversizedRecordBlocksWithoutPublicationSendOrCursorChange() {
        val h = ChunkHarness(File(temporary.newFolder(), "outbox"), ChunkLimits())
        val evidence = File(h.root, "protected-generation/child-0.json")
        assertTrue(checkNotNull(evidence.parentFile).mkdirs())
        val retained = "synthetic unknown-acceptance evidence".toByteArray()
        evidence.writeBytes(retained)
        val input = syntheticRecord("oversized", "x".repeat(524_288))
        h.input = FreshChunkInput(listOf(input), mapOf("steps" to "new"))
        val plan = ChunkPartitioner.partition(listOf(input), listOf("synthetic-child"), syntheticMapping, ChunkLimits())
        assertTrue(plan is ChunkPlan.Blocked)
        assertEquals(ChunkBlockCause.OVERSIZED, (plan as ChunkPlan.Blocked).cause)

        val status = h.run()
        assertEquals(ChunkPhase.BLOCKED, status.phase)
        assertEquals(ChunkReason.OVERSIZED, status.reason)
        assertTrue(h.outbox.load()!!.children.isEmpty())
        assertEquals(0, status.total)
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
        assertArrayEquals(retained, evidence.readBytes())
        assertEquals(listOf(evidence), h.root.walkTopDown().filter { it.name.startsWith("child-") }.toList())
        assertEquals(ChunkReason.OVERSIZED, h.run().reason)
        assertTrue(h.sent.isEmpty())
        assertArrayEquals(retained, evidence.readBytes())
    }
}
