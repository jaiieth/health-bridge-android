package dev.healthbridge

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V011KnownGapsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to (it.length() to sha256(it.readBytes()))
    }

    private fun crashBeforePublication(boundary: DurableBoundary) {
        val root = temporary.newFolder()
        val harness = ChunkHarness(root)
        harness.fault = { actual, name ->
            if (name == "child-0.json" && actual == boundary) throw ProcessDeath()
        }
        assertThrows(ProcessDeath::class.java) { harness.run() }
        assertTrue(harness.sent.isEmpty())
        assertEquals(0, harness.cursors.commits)
        val before = inventory(root)
        assertTrue(before.keys.any { it.contains("child-0.json") })
        assertFalse(before.keys.any { it.endsWith("manifest.json") || it.endsWith("published.json") })
        assertFalse(File(root, "active").exists())
        val restarted = ChunkOutbox(root)
        repeat(2) {
            assertNull(restarted.recoverStaging())
            assertEquals(before, inventory(root))
        }
        assertTrue(restarted.retainedBytes() > 0)
        println("GAP4 boundary=$boundary retained=${restarted.retainedBytes()} inventory=$before")
        val generation = root.listFiles()!!.single { it.isDirectory }
        assertTrue("#4 retained pre-publication bodies have no durable attempt intent", File(generation, "attempt-intent.json").isFile)
    }

    @Test fun gap4BodyTempSurvivesRestartWithoutAttemptIntent() = crashBeforePublication(DurableBoundary.TEMP_WRITTEN)

    @Test fun gap4DurableBodySurvivesRestartWithoutAttemptIntent() = crashBeforePublication(DurableBoundary.DIRECTORY_SYNCED)

    private class DurableCursors : ChunkCursorStore {
        var saved = emptyMap<String, String>()
        override suspend fun hasLegacyPending() = false
        override suspend fun commit(targets: Map<String, String>): Boolean {
            saved = saved + targets
            return true
        }
        override suspend fun committed(targets: Map<String, String>) = targets.all { (type, until) ->
            saved[type]?.let { !Instant.parse(it).isBefore(Instant.parse(until)) } == true
        }
    }

    @Test fun gap6ThreeProvenDoneGenerationsPreventNextEnvelopeAdmission() = runBlocking {
        val root = temporary.newFolder()
        val cursors = DurableCursors()
        repeat(3) { number ->
            var id = 0
            val runner = ChunkSync(ChunkOutbox(root), cursors,
                { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                clock = { number.toLong() }, newId = { "full-$number-${id++}" }, limits = ChunkLimits(count = 1))
            val target = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(number.toLong()).toString()
            val status = runner.run(true, "endpoint", syntheticMapping) {
                FreshChunkInput(List(42) { index -> syntheticRecord("r-$number-$index", "x".repeat(520_000)) },
                    mapOf("StepsRecord" to target))
            }
            assertEquals(ChunkPhase.DONE, status.phase)
            assertEquals(42, ChunkOutbox(root).load()!!.children.size)
        }
        val restarted = ChunkOutbox(root)
        val before = inventory(root)
        val targets = mapOf("StepsRecord" to "2026-11-01T00:00:02Z")
        assertTrue(cursors.committed(targets))
        restarted.reclaimDone(cursors)
        assertEquals(before, inventory(root))
        assertEquals(3, root.listFiles()!!.count { it.isDirectory })
        assertTrue(restarted.retainedBytes() + restarted.controlBytes() <= OUTBOX_HARD_QUOTA_BYTES)
        var id = 0
        var sends = 0
        val next = ChunkSync(restarted, cursors, { _, _ -> sends++; error("No send at quota refusal") },
            newId = { "next-${id++}" }, limits = ChunkLimits(count = 1))
        val result = next.run(true, "endpoint", syntheticMapping) {
            FreshChunkInput(List(4) { syntheticRecord("next-$it", "x".repeat(520_000)) }, targets)
        }
        assertEquals(ChunkReason.OUTBOX_QUOTA, result.reason)
        assertEquals(0, sends)
        assertEquals(3, root.listFiles()!!.count { it.isDirectory })
        before.forEach { (path, bytes) -> assertEquals(bytes, inventory(root)[path]) }
        println("GAP6 retained=${restarted.retainedBytes()} controls=${restarted.controlBytes()} quota=$OUTBOX_HARD_QUOTA_BYTES status=$result")
        assertEquals("#6 no forward progress with three retained DONE generations and a full envelope", ChunkPhase.DONE, result.phase)
    }
}
