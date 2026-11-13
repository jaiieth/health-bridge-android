package dev.healthbridge

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkIoFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun harness() = ChunkHarness(File(temporary.newFolder(), "outbox"))
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }

    private data class Write(val name: String, val phase: ChunkPhase? = null, val acked: Int = 0)

    @Test fun childWritesPreserveOrphansAtEveryBoundary() {
        listOf("child-0.json", "child-1.json").forEach { checkWriteFailures(Write(it)) }
    }

    @Test fun initialManifestWritesPreserveOrphansAtEveryBoundary() =
        checkWriteFailures(Write("manifest.json", ChunkPhase.STAGE))

    @Test fun activeWritesResumeOnlyAfterPublicationAtEveryBoundary() = checkWriteFailures(Write("active"))

    @Test fun sendWritesResumeOnlyBeforePublicationAtEveryBoundary() {
        (0..1).forEach { checkWriteFailures(Write("manifest.json", ChunkPhase.SEND, it)) }
    }

    @Test fun ackWritesNeverInferAcceptanceFromTempAtEveryBoundary() {
        (1..2).forEach { checkWriteFailures(Write("manifest.json", ChunkPhase.ACK, it)) }
    }

    @Test fun commitWritesKeepCursorBarrierAtEveryBoundary() =
        checkWriteFailures(Write("manifest.json", ChunkPhase.COMMIT, 2))

    @Test fun doneWriteFailuresCannotRollBackSuccessfulCursorCommitAtAnyBoundary() =
        checkWriteFailures(Write("manifest.json", ChunkPhase.DONE, 2))

    private fun checkWriteFailures(write: Write) {
        DurableBoundary.entries.forEach { injected ->
            val h = harness()
            var failedFiles: Map<String, List<Byte>>? = null
            var writesAfterFailure = 0
            val published = injected == DurableBoundary.RENAMED || injected == DurableBoundary.DIRECTORY_SYNCED
            val label = "$write at $injected"
            h.fault = { boundary, name ->
                if (failedFiles != null) writesAfterFailure++
                else if (name == write.name && boundary == injected) {
                    val matches = if (write.phase == null) true else {
                        val file = h.root.walkTopDown().first { it.name == if (published) name else "$name.tmp" }
                        val json = JSONObject(file.readText())
                        val children = json.getJSONArray("children")
                        json.getString("phase") == write.phase.name &&
                            (0 until children.length()).count { children.getJSONObject(it).getBoolean("acked") } == write.acked
                    }
                    if (matches) {
                        failedFiles = snapshot(h.root)
                        throw IOException("synthetic ENOSPC")
                    }
                }
            }
            val status = h.run()
            assertEquals(label, ChunkPhase.PAUSED, status.phase)
            assertEquals(label, ChunkReason.IO_FAILURE, status.reason)
            assertNotNull(label, failedFiles)
            assertEquals(label, 0, writesAfterFailure)
            if (h.outbox.load() == null) assertTrue(label, snapshot(h.root).isEmpty())
            else assertEquals(label, failedFiles, snapshot(h.root))
            if (!published) assertTrue(label, failedFiles!!.keys.any { it.endsWith("${write.name}.tmp") })
            val expectedSends = when (write.phase) {
                ChunkPhase.SEND, ChunkPhase.ACK -> write.acked
                ChunkPhase.COMMIT, ChunkPhase.DONE -> 2
                else -> 0
            }
            assertEquals(label, expectedSends, h.sent.size)
            val committed = write.phase == ChunkPhase.DONE
            assertEquals(label, if (committed) 1 else 0, h.cursors.commits)
            assertEquals(label, if (committed) h.input.targetCursors else mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)

            val expectedPhase = when {
                write.name.startsWith("child-") || write.phase == ChunkPhase.STAGE -> null
                write.name == "active" -> if (published) ChunkPhase.STAGE else null
                write.phase == ChunkPhase.SEND -> if (published) ChunkPhase.SEND else if (write.acked == 0) ChunkPhase.STAGE else ChunkPhase.ACK
                write.phase == ChunkPhase.ACK -> if (published) ChunkPhase.ACK else ChunkPhase.SEND
                write.phase == ChunkPhase.COMMIT -> if (published) ChunkPhase.COMMIT else ChunkPhase.ACK
                else -> if (published) ChunkPhase.DONE else ChunkPhase.COMMIT
            }
            val saved = h.outbox.load()
            assertEquals(label, expectedPhase, saved?.phase)
            if (saved == null) {
                assertTrue(label, h.sent.isEmpty())
                assertTrue(label, failedFiles!!.isNotEmpty())
                return@forEach
            }
            val bodies = saved.children.indices.map { h.outbox.body(saved, it) }
            val durableAckCount = when (write.phase) {
                ChunkPhase.SEND -> write.acked
                ChunkPhase.ACK -> if (published) write.acked else write.acked - 1
                ChunkPhase.COMMIT, ChunkPhase.DONE -> 2
                else -> 0
            }
            assertEquals(label, durableAckCount, saved.children.count { it.acked })
            assertEquals(label, listOf("generated-0", "generated-1"), saved.children.map { it.batchId })
            bodies.forEachIndexed { i, bytes ->
                assertArrayEquals(label, ChunkPartitioner.envelope(saved.children[i].batchId, listOf(h.input.records[i])), bytes)
            }
            h.fault = { _, _ -> }
            when (saved.phase) {
                ChunkPhase.SEND -> {
                    repeat(2) { assertEquals(label, ChunkReason.UNKNOWN_ACCEPTANCE, h.run().reason) }
                    assertEquals(label, expectedSends, h.sent.size)
                    assertEquals(label, 0, h.cursors.commits)
                }
                ChunkPhase.DONE -> assertEquals(label, 1, h.cursors.commits)
                else -> {
                    assertEquals(label, ChunkPhase.DONE, h.run().phase)
                    assertEquals(label, saved.generation, h.outbox.load()!!.generation)
                    assertEquals(label, saved.children.map { it.batchId }, h.sent.map { it.first })
                    assertEquals(label, 1, h.cursors.commits)
                    assertEquals(label, h.input.targetCursors, h.cursors.values)
                }
            }
            h.sent.forEachIndexed { i, sent -> assertArrayEquals(label, bodies[i], sent.second) }
            bodies.forEachIndexed { i, bytes -> assertArrayEquals(label, bytes, h.outbox.body(saved, i)) }
            assertEquals(label, 1, h.collectCalls)
        }
    }

    @Test fun ackWriteFailureDoesNotPublishInMemoryAcceptanceOrTouchFailedTemp() {
        val h = harness()
        var failedFiles: Map<String, List<Byte>>? = null
        var writesAfterFailure = 0
        h.fault = { boundary, name ->
            if (failedFiles != null) writesAfterFailure++
            else if (name == "manifest.json" && boundary == DurableBoundary.FILE_SYNCED) {
                val temp = h.root.walkTopDown().first { it.name == "manifest.json.tmp" }
                if (JSONObject(temp.readText()).getString("phase") == "ACK") {
                    failedFiles = snapshot(h.root)
                    throw IOException("synthetic ENOSPC")
                }
            }
        }
        val status = h.run()
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        assertNotNull(failedFiles)
        assertEquals(0, writesAfterFailure)
        assertEquals(failedFiles, snapshot(h.root))
        assertEquals(ChunkPhase.SEND, h.outbox.load()!!.phase)
        assertEquals(listOf(false, false), h.outbox.load()!!.children.map { it.acked })
        assertEquals(0, h.cursors.commits)
        h.fault = { _, _ -> }
        repeat(2) { assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, h.run().reason) }
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
    }

    @Test fun cursorTransactionFailureBeforeMutationResumesCommitWithoutSendingOrWritingOnFailure() {
        val h = harness()
        var failCommit = true
        var atFailure: Map<String, List<Byte>>? = null
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = false
            override suspend fun commit(targets: Map<String, String>): Boolean {
                if (failCommit) {
                    atFailure = snapshot(h.root)
                    throw IOException("synthetic ENOSPC")
                }
                return h.cursors.commit(targets)
            }
        }
        var id = 0
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val runner = ChunkSync(h.outbox, cursors, send = { batch, bytes ->
            sent += batch to bytes.copyOf()
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
        }, newId = { "transaction-${id++}" }, limits = ChunkLimits(count = 1))
        fun run() = runBlocking { runner.run(true, "synthetic", syntheticMapping) { h.input } }
        val status = run()
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        assertNotNull(atFailure)
        assertEquals(atFailure, snapshot(h.root))
        assertEquals(ChunkPhase.COMMIT, h.outbox.load()!!.phase)
        assertEquals(0, h.cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
        val saved = h.outbox.load()!!
        failCommit = false
        assertEquals(ChunkPhase.DONE, run().phase)
        assertEquals(saved.generation, h.outbox.load()!!.generation)
        assertEquals(saved.children.map { it.batchId }, sent.map { it.first })
        sent.forEachIndexed { i, pair -> assertArrayEquals(pair.second, h.outbox.body(saved, i)) }
        assertEquals(1, h.cursors.commits)
        assertEquals(h.input.targetCursors, h.cursors.values)
    }

    @Test fun loadIoFailureIsDistinctFromMalformedManifestAndNeverWrites() {
        val root = temporary.newFolder()
        assertTrue(File(root, "active").mkdir())
        File(root, "evidence").writeText("retained")
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ -> fail("No writes during load failure") }))
        val runner = ChunkSync(outbox, FakeChunkCursors(), send = { _, _ -> error("No send") })
        val before = snapshot(root)
        val status = runBlocking { runner.run(true, "synthetic", syntheticMapping) { error("No collection") } }
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        assertEquals(ChunkReason.IO_FAILURE, outbox.status()!!.reason)
        assertEquals(before, snapshot(root))

        val h = staged()
        File(h.root, "${h.outbox.load()!!.generation}/manifest.json").writeText("{broken")
        val corrupt = snapshot(h.root)
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.outbox.status()!!.reason)
        assertEquals(corrupt, snapshot(h.root))
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.cursors.commits)
    }

    @Test fun publishedStageAckAndCommitMustPassResumeFenceAfterIoFailure() {
        listOf(ChunkPhase.STAGE, ChunkPhase.ACK, ChunkPhase.COMMIT).forEach { phase ->
            val h = harness()
            var failed = false
            h.fault = { boundary, name ->
                if (!failed && boundary == DurableBoundary.RENAMED &&
                    (phase == ChunkPhase.STAGE && name == "active" ||
                        phase != ChunkPhase.STAGE && name == "manifest.json" && h.manifestPhase() == phase.name)) {
                    failed = true
                    throw IOException("synthetic ENOSPC")
                }
            }
            assertEquals(ChunkReason.IO_FAILURE, h.run().reason)
            val saved = h.outbox.load()!!
            assertEquals(phase, saved.phase)
            val before = snapshot(h.root)
            val sendsBefore = h.sent.size
            var failFence = true
            val synced = mutableListOf<File>()
            val outbox = ChunkOutbox(h.root, DurableFiles(syncDirectory = { directory ->
                synced += directory
                if (failFence && directory == h.root) throw IOException("synthetic ENOSPC")
            }, fault = { _, _ -> if (failFence) fail("No writes after failed resume fence") }))
            val runner = ChunkSync(outbox, h.cursors, send = { batch, bytes ->
                assertEquals(listOf(h.root.parentFile, h.root, File(h.root, saved.generation)), synced.take(3))
                h.sent += batch to bytes.copyOf()
                ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
            })
            fun run() = runBlocking { runner.run(true, "https://synthetic.invalid/hook", syntheticMapping) { error("No recollection") } }
            val status = run()
            assertEquals(ChunkPhase.PAUSED, status.phase)
            assertEquals(ChunkReason.IO_FAILURE, status.reason)
            assertEquals(before, snapshot(h.root))
            assertEquals(sendsBefore, h.sent.size)
            assertEquals(0, h.cursors.commits)
            synced.clear()
            failFence = false
            assertEquals(ChunkPhase.DONE, run().phase)
            assertEquals(listOf(h.root.parentFile, h.root, File(h.root, saved.generation)), synced.take(3))
            assertEquals(saved.children.map { it.batchId }, h.sent.map { it.first })
            h.sent.forEachIndexed { i, pair -> assertArrayEquals(pair.second, h.outbox.body(saved, i)) }
            assertEquals(1, h.cursors.commits)
        }
    }

    @Test fun stageAdmissionRejectsOneByteBelowAndAdmitsExactBudgetWithoutDeletingEvidence() {
        val source = staged()
        val manifest = source.outbox.load()!!
        val plan = ChunkPartitioner.partition(source.input.records, manifest.children.map { it.batchId }, syntheticMapping, source.limits) as ChunkPlan.Ready
        val required = plan.children.sumOf { it.body.size.toLong() } + 3 * manifestEncodedBytes(manifest) + 256 + STAGE_HEADROOM_BYTES + 8192
        listOf(required - 1, required).forEach { available ->
            val root = temporary.newFolder()
            val old = File(root, "older")
            assertTrue(old.mkdir())
            File(old, "child-0.json").writeText("retained body")
            File(old, "manifest.json.tmp").writeText("retained failed temp")
            val before = snapshot(root)
            var writes = 0
            var syncs = 0
            var probes = 0
            val outbox = ChunkOutbox(root, DurableFiles(syncDirectory = { syncs++ }, fault = { _, _ -> writes++ }), usableSpace = { directory ->
                probes++
                assertEquals(root, directory)
                if (probes == 1) assertEquals(before, snapshot(root))
                available
            })
            if (available < required) {
                assertThrows(IOException::class.java) { outbox.stage(manifest, plan) }
                assertEquals(0, writes)
                assertEquals(0, syncs)
                assertEquals(before, snapshot(root))
            } else {
                outbox.stage(manifest, plan)
                assertEquals(ChunkPhase.STAGE, outbox.load()!!.phase)
                assertEquals(manifest.generation, File(root, "active").readText())
                before.filterKeys { it != "active" }.forEach { (path, bytes) -> assertEquals(bytes, File(root, path).readBytes().toList()) }
                plan.children.forEachIndexed { i, child -> assertArrayEquals(child.body, outbox.body(manifest, i)) }
            }
            assertEquals(if (available < required) 1 else 4, probes)
        }
    }

    @Test fun unavailableSpaceIsIoFailureBeforeRootCreationOrAnyDurableWrites() {
        val source = staged()
        val manifest = source.outbox.load()!!
        val plan = ChunkPartitioner.partition(source.input.records, manifest.children.map { it.batchId }, syntheticMapping, source.limits) as ChunkPlan.Ready
        val unavailable: List<() -> Long> = listOf({ -1L }, { 0L }, { throw IOException("synthetic ENOSPC") }, { throw SecurityException("unavailable") })
        unavailable.forEach { space ->
            val parent = temporary.newFolder()
            File(parent, "evidence.tmp").writeText("retained")
            val root = File(parent, "outbox")
            val before = snapshot(parent)
            val outbox = ChunkOutbox(root, DurableFiles(syncDirectory = { fail("No durable directory writes") },
                fault = { _, _ -> fail("No durable file writes") }), usableSpace = { directory ->
                assertEquals(parent, directory)
                space()
            })
            assertThrows(IOException::class.java) { outbox.stage(manifest, plan) }
            assertFalse(root.exists())
            assertEquals(before, snapshot(parent))
        }
    }

    @Test fun directoryCreationAndNonDirectoryFailuresAreIoExceptions() {
        val root = temporary.newFolder()
        val files = DurableFiles(syncDirectory = { fail("No sync after directory failure") })
        assertThrows(IOException::class.java) { files.directory(File(root, "missing/child")) }
        val evidence = File(root, "existing-file")
        evidence.writeText("retained")
        assertThrows(IOException::class.java) { files.directory(evidence) }
        assertEquals("retained", evidence.readText())
    }

    private fun staged(): ChunkHarness {
        val h = harness()
        h.fault = { boundary, name ->
            if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath()
        }
        try { h.run(); fail("Expected process death") } catch (_: ProcessDeath) {}
        h.fault = { _, _ -> }
        return h
    }
}
