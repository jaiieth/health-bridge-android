package dev.healthbridge

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manifest() = ChunkManifest("generation", parentFingerprint("endpoint", emptyList(), emptyList(), emptyMap()),
        "endpoint", emptyList(), emptyList(), emptyMap(), 0, 0, ChunkPhase.STAGE,
        listOf(ChunkChild(0, "batch", sha256("body".toByteArray()), 0)))
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        it.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        it.relativeTo(root).path to (it.length() to digest.digest().toList())
    }

    @Test fun hardQuotaBoundaryCountsRetainedTempAndPointerBeforeAnyBody() {
        for (delta in -1L..1L) {
            val root = temporary.newFolder()
            val m = manifest()
            val added = 4 + 3 * manifestEncodedBytes(m) + 256
            val retained = OUTBOX_HARD_QUOTA_BYTES - added + delta
            RandomAccessFile(File(root, "unknown-pending.tmp"), "rw").use { it.setLength(retained) }
            val outbox = ChunkOutbox(root)
            if (delta <= 0) {
                val r = outbox.reserve(m, 4)
                assertEquals(OUTBOX_HARD_QUOTA_BYTES + delta, r.peak)
                assertEquals(retained, outbox.retainedBytes())
                assertFalse(File(root, "generation").exists())
            } else {
                assertEquals(ChunkReason.OUTBOX_QUOTA, assertThrows(ChunkBudgetExceeded::class.java) { outbox.reserve(m, 4) }.reason)
                assertFalse(File(root, "generation").exists())
                assertEquals(retained, File(root, "unknown-pending.tmp").length())
                val before = inventory(root)
                repeat(5) { assertEquals(ChunkReason.OUTBOX_QUOTA, ChunkOutbox(root).backpressure()) }
                assertEquals(before, inventory(root))
            }
        }
    }

    @Test fun retainedPendingGenerationAloneMakesSecondGenerationExceedQuota() {
        val root = temporary.newFolder()
        val record = syntheticRecord("pending")
        val body = ChunkPartitioner.envelope("batch", listOf(record))
        val first = manifest().copy(recordIds = listOf(record.recordId), recordHashes = listOf(sha256(record.json.toByteArray())),
            fingerprint = parentFingerprint("endpoint", listOf(record.recordId), listOf(sha256(record.json.toByteArray())), emptyMap()),
            children = listOf(ChunkChild(0, "batch", sha256(body), 1)))
        val outbox = ChunkOutbox(root)
        outbox.stage(first, ChunkPlan.Ready(listOf(PlannedChild("batch", listOf(record), body, 2))))
        val before = inventory(root)
        val second = manifest().copy(generation = "second", recordIds = List(1300) { "\u0000".repeat(1000) },
            recordHashes = List(1300) { "0".repeat(64) })
        val bodies = OUTBOX_HARD_QUOTA_BYTES - 3 * manifestEncodedBytes(second) - 256
        assertTrue(bodies <= 100L * 524_288)
        val empty = ChunkOutbox(temporary.newFolder())
        assertEquals(OUTBOX_HARD_QUOTA_BYTES, empty.reserve(second, bodies).peak)
        assertEquals(ChunkReason.OUTBOX_QUOTA, assertThrows(ChunkBudgetExceeded::class.java) { outbox.reserve(second, bodies) }.reason)
        before.forEach { (path, bytes) -> assertEquals(bytes, inventory(root)[path]) }
        assertFalse(File(root, "second").exists())
        assertEquals(first, outbox.load())
    }

    @Test fun markerPreventsCollectionOnRepeatedRunsAndExplicitRetryClearsIt() {
        val root = temporary.newFolder()
        val outbox = ChunkOutbox(root)
        outbox.recordBackpressure(ChunkReason.MANIFEST_LIMIT, "refused", Long.MAX_VALUE)
        val before = inventory(root)
        val runner = ChunkSync(outbox, FakeChunkCursors(), { _, _ -> error("No send") })
        repeat(8) {
            assertEquals(ChunkReason.MANIFEST_LIMIT, runBlocking { runner.run(true, "endpoint", syntheticMapping) { error("No recollection") } }.reason)
            assertEquals(before, inventory(root))
        }
        assertNull(outbox.backpressure(explicitRetry = true))
    }

    @Test fun bodyAndManifestIoFailuresRollbackOnlyOwnedFiles() {
        for (name in listOf("child-0.json", "manifest.json")) {
            val root = temporary.newFolder()
            File(root, "active").writeText("previous")
            File(root, "unrecognised").writeText("pending evidence")
            val before = inventory(root)
            val outbox = ChunkOutbox(root, DurableFiles(fault = { boundary, file ->
                if (file == name && boundary == DurableBoundary.TEMP_WRITTEN) {
                    val temp = File(root, "generation/$file.tmp")
                    RandomAccessFile(temp, "rw").use { it.setLength(temp.length() / 2) }
                    throw IOException("ENOSPC after a partial temporary write")
                }
            }))
            val m = manifest()
            val r = outbox.reserve(m, 4)
            val attempt = outbox.beginReserved(r)
            assertThrows(IOException::class.java) {
                try {
                    outbox.stageReservedBody(r, 0, "body".toByteArray(), 4)
                    outbox.publishReserved(r, m)
                } catch (e: IOException) { outbox.rollback(attempt); throw e }
            }
            assertEquals(before, inventory(root))
            outbox.rollback(attempt)
            assertEquals(before, inventory(root))
        }
    }

    @Test fun nearManifestBudgetWithEscapingMultibyteAndPairsFitsReservedPeak() {
        val root = temporary.newFolder()
        val recordId = "\u0000/é中😀".repeat(200)
        val record = ChunkRecord(recordId, "{\"recordId\":" + org.json.JSONObject.quote(recordId) + "}")
        val count = ((8L * 1024 * 1024 - 110_000) / manifestRecordBytes(recordId, "0".repeat(64))).toInt()
        var id = 0
        var peak = 0L
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ ->
            peak = maxOf(peak, root.walkTopDown().filter { it.isFile && it.parentFile != root }.sumOf { it.length() })
        }))
        val m = runBlocking {
            StreamingChunkPlanner(outbox, syntheticMapping, ChunkLimits(), { "near-${id++}" }, { 0 }).stage(
                FreshChunkInput(emptyMap(), FreshRecordProducer { accept -> repeat(count) { accept(record) } }), "endpoint")
        }
        assertEquals(ChunkPhase.STAGE, m.phase)
        val actual = File(root, "${m.generation}/manifest.json").length()
        assertTrue(actual > 7L * 1024 * 1024)
        assertTrue(actual <= manifestEncodedBytes(m))
        val reserved = File(root, "${m.generation}/reservation").readLines()[5].toLong()
        outbox.save(m.copy(updatedAt = 1))
        assertTrue(peak <= reserved)
        assertTrue(reserved <= OUTBOX_HARD_QUOTA_BYTES)
        assertEquals(m.copy(updatedAt = 1), outbox.load())
    }

    @Test fun changedSourceRollsBackAndPreservesPreexistingPendingInventory() {
        val root = temporary.newFolder()
        File(root, "legacy-Pending-unknown").writeText("never touch")
        val before = inventory(root)
        var pass = 0
        var id = 0
        val input = FreshChunkInput(emptyMap(), FreshRecordProducer { accept ->
            accept(syntheticRecord(if (pass++ == 0) "first" else "changed"))
        })
        assertThrows(IllegalStateException::class.java) { runBlocking {
            StreamingChunkPlanner(ChunkOutbox(root), syntheticMapping, ChunkLimits(), { "change-${id++}" }, { 0 }).stage(input, "endpoint")
        } }
        assertEquals(before, inventory(root))
    }

    @Test fun sameReservationRetriesAfterRolledBackFailureWithoutDuplicateBodies() {
        val root = temporary.newFolder()
        var fail = true
        val outbox = ChunkOutbox(root, DurableFiles(fault = { boundary, name ->
            if (fail && name == "child-0.json" && boundary == DurableBoundary.FILE_SYNCED) throw IOException("ENOSPC")
        }))
        val m = manifest()
        val r = outbox.reserve(m, 4)
        val attempt = outbox.beginReserved(r)
        assertThrows(IOException::class.java) { outbox.stageReservedBody(r, 0, "body".toByteArray(), 4) }
        outbox.rollback(attempt)
        assertEquals(0L, outbox.retainedBytes())
        fail = false
        outbox.beginReserved(r)
        outbox.stageReservedBody(r, 0, "body".toByteArray(), 4)
        outbox.publishReserved(r, m)
        assertEquals(1, root.walkTopDown().count { it.name == "child-0.json" })
        assertEquals(r.peak.toString(), File(root, "generation/reservation").readLines()[5])
        ChunkOutbox(root).save(m)
        assertEquals(r.peak.toString(), File(root, "generation/reservation").readLines()[5])
    }

    @Test fun cleanupCrashLeavesIgnoredOrphanAndNeverTouchesUnknownOrActiveFiles() {
        val root = temporary.newFolder()
        File(root, "active").writeText("previous")
        val pending = File(root, "previous").apply { mkdir() }
        File(pending, "child-0.json").writeText("unacked")
        File(root, "unknown-file").writeText("unknown")
        val before = inventory(root)
        var crash = false
        val outbox = ChunkOutbox(root, DurableFiles(syncDirectory = { directory ->
            if (crash && directory.name == "generation") throw ProcessDeath()
        }))
        val r = outbox.reserve(manifest(), 4)
        val attempt = outbox.beginReserved(r)
        outbox.stageReservedBody(r, 0, "body".toByteArray(), 4)
        crash = true
        assertThrows(ProcessDeath::class.java) { outbox.rollback(attempt) }
        val after = inventory(root)
        before.forEach { (path, content) -> assertEquals(content, after[path]) }
        // A restarted instance has no ownership token and leaves this unpublished directory alone.
        val restarted = ChunkOutbox(root)
        assertTrue(restarted.retainedBytes() <= r.peak)
        assertEquals(after, inventory(root))
        crash = false
        outbox.rollback(attempt)
        assertEquals(before, inventory(root))
    }

    @Test fun rollbackNeverRemovesCommitDoneUnknownAcceptanceOrUnknownFiles() {
        for (phase in listOf(ChunkPhase.COMMIT, ChunkPhase.DONE, ChunkPhase.SEND, ChunkPhase.PAUSED)) {
            val root = temporary.newFolder()
            val outbox = ChunkOutbox(root)
            val m = manifest().copy(phase = phase, reason = if (phase == ChunkPhase.PAUSED) ChunkReason.UNKNOWN_ACCEPTANCE else ChunkReason.NONE)
            val r = outbox.reserve(m, 4)
            val token = outbox.beginReserved(r)
            outbox.stageReservedBody(r, 0, "body".toByteArray(), 4)
            outbox.publishReserved(r, m)
            File(root, "active").delete()
            val before = inventory(root)
            outbox.rollback(token)
            assertEquals(before, inventory(root))
        }
        val root = temporary.newFolder()
        val outbox = ChunkOutbox(root)
        val token = outbox.beginReserved(outbox.reserve(manifest(), 4))
        File(root, "generation/unknown-named-file").writeText("untouched")
        val before = inventory(root)
        outbox.rollback(token)
        assertEquals(before, inventory(root))
    }

    @Test fun entirelyFullUnknownTreeRefusesWithoutCollectionOrAnyGrowth() {
        val root = temporary.newFolder()
        val unknown = File(root, "unknown-pending")
        RandomAccessFile(unknown, "rw").use { it.setLength(OUTBOX_HARD_QUOTA_BYTES) }
        val outbox = ChunkOutbox(root)
        assertEquals(ChunkReason.OUTBOX_QUOTA, outbox.backpressure())
        val before = inventory(root)
        val runner = ChunkSync(outbox, FakeChunkCursors(), { _, _ -> error("No send") })
        repeat(5) {
            assertEquals(ChunkReason.OUTBOX_QUOTA, runBlocking {
                runner.run(true, "endpoint", syntheticMapping) { error("No collection") }
            }.reason)
            assertEquals(before, inventory(root))
        }
    }

    @Test fun killedAfterPointerRenameUsesValidNewPublicationOrAuthenticatedPrevious() {
        val root = temporary.newFolder()
        val body = ChunkPartitioner.envelope("batch", emptyList())
        fun empty(generation: String) = manifest().copy(generation = generation,
            children = listOf(ChunkChild(0, "batch", sha256(body), 0)))
        val plan = ChunkPlan.Ready(listOf(PlannedChild("batch", emptyList(), body, 2)))
        ChunkOutbox(root).stage(empty("previous-generation"), plan)
        val outbox = ChunkOutbox(root, DurableFiles(fault = { boundary, name ->
            if (name == "active" && boundary == DurableBoundary.RENAMED) throw ProcessDeath()
        }))
        assertThrows(ProcessDeath::class.java) { outbox.stage(empty("next-generation"), plan) }
        assertEquals("previous-generation", File(root, "previous").readLines()[1])
        assertEquals("next-generation", ChunkOutbox(root).load()!!.generation)
        File(root, "next-generation/published.json").writeText("corrupt snapshot")
        assertEquals("previous-generation", ChunkOutbox(root).load()!!.generation)
        assertTrue(ChunkOutbox(root).controlBytes() <= OUTBOX_CONTROL_QUOTA_BYTES)
    }

    @Test fun reasonsHaveHonestDistinctMessages() {
        val expected = mapOf(
            ChunkReason.OUTBOX_QUOTA to "The fresh-sync outbox has reached its storage admission limit.",
            ChunkReason.MANIFEST_LIMIT to "The sync window exceeds the manifest metadata budget.",
            ChunkReason.OVERSIZED to "A record exceeds the delivery size limit.",
            ChunkReason.IO_FAILURE to "Local storage failed. Recorded counts may be unavailable.",
        )
        expected.forEach { (reason, text) ->
            val display = SyncStatusDisplay.format(SyncStatusDisplay.Snapshot(true, true, true,
                result = "Health sync BLOCKED: 0/0; $reason", freshChunkingEnabled = true))
            assertTrue(display.reason, display.reason.endsWith(text))
        }
    }

    @Test fun reservedGenerationAtEveryAtomicBoundaryStaysUnderPeak() {
        val root = temporary.newFolder()
        var peak = 0L
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ ->
            peak = maxOf(peak, root.walkTopDown().filter { it.isFile && it.parentFile != root }.sumOf { it.length() })
        }))
        val m = manifest()
        val r = outbox.reserve(m, 4)
        outbox.beginReserved(r)
        outbox.stageReservedBody(r, 0, "body".toByteArray(), 4)
        outbox.publishReserved(r, m)
        outbox.save(m.copy(phase = ChunkPhase.SEND))
        assertTrue(peak <= r.peak)
        assertTrue(r.peak <= OUTBOX_HARD_QUOTA_BYTES)
        assertEquals(r.peak.toString(), File(root, "generation/reservation").readLines()[5])
    }
}
