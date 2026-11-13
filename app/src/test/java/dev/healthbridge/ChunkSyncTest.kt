package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class ProcessDeath : Error()
internal class FakeChunkCursors : ChunkCursorStore {
    var pending: Pending? = null
    var values: Map<String, String> = mapOf("steps" to "old", "sleep" to "old")
    var commits = 0
    var afterCommit: () -> Unit = {}
    override suspend fun hasLegacyPending() = pending != null
    override suspend fun commit(targets: Map<String, String>): Boolean {
        if (pending != null) return false
        if (targets.any { values[it.key] != it.value }) {
            values = values + targets
            commits++
            afterCommit()
        }
        return true
    }
}

internal class ChunkHarness(val root: File, val limits: ChunkLimits = ChunkLimits(count = 1)) {
    val cursors = FakeChunkCursors()
    var fault: (DurableBoundary, String) -> Unit = { _, _ -> }
    val outbox get() = ChunkOutbox(root, DurableFiles(fault = { boundary, name -> fault(boundary, name) }))
    val sent = mutableListOf<Pair<String, ByteArray>>()
    var collectCalls = 0
    var nextId = 0
    var now = 1700L
    var input = FreshChunkInput(listOf(syntheticRecord("r"), syntheticRecord("s")), mapOf("steps" to "new", "sleep" to "new"))
    var reply: (String, ByteArray) -> ChunkResponse = { id, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\",\"accepted\":1,\"duplicate\":false}") }
    fun manifestPhase(): String? = root.walkTopDown().firstOrNull { it.name == "manifest.json" }?.let { JSONObject(it.readText()).getString("phase") }
    fun runner() = ChunkSync(outbox, cursors, send = { id, bytes ->
        sent += id to bytes.copyOf()
        reply(id, bytes)
    }, clock = { now }, newId = { "generated-${nextId++}" }, limits = limits)
    fun run(enabled: Boolean = true, mapping: VerifiedReceiverMapping = syntheticMapping, endpoint: String = "https://synthetic.invalid/hook"): ChunkStatus = runBlocking {
        runner().run(enabled, endpoint, mapping) { collectCalls++; input }
    }
    fun crashAfterDurablePhase(phase: String) {
        fault = { boundary, name -> if (name == "manifest.json" && boundary == DurableBoundary.DIRECTORY_SYNCED && manifestPhase() == phase) throw ProcessDeath() }
    }
}

class ChunkSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun harness(limits: ChunkLimits = ChunkLimits(count = 1)) = ChunkHarness(File(temporary.newFolder(), "outbox"), limits)
    private fun crash(block: () -> Unit) { try { block(); fail("Expected process death") } catch (_: ProcessDeath) {} }

    @Test fun flagOffDoesNotOpenOutboxCollectOrSend() {
        val h = harness()
        assertEquals(ChunkReason.FLAG_OFF, h.run(enabled = false).reason)
        assertFalse(h.root.exists())
        assertEquals(0, h.collectCalls)
        assertTrue(h.sent.isEmpty())
    }
    @Test fun productionFeatureGateControlsDeliveryWithConsentOn() {
        val h = harness()
        val consent = true
        val gate = ChunkContracts.freshChunkingEnabled
        val result = h.run(enabled = consent && gate)
        assertEquals(if (gate) ChunkPhase.DONE else ChunkPhase.PLAN, result.phase)
        assertEquals(if (gate) ChunkReason.NONE else ChunkReason.FLAG_OFF, result.reason)
        assertEquals(gate, h.root.exists())
        assertEquals(if (gate) 1 else 0, h.collectCalls)
        assertEquals(if (gate) 2 else 0, h.sent.size)
        assertEquals(if (gate) 1 else 0, h.cursors.commits)
    }
    @Test fun legacyPendingIsByteExactAndAllFieldsUnchanged() {
        val h = harness()
        val bytes = byteArrayOf(0, 1, -1, 10, 34)
        val pending = Pending(batchId = "legacy", body = bytes.copyOf(), cursors = "legacy-cursors", attempts = 4, nextAttempt = 999999, isTest = true)
        h.cursors.pending = pending
        assertEquals(ChunkReason.LEGACY_PENDING, h.run().reason)
        assertSame(pending, h.cursors.pending)
        assertArrayEquals(bytes, h.cursors.pending!!.body)
        assertEquals(4, pending.attempts)
        assertEquals(999999L, pending.nextAttempt)
        assertEquals("legacy-cursors", pending.cursors)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
        assertFalse(h.root.exists())
        assertTrue(h.sent.isEmpty())
    }
    @Test fun childOneAckChildTwo413RetainsBodiesAndNeverResends() {
        val h = harness()
        h.reply = { id, _ -> if (h.sent.size == 1) ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\",\"accepted\":1,\"duplicate\":false}") else ChunkResponse(413, "secret error") }
        assertEquals(ChunkPhase.BLOCKED, h.run().phase)
        val m = h.outbox.load()!!
        assertEquals(listOf(true, false), m.children.map { it.acked })
        assertEquals(0, h.cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
        h.sent.forEachIndexed { index, pair -> assertArrayEquals(pair.second, h.outbox.body(m, index)) }
        val filesBefore = h.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(h.root).path to it.readBytes().toList() }
        assertEquals(ChunkPhase.BLOCKED, h.run().phase)
        assertEquals(2, h.sent.size)
        assertEquals(filesBefore, h.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(h.root).path to it.readBytes().toList() })
    }
    @Test fun restartAfterStagingSendsOriginalIdsAndBytes() {
        val h = harness()
        h.fault = { boundary, name -> if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath() }
        crash { h.run() }
        assertTrue(h.sent.isEmpty())
        val staged = h.outbox.load()!!
        assertEquals(ChunkPhase.STAGE, staged.phase)
        val bodies = staged.children.indices.map { h.outbox.body(staged, it) }
        h.fault = { _, _ -> }
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(staged.children.map { it.batchId }, h.sent.map { it.first })
        bodies.zip(h.sent).forEach { (expected, actual) -> assertArrayEquals(expected, actual.second) }
        assertEquals(1, h.collectCalls)
    }
    @Test fun restartAfterDurableAckOnlySendsUnsentChild() {
        val h = harness()
        h.crashAfterDurablePhase("ACK")
        crash { h.run() }
        assertEquals(listOf(true, false), h.outbox.load()!!.children.map { it.acked })
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
        h.fault = { _, _ -> }
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(listOf("generated-0", "generated-1"), h.sent.map { it.first })
        assertEquals(1, h.cursors.commits)
        assertEquals(1, h.collectCalls)
    }
    @Test fun restartAtCommitDoesNotSendOrAdvanceEarly() {
        val h = harness()
        h.crashAfterDurablePhase("COMMIT")
        crash { h.run() }
        assertEquals(ChunkPhase.COMMIT, h.outbox.load()!!.phase)
        assertEquals(0, h.cursors.commits)
        assertEquals("old", h.cursors.values["steps"])
        h.fault = { _, _ -> }
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(2, h.sent.size)
        assertEquals(1, h.cursors.commits)
        assertEquals(mapOf("steps" to "new", "sleep" to "new"), h.cursors.values)
    }
    @Test fun restartAfterRoomCommitDoesNotRewriteCursorsOrSend() {
        val h = harness()
        h.cursors.afterCommit = { throw ProcessDeath() }
        crash { h.run() }
        assertEquals(ChunkPhase.COMMIT, h.outbox.load()!!.phase)
        assertEquals(mapOf("steps" to "new", "sleep" to "new"), h.cursors.values)
        h.cursors.afterCommit = {}
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(1, h.cursors.commits)
        assertEquals(2, h.sent.size)
        assertEquals(mapOf("steps" to "new", "sleep" to "new"), h.cursors.values)
    }
    @Test fun restartWithDurableSendIntentPausesForeverWithoutNetwork() {
        val h = harness()
        h.crashAfterDurablePhase("SEND")
        crash { h.run() }
        assertTrue(h.sent.isEmpty())
        h.fault = { _, _ -> }
        repeat(3) { assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, h.run().reason) }
        assertTrue(h.sent.isEmpty())
        assertEquals(ChunkPhase.PAUSED, h.outbox.load()!!.phase)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun crashAfterResponseBeforeAckWriteRemainsUnknown() {
        val h = harness()
        h.reply = { _, _ -> throw ProcessDeath() }
        crash { h.run() }
        assertEquals(ChunkPhase.SEND, h.outbox.load()!!.phase)
        assertEquals(ChunkPhase.PAUSED, h.run().phase)
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun timeoutPersistsPauseAndNeverRetries() {
        val h = harness()
        h.reply = { _, _ -> throw java.net.SocketTimeoutException("credentials payload endpoint") }
        val status = h.run()
        assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, status.reason)
        assertFalse(status.toString().contains("credentials"))
        assertEquals(ChunkPhase.PAUSED, h.outbox.load()!!.phase)
        assertEquals(ChunkPhase.PAUSED, h.run().phase)
        assertEquals(1, h.sent.size)
    }
    @Test fun wrongAckPersistsPause() { assertPausedResponse(200, "{\"ok\":true,\"batchId\":\"other\"}") }
    @Test fun unparseableAckPersistsPause() { assertPausedResponse(200, "not json") }
    @Test fun falseOkAckPersistsPause() { assertPausedResponse(200, "{\"batchId\":\"generated-0\",\"ok\":false}") }
    @Test fun legacy204DoesNotAcknowledgeChunk() { assertPausedResponse(204, "") }
    @Test fun unexpectedServerErrorPauses() { assertPausedResponse(500, "error") }
    private fun assertPausedResponse(code: Int, body: String) {
        val h = harness()
        h.reply = { _, _ -> ChunkResponse(code, body) }
        assertEquals(ChunkPhase.PAUSED, h.run().phase)
        assertEquals(ChunkPhase.PAUSED, h.outbox.load()!!.phase)
        h.run()
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun http400And409BlockDurably() {
        listOf(400, 409).forEach { code ->
            val h = harness()
            h.reply = { _, _ -> ChunkResponse(code, "ignored") }
            assertEquals(ChunkPhase.BLOCKED, h.run().phase)
            assertEquals(ChunkPhase.BLOCKED, h.outbox.load()!!.phase)
            h.run()
            assertEquals(1, h.sent.size)
            assertEquals(0, h.cursors.commits)
        }
    }
    @Test fun oversizedRecordStagesNoChildrenOrSend() {
        val h = harness(ChunkLimits(wireBytes = 200, count = 1))
        h.input = h.input.copy(records = listOf(syntheticRecord("r"), syntheticRecord("large", "x".repeat(1000))))
        assertEquals(ChunkReason.OVERSIZED, h.run().reason)
        assertTrue(h.sent.isEmpty())
        assertTrue(h.root.walkTopDown().none { it.name.startsWith("child-") })
        val blocked = h.outbox.load()!!
        assertEquals("large", blocked.blockedRecordId)
        assertTrue(blocked.blockedWireBytes!! > 1000)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun exactBodiesUseFakeIdsAndNoCursorMetadata() {
        val h = harness()
        h.input = FreshChunkInput(listOf(ChunkRecord("r", "{\"recordId\":\"r\"}")), mapOf("long".repeat(500) to "cursor-secret".repeat(500)))
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals("{\"schemaVersion\":1,\"batchId\":\"generated-0\",\"records\":[{\"recordId\":\"r\"}]}", h.sent.single().second.toString(Charsets.UTF_8))
        val m = h.outbox.load()!!
        assertEquals(1700L, m.createdAt)
        assertEquals(1700L, m.children.single().sentAt)
        assertEquals(1700L, m.children.single().ackedAt)
        assertEquals(h.input.targetCursors, m.targets)
    }
    @Test fun corruptionFailsClosedAndRetainsEveryBody() {
        val h = harness()
        h.crashAfterDurablePhase("STAGE")
        // Publish normally, then interrupt at the active pointer boundary.
        h.fault = { boundary, name -> if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath() }
        crash { h.run() }
        val body = h.root.walkTopDown().first { it.name == "child-0.json" }
        body.appendText("tampered")
        h.fault = { _, _ -> }
        assertEquals(ChunkReason.CORRUPT_OUTBOX, h.run().reason)
        assertTrue(body.readText().endsWith("tampered"))
        assertTrue(h.sent.isEmpty())
    }
    @Test fun newGenerationRetainsPriorGenerationBytes() {
        val h = harness()
        assertEquals(ChunkPhase.DONE, h.run().phase)
        val old = h.outbox.load()!!
        val bytes = old.children.indices.map { h.outbox.body(old, it) }
        h.now = 1900
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertNotEquals(old.generation, h.outbox.load()!!.generation)
        bytes.forEachIndexed { index, expected -> assertArrayEquals(expected, h.outbox.body(old, index)) }
        assertTrue(File(h.root, "${old.generation}/manifest.json").exists())
    }
    @Test fun endpointChangePausesBeforeAnyResumedSend() {
        val h = harness()
        h.fault = { boundary, name -> if (name == "active" && boundary == DurableBoundary.DIRECTORY_SYNCED) throw ProcessDeath() }
        crash { h.run() }
        h.fault = { _, _ -> }
        assertEquals(ChunkReason.ENDPOINT_CHANGED, h.run(endpoint = "https://other.invalid").reason)
        assertTrue(h.sent.isEmpty())
    }
    @Test fun pendingAppearingAfterAckBlocksBeforeChildTwo() {
        val h = harness()
        h.reply = { id, _ -> h.cursors.pending = Pending(batchId = "legacy", body = byteArrayOf(1), cursors = "{}"); ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\",\"accepted\":1,\"duplicate\":false}") }
        assertEquals(ChunkReason.LEGACY_PENDING, h.run().reason)
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
    }
    @Test fun pendingAppearingBeforeCommitPreventsAllCursorWrites() {
        val h = harness()
        h.reply = { id, _ -> if (h.sent.size == 2) h.cursors.pending = Pending(batchId = "legacy", body = byteArrayOf(9), cursors = "{}"); ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\",\"accepted\":1,\"duplicate\":false}") }
        assertEquals(ChunkReason.LEGACY_PENDING, h.run().reason)
        assertEquals(2, h.sent.size)
        assertEquals(0, h.cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
    }
    @Test fun readOnlyStatusNeverChangesManifestOrFiles() {
        val h = harness()
        h.run()
        val before = h.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        assertEquals(ChunkPhase.DONE, h.outbox.status()!!.phase)
        assertEquals(before, h.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() })
    }
    @Test fun strictAckRejectsDuplicateKeysTrailingBytesAndNonJsonSyntax() {
        listOf("{\"batchId\":\"x\",\"batchId\":\"x\"}", "{\"batchId\":\"x\"} garbage", "{'batchId':'x'}", "{batchId:\"x\"}", "[]", "{\"batchId\":1}", "{\"batchId\":\"x\",}").forEach { assertFalse(it, validChunkAck(it, "x")) }
        assertTrue(validChunkAck(" \n{ \"ok\":true, \"batchId\" : \"x\" }\t", "x"))
        assertTrue(validChunkAck("{\"ok\":true,\"batchId\":\"\\u0078\"}", "x"))
    }
    @Test fun canonicalFingerprintCoversRecordsEndpointAndTargets() {
        val h = harness()
        h.run()
        val m = h.outbox.load()!!
        assertEquals(m.fingerprint, parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes, m.targets.entries.reversed().associate { it.toPair() }))
        assertNotEquals(m.fingerprint, parentFingerprint("other", m.recordIds, m.recordHashes, m.targets))
        assertNotEquals(m.fingerprint, parentFingerprint(m.endpointHash, m.recordIds.reversed(), m.recordHashes, m.targets))
        assertNotEquals(m.fingerprint, parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes.reversed(), m.targets))
        assertNotEquals(m.fingerprint, parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes, mapOf("steps" to "other")))
    }
    @Test fun expectedReceiverAckFieldsAndDuplicateTrueAcknowledgeOnce() {
        val h = harness()
        h.reply = { id, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\",\"accepted\":0,\"duplicate\":true}") }
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(listOf("generated-0", "generated-1"), h.sent.map { it.first })
        assertEquals(1, h.cursors.commits)
    }
    @Test fun unknownExtraAckFieldPausesDurably() {
        assertPausedResponse(200, "{\"ok\":true,\"batchId\":\"generated-0\",\"accepted\":1,\"duplicate\":false,\"extra\":1}")
    }
    @Test fun malformedOptionalAckFieldsAndNonBooleanOkAreRejected() {
        listOf(
            "{\"ok\":\"true\",\"batchId\":\"x\"}",
            "{\"ok\":true,\"batchId\":\"x\",\"accepted\":-1}",
            "{\"ok\":true,\"batchId\":\"x\",\"accepted\":1.5}",
            "{\"ok\":true,\"batchId\":\"x\",\"accepted\":\"1\"}",
            "{\"ok\":true,\"batchId\":\"x\",\"duplicate\":1}"
        ).forEach { assertFalse(it, validChunkAck(it, "x")) }
        assertTrue(validChunkAck("{\"ok\":true,\"batchId\":\"x\"}", "x"))
    }
    @Test fun fingerprintExplicitlySeparatesRecordAndTargetPairCounts() {
        assertNotEquals(parentFingerprint("e", listOf("a"), listOf("b"), mapOf("c" to "d")),
            parentFingerprint("e", listOf("a", "c"), listOf("b", "d"), emptyMap()))
    }
    @Test fun offStopsEvenWithUnfinishedCorruptOutboxAndPreservesFiles() {
        val h = harness()
        h.crashAfterDurablePhase("ACK")
        crash { h.run() }
        h.root.walkTopDown().first { it.name == "child-0.json" }.appendText("corrupt")
        val before = h.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        assertEquals(ChunkReason.FLAG_OFF, h.run(enabled = false).reason)
        assertEquals(before, h.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() })
        assertEquals(1, h.sent.size)
        assertEquals(0, h.cursors.commits)
    }
}
