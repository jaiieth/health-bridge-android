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
        parentFingerprint(sha256("endpoint".toByteArray()), emptyList(), emptyList(), emptyMap()), sha256("endpoint".toByteArray()),
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
        val overhead = 4 + 3 * manifestEncodedBytes(m) + 256 + 2 * attemptIntentBudget(manifestEncodedBytes(m))
        sized(File(root, "retained-body"), OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES - overhead)
        val outbox = ChunkOutbox(root)
        val reservation = outbox.reserve(m, 4)
        assertEquals(OUTBOX_HARD_QUOTA_BYTES, reservation.peak)
        assertEquals(outbox.retainedBytes() + outbox.controlBytes() + 4 + 3 * manifestEncodedBytes(m) + 256 + 2 * attemptIntentBudget(manifestEncodedBytes(m)), reservation.peak)
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
        val overhead = 4 + 3 * manifestEncodedBytes(m) + 256 + 2 * attemptIntentBudget(manifestEncodedBytes(m))
        sized(File(root, "retained-body"), OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES - overhead)
        var highWater = 0L
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ ->
            highWater = maxOf(highWater, total(root))
            assertTrue(total(root) <= OUTBOX_HARD_QUOTA_BYTES)
        }))
        val reservation = outbox.reserve(m, 4)
        assertEquals(OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES, reservation.peak)
        outbox.beginReserved(reservation, m, listOf(4L))
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

    @Test fun nearHardQuotaMeasuresEveryAtomicCopyAndRetainsIntentStaging() {
        val root = temporary.newFolder()
        data class Peak(val bytes: Long, val atMillis: Long, val file: String)
        val peaks = sortedMapOf<String, Peak>()
        var operation = "retained-stage"
        var admittedPeak = OUTBOX_HARD_QUOTA_BYTES
        fun observe(boundary: String, file: String) {
            val bytes = total(root)
            val atMillis = System.currentTimeMillis()
            val key = "$operation/${if (file.startsWith("child-")) "child" else file}/$boundary"
            if (bytes > (peaks[key]?.bytes ?: -1)) peaks[key] = Peak(bytes, atMillis, file)
            val message = "$key total=$bytes peak=$admittedPeak atMillis=$atMillis maxima=$peaks"
            assertTrue(message, bytes <= OUTBOX_HARD_QUOTA_BYTES)
            assertTrue(message, bytes <= admittedPeak)
        }
        val outbox = ChunkOutbox(root, DurableFiles(fault = { boundary, file -> observe(boundary.name, file) }),
            releaseFault = { boundary, file -> observe(boundary.name, file) })
        fun fixture(id: String, count: Int, lastSize: Int = 524_288): Pair<ChunkManifest, List<ByteArray>> {
            val records = List(count) { index ->
                val recordId = "$id-record-$index"
                val empty = syntheticRecord(recordId, "")
                val overhead = ChunkPartitioner.envelope("$id-batch-$index", listOf(empty)).size
                syntheticRecord(recordId, "x".repeat((if (index == count - 1) lastSize else 524_288) - overhead))
            }
            val bodies = records.mapIndexed { index, record ->
                ChunkPartitioner.envelope("$id-batch-$index", listOf(record)).also { assertEquals(if (index == count - 1) lastSize else 524_288, it.size) }
            }
            val endpoint = sha256("endpoint".toByteArray())
            val ids = records.map { it.recordId }
            val hashes = records.map { sha256(it.json.toByteArray()) }
            val targets = mapOf("StepsRecord" to "2026-11-01T00:00:00Z")
            val manifest = ChunkManifest(id, parentFingerprint(endpoint, ids, hashes, targets), endpoint,
                ids, hashes, targets, 0, 0, ChunkPhase.STAGE,
                bodies.mapIndexed { index, body -> ChunkChild(index, "$id-batch-$index", sha256(body), 1) })
            return manifest to bodies
        }
        fun stage(m: ChunkManifest, bodies: List<ByteArray>, destination: ChunkOutbox = outbox): ChunkOutbox.Reservation {
            val reservation = destination.reserve(m, bodies.sumOf { it.size.toLong() })
            if (destination === outbox) admittedPeak = reservation.peak
            destination.beginReserved(reservation, m, bodies.map { it.size.toLong() })
            bodies.forEachIndexed { index, body -> destination.stageReservedBody(reservation, index, body, body.size) }
            destination.publishReserved(reservation, m)
            return reservation
        }
        fun done(m: ChunkManifest) = m.copy(phase = ChunkPhase.DONE,
            children = m.children.map { it.copy(acked = true, sentAt = 1, ackedAt = 1) })
        val (attempt, bodies) = fixture("near-limit", 100)
        val probeRoot = temporary.newFolder()
        val probe = ChunkOutbox(probeRoot)
        val (probeManifest, probeBodies) = fixture("retained", 28)
        stage(probeManifest, probeBodies, probe)
        probe.save(done(probeManifest))
        val retainedMetadata = probe.retainedBytes() - probeBodies.sumOf { it.size.toLong() }
        val retainedBodyBudget = OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES -
            bodies.sumOf { it.size.toLong() } - 3 * manifestEncodedBytes(attempt) - 256 - 2 * attemptIntentBudget(manifestEncodedBytes(attempt)) - retainedMetadata
        val lastSize = (retainedBodyBudget - 27L * 524_288).toInt()
        assertTrue(lastSize in 1024..524_288)
        val (retained, retainedBodies) = fixture("retained", 28, lastSize)
        stage(retained, retainedBodies)
        operation = "retained-save-done"
        outbox.save(done(retained))
        File(root, "previous").writeBytes(File(root, "active-proof").readBytes())
        operation = "near-limit-stage"
        val reservation = stage(attempt, bodies)
        assertEquals(OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES + reservation.controls, reservation.peak)
        assertEquals(52_428_800L, reservation.bodies)
        operation = "near-limit-save"
        outbox.save(attempt.copy(updatedAt = 1))
        operation = "backpressure"
        outbox.recordBackpressure(ChunkReason.OUTBOX_QUOTA, "refused", OUTBOX_HARD_QUOTA_BYTES + 1)
        for (name in listOf("active", "active-proof")) assertTrue(File(root, name).delete())
        operation = "staging-retain"
        val beforeRecovery = inventory(root)
        repeat(2) { assertNull(outbox.recoverStaging()); assertEquals(beforeRecovery, inventory(root)) }
        assertTrue(File(root, attempt.generation).isDirectory)
        assertTrue(File(root, retained.generation).isDirectory)
        val requiredWrites = listOf("near-limit-stage/reservation", "near-limit-stage/child",
            "near-limit-stage/manifest.json", "near-limit-stage/published.json", "near-limit-stage/previous",
            "near-limit-stage/active-proof", "near-limit-stage/active", "near-limit-save/manifest.json",
            "backpressure/backpressure", "near-limit-stage/attempt-intent.json")
        for (write in requiredWrites) for (boundary in DurableBoundary.entries) {
            assertNotNull("$write/$boundary", peaks["$write/$boundary"])
        }
        peaks.forEach { (key, value) -> println("OUTBOX_PEAK $key total=${value.bytes} atMillis=${value.atMillis} file=${value.file} reservationPeak=${reservation.peak}") }
    }

    @Test fun oldAndUnknownMarkerFormatsAreBlockedReadOnlyEvenOnExplicitRetry() {
        for (version in listOf("1", "999")) {
            val root = temporary.newFolder()
            val staged = ChunkOutbox(root)
            val m = manifest()
            val reservation = staged.reserve(m, 4)
            staged.beginReserved(reservation, m, listOf(4L))
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
        assertEquals(5, root.listFiles()!!.count { it.isDirectory })
    }
}
