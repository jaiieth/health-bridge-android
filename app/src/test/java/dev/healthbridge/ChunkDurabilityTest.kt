package dev.healthbridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkDurabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun harness() = ChunkHarness(File(temporary.newFolder(), "outbox"))
    private fun dies(block: () -> Unit) { try { block(); fail("Expected process death") } catch (_: ProcessDeath) {} }

    @Test fun eachChildWriteBoundaryCannotPublishPartialPlan() {
        DurableBoundary.entries.forEach { injected ->
            val h = harness()
            h.fault = { boundary, name -> if (name == "child-1.json" && boundary == injected) throw ProcessDeath() }
            dies { h.run() }
            assertNull(h.outbox.load())
            assertTrue(h.sent.isEmpty())
            assertEquals(0, h.cursors.commits)
            val oldFiles = h.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
            assertTrue(oldFiles.isNotEmpty())
            h.fault = { _, _ -> }
            assertEquals(ChunkPhase.DONE, h.run().phase)
            oldFiles.forEach { (path, bytes) -> assertEquals(bytes, File(path).readBytes().toList()) }
            assertEquals(2, h.sent.size)
        }
    }
    @Test fun eachInitialManifestWriteBoundaryCannotPublishUnstagedBody() {
        DurableBoundary.entries.forEach { injected ->
            val h = harness()
            h.fault = { boundary, name -> if (name == "manifest.json" && boundary == injected) throw ProcessDeath() }
            dies { h.run() }
            assertNull(h.outbox.load())
            assertTrue(h.sent.isEmpty())
            assertEquals(2, h.root.walkTopDown().count { it.name.startsWith("child-") && it.extension == "json" })
        }
    }
    @Test fun activePointerPublicationAlwaysReferencesCompleteVerifiedBodies() {
        DurableBoundary.entries.forEach { injected ->
            val h = harness()
            h.fault = { boundary, name -> if (name == "active" && boundary == injected) throw ProcessDeath() }
            dies { h.run() }
            assertTrue(h.sent.isEmpty())
            val m = h.outbox.load()
            if (injected == DurableBoundary.TEMP_WRITTEN || injected == DurableBoundary.FILE_SYNCED) assertNull(m)
            else {
                assertEquals(ChunkPhase.STAGE, m!!.phase)
                assertEquals(2, m.children.size)
                m.children.indices.forEach { assertEquals(m.children[it].bodySha, sha256(h.outbox.body(m, it))) }
            }
        }
    }
    @Test fun ackCrashBeforeRenameNeverResendsAcceptanceUnknownChild() {
        listOf(DurableBoundary.TEMP_WRITTEN, DurableBoundary.FILE_SYNCED).forEach { injected ->
            val h = harness()
            h.fault = { boundary, name ->
                if (name == "manifest.json" && boundary == injected) {
                    val temp = h.root.walkTopDown().firstOrNull { it.name == "manifest.json.tmp" }
                    if (temp != null && JSONObject(temp.readText()).getString("phase") == "ACK") throw ProcessDeath()
                }
            }
            dies { h.run() }
            assertEquals(1, h.sent.size)
            assertEquals(ChunkPhase.SEND, h.outbox.load()!!.phase)
            h.fault = { _, _ -> }
            assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, h.run().reason)
            h.run()
            assertEquals(1, h.sent.size)
            assertEquals(0, h.cursors.commits)
        }
    }
    @Test fun ackAfterAtomicPublicationResumesOnlyRemainingBytes() {
        listOf(DurableBoundary.RENAMED, DurableBoundary.DIRECTORY_SYNCED).forEach { injected ->
            val h = harness()
            h.fault = { boundary, name -> if (name == "manifest.json" && boundary == injected && h.manifestPhase() == "ACK") throw ProcessDeath() }
            dies { h.run() }
            val m = h.outbox.load()!!
            assertEquals(listOf(true, false), m.children.map { it.acked })
            val remaining = h.outbox.body(m, 1)
            h.fault = { _, _ -> }
            assertEquals(ChunkPhase.DONE, h.run().phase)
            assertEquals(listOf("generated-0", "generated-1"), h.sent.map { it.first })
            assertArrayEquals(remaining, h.sent.last().second)
        }
    }
    @Test fun manifestFingerprintTamperFailsClosed() {
        val h = staged()
        val file = h.root.walkTopDown().first { it.name == "manifest.json" }
        val json = JSONObject(file.readText())
        json.getJSONObject("targets").put("steps", "different")
        file.writeText(json.toString())
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertTrue(h.sent.isEmpty())
    }
    @Test fun missingReferencedBodyFailsClosedWithoutDeletingOtherBody() {
        val h = staged()
        val first = h.root.walkTopDown().first { it.name == "child-0.json" }
        val retained = first.readBytes()
        // Simulate external storage loss, rather than outbox retention policy.
        assertTrue(h.root.walkTopDown().first { it.name == "child-1.json" }.delete())
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertArrayEquals(retained, first.readBytes())
        assertTrue(h.sent.isEmpty())
    }
    @Test fun pathTraversalActivePointerFailsClosed() {
        val h = staged()
        File(h.root, "active").writeText("../outside")
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertTrue(h.sent.isEmpty())
    }
    @Test fun unsupportedDirectorySyncFailsBeforeTransport() {
        val h = harness()
        val outbox = ChunkOutbox(h.root, DurableFiles(syncDirectory = { throw java.io.IOException("unsupported") }))
        val runner = ChunkSync(outbox, h.cursors, send = { _, _ -> fail("Must not send"); ChunkResponse(200, "") })
        val status = kotlinx.coroutines.runBlocking { runner.run(true, "synthetic", syntheticMapping) { h.input } }
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun immutableGenerationCannotBeOverwritten() {
        val h = staged()
        val m = h.outbox.load()!!
        val bytes = h.outbox.body(m, 0)
        assertTrue(runCatching { h.outbox.stage(m, null) }.isFailure)
        assertArrayEquals(bytes, h.outbox.body(m, 0))
    }
    @Test fun retryAfterFailedParentSyncMustSyncExistingRootBeforePublicationOrSend() {
        val root = File(temporary.newFolder(), "outbox")
        val parent = root.parentFile
        var failParentSync = true
        var parentSyncs = 0
        var sends = 0
        val files = DurableFiles(syncDirectory = { directory ->
            if (directory == parent) {
                parentSyncs++
                if (failParentSync) throw java.io.IOException("synthetic parent sync failure")
            }
        })
        val outbox = ChunkOutbox(root, files)
        val runner = ChunkSync(outbox, FakeChunkCursors(), send = { id, _ ->
            assertEquals(2, parentSyncs)
            sends++
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\"}")
        })
        fun run() = kotlinx.coroutines.runBlocking {
            runner.run(true, "synthetic", syntheticMapping) {
                FreshChunkInput(listOf(syntheticRecord("r")), mapOf("steps" to "new"))
            }
        }
        assertEquals(ChunkReason.IO_FAILURE, run().reason)
        assertTrue(root.isDirectory)
        assertNull(outbox.load())
        assertEquals(0, sends)
        failParentSync = false
        assertEquals(ChunkPhase.DONE, run().phase)
        assertEquals(2, parentSyncs)
        assertEquals(1, sends)
    }

    @Test fun resumedActiveAndAckRenamesMustPassDirectoryFenceBeforeSending() {
        listOf("active", "ACK").forEach { boundary ->
            val root = File(temporary.newFolder(), "outbox")
            val cursors = FakeChunkCursors()
            var injected = false
            var resumed = false
            var failFence = true
            var sends = 0
            var id = 0
            val synced = mutableListOf<File>()
            val files = DurableFiles(syncDirectory = { directory ->
                if (resumed) {
                    synced += directory
                    if (failFence && directory == root) throw java.io.IOException("synthetic resume fence failure")
                } else if (!injected) {
                    val activePublished = boundary == "active" && File(root, "active").exists() && directory == root
                    val ackPublished = boundary == "ACK" && root.walkTopDown().filter { it.name == "manifest.json" }
                        .any { JSONObject(it.readText()).getString("phase") == "ACK" }
                    if (activePublished || ackPublished) {
                        injected = true
                        throw ProcessDeath()
                    }
                }
            })
            val outbox = ChunkOutbox(root, files)
            val runner = ChunkSync(outbox, cursors, send = { batch, _ ->
                if (resumed) {
                    val generation = File(root, outbox.load()!!.generation)
                    assertEquals(listOf(root.parentFile, root, generation), synced.take(3))
                }
                sends++
                ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
            }, newId = { "fence-${id++}" }, limits = ChunkLimits(count = 1))
            fun run() = kotlinx.coroutines.runBlocking {
                runner.run(true, "synthetic", syntheticMapping) {
                    FreshChunkInput(listOf(syntheticRecord("a"), syntheticRecord("b")), mapOf("steps" to "new"))
                }
            }
            dies { run() }
            assertEquals(if (boundary == "active") 0 else 1, sends)
            resumed = true
            assertEquals(ChunkReason.IO_FAILURE, run().reason)
            assertEquals(if (boundary == "active") 0 else 1, sends)
            assertEquals(0, cursors.commits)
            synced.clear()
            failFence = false
            assertEquals(ChunkPhase.DONE, run().phase)
            assertEquals(2, sends)
            assertEquals(1, cursors.commits)
        }
    }

    private fun staged(): ChunkHarness {
        val h = harness()
        h.fault = { boundary, name -> if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath() }
        dies { h.run() }
        h.fault = { _, _ -> }
        return h
    }
}
