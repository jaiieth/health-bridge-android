package dev.healthbridge

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun harness() = ChunkHarness(File(temporary.newFolder(), "outbox"))

    @Test fun stageIoFailureNeverWritesManifestForIncompleteChildren() {
        val h = harness()
        h.fault = { boundary, name ->
            if (name == "child-0.json" && boundary == DurableBoundary.FILE_SYNCED) throw IOException("synthetic disk failure")
        }
        assertEquals(ChunkReason.IO_FAILURE, h.run().reason)
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.cursors.commits)
        assertNull(h.outbox.load())
        assertTrue(h.root.walkTopDown().none { it.name == "manifest.json" })
    }

    @Test fun inconsistentPhaseCannotBypassDurableSendMarker() {
        val h = harness()
        h.crashAfterDurablePhase("SEND")
        try { h.run(); fail("Expected process death") } catch (_: ProcessDeath) {}
        val file = h.root.walkTopDown().first { it.name == "manifest.json" }
        val manifest = JSONObject(file.readText()).put("phase", "ACK")
        file.writeText(manifest.toString())
        h.fault = { _, _ -> }
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.cursors.commits)
    }

    @Test fun blockedHttpCodeIsPersistedWithoutResponsePayload() {
        listOf(400, 409, 413).forEach { code ->
            val h = harness()
            h.reply = { _, _ -> ChunkResponse(code, "synthetic-response-must-not-persist") }
            assertEquals("HTTP_$code", h.run().reason.name)
            assertEquals("HTTP_$code", h.outbox.load()!!.reason.name)
            assertTrue(h.root.walkTopDown().filter { it.name == "manifest.json" }.none { it.readText().contains("synthetic-response-must-not-persist") })
            h.run()
            assertEquals(1, h.sent.size)
            assertEquals(0, h.cursors.commits)
        }
    }
    @Test fun containmentHttpClassesGovernFreshDeliveryAndNeverAdvanceOnNonAck() {
        val retry = listOf(0, 408, 425, 429, 500, 503, 599)
        val blocked = listOf(-1, 199, 300, 302, 400, 401, 403, 405, 409, 413, 422, 499, 600)
        (retry + blocked + listOf(201, 204, 299)).forEach { code ->
            val h = harness()
            h.reply = { id, _ -> ChunkResponse(code, "{\"ok\":true,\"batchId\":\"$id\"}") }
            val expected = if (code in blocked) ChunkPhase.BLOCKED else ChunkPhase.PAUSED
            assertEquals("HTTP $code", expected, h.run().phase)
            assertEquals(expected, h.outbox.load()!!.phase)
            h.run()
            assertEquals(1, h.sent.size)
            assertEquals(0, h.cursors.commits)
        }
    }
}
