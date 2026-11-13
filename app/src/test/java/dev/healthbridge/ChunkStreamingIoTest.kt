package dev.healthbridge

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkStreamingIoTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }

    @Test fun streamingWriteFaultsPreserveEvidenceAndResumeOnlyPublishedSafeStates() {
        val writes = listOf("child-0.json", "manifest.json:STAGE", "active", "manifest.json:ACK", "manifest.json:COMMIT")
        writes.forEach { write ->
            DurableBoundary.entries.forEach { injected ->
                val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
                val records = listOf(syntheticRecord("stream-r"), syntheticRecord("stream-s"))
                h.input = FreshChunkInput(mapOf("StepsRecord" to "2026-11-12T00:00:00Z"), FreshRecordProducer { consume ->
                    records.forEach { consume(it) }
                })
                var failed: Map<String, List<Byte>>? = null
                var extraWrites = 0
                h.fault = { boundary, name ->
                    if (failed != null) extraWrites++
                    else if (boundary == injected && name == write.substringBefore(':')) {
                        val published = boundary == DurableBoundary.RENAMED || boundary == DurableBoundary.DIRECTORY_SYNCED
                        val matches = if (':' !in write) true else {
                            val file = h.root.walkTopDown().first { it.name == if (published) name else "$name.tmp" }
                            JSONObject(file.readText()).getString("phase") == write.substringAfter(':')
                        }
                        if (matches) {
                            failed = snapshot(h.root)
                            throw IOException("synthetic ENOSPC")
                        }
                    }
                }
                val label = "$write at $injected"
                val status = h.run()
                assertEquals(label, ChunkPhase.PAUSED, status.phase)
                assertEquals(label, ChunkReason.IO_FAILURE, status.reason)
                assertNotNull(label, failed)
                assertEquals(label, failed, snapshot(h.root))
                assertEquals(label, 0, extraWrites)
                assertEquals(label, 0, h.cursors.commits)
                assertEquals(label, mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
                val saved = h.outbox.load()
                h.fault = { _, _ -> }
                if (saved == null) {
                    assertTrue(label, h.sent.isEmpty())
                    assertFalse(label, File(h.root, "active").exists())
                    assertEquals(label, failed, snapshot(h.root))
                } else {
                    val bodies = saved.children.indices.map { h.outbox.body(saved, it) }
                    val sentBefore = h.sent.size
                    if (saved.phase == ChunkPhase.SEND) {
                        assertEquals(label, ChunkReason.UNKNOWN_ACCEPTANCE, h.run().reason)
                        assertEquals(label, sentBefore, h.sent.size)
                        assertEquals(label, 0, h.cursors.commits)
                    } else {
                        assertEquals(label, ChunkPhase.DONE, h.run().phase)
                        assertEquals(label, saved.generation, h.outbox.load()!!.generation)
                        assertEquals(label, saved.children.map { it.batchId }, h.sent.map { it.first })
                        assertEquals(label, 1, h.cursors.commits)
                    }
                    h.sent.forEachIndexed { index, sent -> assertArrayEquals(label, bodies[index], sent.second) }
                    bodies.forEachIndexed { index, body -> assertArrayEquals(label, body, h.outbox.body(saved, index)) }
                    assertEquals(label, 1, h.collectCalls)
                }
            }
        }
    }

    @Test fun streamingCursorFailureResumesWithoutResendingAcknowledgedChildren() {
        val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
        val records = listOf(syntheticRecord("stream-r"), syntheticRecord("stream-s"))
        val input = FreshChunkInput(mapOf("StepsRecord" to "2026-11-12T00:00:00Z"), FreshRecordProducer { consume ->
            records.forEach { consume(it) }
        })
        var failCommit = true
        var failed: Map<String, List<Byte>>? = null
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = false
            override suspend fun commit(targets: Map<String, String>): Boolean {
                if (failCommit) {
                    failed = snapshot(h.root)
                    throw IOException("synthetic ENOSPC")
                }
                return h.cursors.commit(targets)
            }
        }
        var id = 0
        val runner = ChunkSync(h.outbox, cursors, send = { batch, bytes ->
            h.sent += batch to bytes.copyOf()
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
        }, newId = { "stream-transaction-${id++}" }, limits = ChunkLimits(count = 1))
        fun run() = runBlocking { runner.run(true, "synthetic", syntheticMapping) { input } }
        val status = run()
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        assertEquals(failed, snapshot(h.root))
        assertEquals(0, h.cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
        val saved = h.outbox.load()!!
        assertEquals(ChunkPhase.COMMIT, saved.phase)
        assertTrue(saved.children.all { it.acked })
        assertEquals(2, h.sent.size)
        failCommit = false
        assertEquals(ChunkPhase.DONE, run().phase)
        assertEquals(saved.generation, h.outbox.load()!!.generation)
        assertEquals(saved.children.map { it.batchId }, h.sent.map { it.first })
        h.sent.forEachIndexed { index, sent -> assertArrayEquals(h.outbox.body(saved, index), sent.second) }
        assertEquals(1, h.cursors.commits)
    }

}
