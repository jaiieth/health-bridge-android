package dev.healthbridge

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxEnvelopeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manifest(id: String = "generation") = ChunkManifest(id,
        parentFingerprint("endpoint", emptyList(), emptyList(), emptyMap()), "endpoint",
        emptyList(), emptyList(), emptyMap(), 0, 0, ChunkPhase.STAGE,
        listOf(ChunkChild(0, "batch", sha256("body".toByteArray()), 0)))
    private fun sized(file: File, length: Long) = RandomAccessFile(file, "rw").use { it.setLength(length) }
    private fun total(root: File) = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to (it.length() to sha256(it.readBytes()))
    }

    @Test fun exactTotalBoundaryIncludesEveryControlAndRefusesOneExtraByte() {
        val root = temporary.newFolder()
        val m = manifest()
        // Fill the control slice for this reservation-only arithmetic fixture.
        repeat(32) { sized(File(root, "receipt-control-$it.json"), 2048) }
        val overhead = 4 + 3 * manifestEncodedBytes(m) + 256
        sized(File(root, "retained-body"), OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES - overhead)
        val outbox = ChunkOutbox(root)
        val reservation = outbox.reserve(m, 4)
        assertEquals(OUTBOX_HARD_QUOTA_BYTES, reservation.peak)
        assertEquals(outbox.retainedBytes() + outbox.controlBytes() + 4 + 3 * manifestEncodedBytes(m) + 256, reservation.peak)
        val refused = assertThrows(java.io.IOException::class.java) { outbox.reserve(m, 5) }
        assertNotNull(refused)
        assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
        assertFalse(File(root, m.generation).exists())
    }

    @Test fun controlsReduceAvailableCapacityWithoutAnAdditiveQuota() {
        val root = temporary.newFolder()
        val m = manifest()
        val outbox = ChunkOutbox(root)
        val emptyPeak = outbox.reserve(m, 4).peak
        File(root, "active").writeText("prior")
        assertEquals(emptyPeak + 5, outbox.reserve(m, 4).peak)
        assertEquals(5L, outbox.controlBytes())
    }

    @Test fun payloadCeilingLeavesRoomForDurableRefusalWithinTheTotalEnvelope() {
        val root = temporary.newFolder()
        val m = manifest()
        val overhead = 4 + 3 * manifestEncodedBytes(m) + 256
        sized(File(root, "retained-body"), OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES - overhead)
        var highWater = 0L
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ ->
            highWater = maxOf(highWater, total(root))
            assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
        }))
        val reservation = outbox.reserve(m, 4)
        assertEquals(OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES, reservation.peak)
        outbox.beginReserved(reservation)
        outbox.stageReservedBody(reservation, 0, "body".toByteArray(), 4)
        outbox.publishReserved(reservation, m)
        outbox.save(m.copy(updatedAt = 1))
        assertEquals(ChunkReason.OUTBOX_QUOTA, assertThrows(ChunkBudgetExceeded::class.java) {
            outbox.reserve(manifest("refused"), OUTBOX_HARD_QUOTA_BYTES)
        }.reason)
        assertTrue(File(root, "backpressure").isFile)
        assertEquals(ChunkReason.OUTBOX_QUOTA, outbox.backpressure())
        assertTrue(highWater <= OUTBOX_HARD_QUOTA_BYTES)
        assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
    }

    @Test fun oldAndUnknownMarkerFormatsAreBlockedReadOnlyEvenOnExplicitRetry() {
        for (version in listOf("1", "999")) {
            val root = temporary.newFolder()
            val staged = ChunkOutbox(root)
            val reservation = staged.reserve(manifest(), 4)
            staged.beginReserved(reservation)
            staged.stageReservedBody(reservation, 0, "body".toByteArray(), 4)
            File(root, "backpressure").writeText(listOf(version, "OUTBOX_QUOTA", "generation",
                OUTBOX_HARD_QUOTA_BYTES + 1, OUTBOX_HARD_QUOTA_BYTES, 0, FreshGenerationLimits().manifestBytes).joinToString("\n"))
            val before = inventory(root)
            val outbox = ChunkOutbox(root)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, outbox.backpressure(explicitRetry = true))
            val status = outbox.status()!!
            assertEquals(ChunkPhase.BLOCKED, status.phase)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, status.reason)
            val result = runBlocking {
                ChunkSync(outbox, FakeChunkCursors(), { _, _ -> error("No send") }).run(true, "endpoint", syntheticMapping) { error("No collect") }
            }
            assertEquals(ChunkPhase.BLOCKED, result.phase)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, result.reason)
            assertEquals(before, inventory(root))
        }
    }

    @Test fun retryCannotReleaseAnyOfThreeProvenDoneGenerationsToAdmitWork() {
        val root = temporary.newFolder()
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = false
            override suspend fun commit(targets: Map<String, String>) = true
            override suspend fun committed(targets: Map<String, String>) = true
        }
        repeat(3) { number ->
            var id = 0
            val runner = ChunkSync(ChunkOutbox(root), cursors,
                { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                clock = { number.toLong() }, newId = { "done-$number-${id++}" })
            assertEquals(ChunkPhase.DONE, runBlocking { runner.run(true, "endpoint", syntheticMapping) {
                FreshChunkInput(listOf(syntheticRecord("r$number")), mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
            } }.phase)
        }
        val retained = root.listFiles()!!.filter { it.isDirectory }.associate { it.name to inventory(it) }
        assertEquals(3, retained.size)
        val outbox = ChunkOutbox(root)
        val m = manifest("new-work")
        repeat(3) {
            assertEquals(ChunkReason.OUTBOX_QUOTA, assertThrows(ChunkBudgetExceeded::class.java) {
                outbox.reserve(m, OUTBOX_HARD_QUOTA_BYTES)
            }.reason)
            outbox.backpressure(explicitRetry = true)
            runBlocking { outbox.reclaimDone(cursors) }
            assertEquals(ChunkReason.OUTBOX_QUOTA, assertThrows(ChunkBudgetExceeded::class.java) {
                outbox.reserve(m, OUTBOX_HARD_QUOTA_BYTES)
            }.reason)
            retained.forEach { (id, bytes) -> assertEquals(bytes, inventory(File(root, id))) }
            assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
        }
    }

    @Test fun successfulStageSavePublishRetentionAndRecoveryNeverExceedTotal() {
        val root = temporary.newFolder()
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = false
            override suspend fun commit(targets: Map<String, String>) = true
            override suspend fun committed(targets: Map<String, String>) = true
        }
        var observed = 0
        val files = DurableFiles(fault = { _, _ -> observed++; assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES) })
        repeat(5) { number ->
            var id = 0
            val outbox = ChunkOutbox(root, files)
            val runner = ChunkSync(outbox, cursors,
                { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                clock = { number.toLong() }, newId = { "total-$number-${id++}" })
            assertEquals(ChunkPhase.DONE, runBlocking { runner.run(true, "endpoint", syntheticMapping) {
                FreshChunkInput(listOf(syntheticRecord("r$number")), mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
            } }.phase)
            assertNull(outbox.recoverStaging())
            assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
        }
        assertTrue(observed > 0)
        assertEquals(3, root.listFiles()!!.count { it.isDirectory })
    }
}
