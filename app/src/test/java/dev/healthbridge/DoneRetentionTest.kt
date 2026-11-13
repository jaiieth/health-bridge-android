package dev.healthbridge

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DoneRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Cursors : ChunkCursorStore {
        var legacy = false
        var prove = false
        var values = emptyMap<String, String>()
        override suspend fun hasLegacyPending() = legacy
        override suspend fun commit(targets: Map<String, String>): Boolean {
            if (legacy) return false
            values = values + targets
            return true
        }
        override suspend fun committed(targets: Map<String, String>) = prove && targets.all { (type, until) ->
            values[type]?.let { !Instant.parse(it).isBefore(Instant.parse(until)) } == true
        }
    }
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }
    private fun complete(root: File, cursors: Cursors, number: Int, count: Int = 2, files: DurableFiles = DurableFiles()): ChunkManifest {
        var next = 0
        val target = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(number * 100L).toString()
        val input = FreshChunkInput(List(count) { syntheticRecord("record-$number-$it") }, mapOf("StepsRecord" to target))
        val outbox = ChunkOutbox(root, files)
        val runner = ChunkSync(outbox, cursors, { id, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\"}") },
            newId = { "g$number-${next++}" }, clock = { number * 1000L }, limits = ChunkLimits(count = 1))
        assertEquals(ChunkPhase.DONE, runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { input } }.phase)
        return outbox.load()!!
    }
    private fun fixture(): Triple<File, Cursors, List<ChunkManifest>> {
        val root = temporary.newFolder()
        val cursors = Cursors()
        val manifests = (1..5).map { complete(root, cursors, it) }
        cursors.prove = true
        return Triple(root, cursors, manifests)
    }

    @Test fun keepsNewestThreeFullAndBoundedEvidenceForOlderCompletions() {
        val (root, cursors, manifests) = fixture()
        val before = inventory(root)
        val oldest = manifests.take(2).associate { m -> m.generation to m.children.sumOf { File(root, "${m.generation}/child-${it.index}.json").length() } }
        val outbox = ChunkOutbox(root)
        runBlocking { outbox.reclaimDone(cursors) }
        manifests.take(2).forEach { m ->
            assertFalse(File(root, m.generation).exists())
            val file = File(root, "receipt-${m.generation}.json")
            assertTrue(file.length() <= 2048)
            val receipt = JSONObject(file.readText())
            assertEquals(m.generation, receipt.getString("generation"))
            assertEquals(m.fingerprint, receipt.getString("fingerprint"))
            assertEquals(m.targets["StepsRecord"], receipt.getJSONObject("targets").getString("StepsRecord"))
            assertEquals(m.children.size, receipt.getInt("chunkCount"))
            assertEquals(oldest[m.generation], receipt.getLong("totalBytes"))
            assertEquals(m.updatedAt, receipt.getLong("completedAt"))
            assertEquals(208L, receipt.getLong("totalBytes"))
            val expectedHash = if (m == manifests[0]) "97b747c641c0e65f78e8a198280e814d9b1afe90e03200ac917f5c5e0346c419"
                else "9b29717b36f47891057d5e7db9791ad03348a468313bca8c0e36e2e287d0b296"
            assertEquals(expectedHash, receipt.getString("contentHash"))
        }
        manifests.takeLast(3).forEach { m ->
            before.filterKeys { it.startsWith(m.generation + "/") }.forEach { (path, bytes) -> assertEquals(bytes, inventory(root)[path]) }
        }
        assertEquals(manifests.last().generation, outbox.load()!!.generation)
        val after = inventory(root)
        repeat(4) { runBlocking { ChunkOutbox(root).reclaimDone(cursors) }; assertEquals(after, inventory(root)) }
        assertTrue(outbox.controlBytes() <= OUTBOX_CONTROL_RESERVE_BYTES)
        assertTrue(outbox.retainedBytes() <= OUTBOX_HARD_QUOTA_BYTES)
    }

    @Test fun restartsEveryReleaseBoundaryWithNoDanglingPointerOrPendingLoss() {
        ReleaseBoundary.entries.forEach { boundary ->
            val (root, cursors, manifests) = fixture()
            File(root, "unknown-file").writeText("do not touch")
            val unknown = File(root, "unknown-directory").apply { mkdir() }
            File(unknown, "child-0.json").writeText("not a recognised generation")
            var failed = false
            val outbox = ChunkOutbox(root, releaseFault = { actual, _ ->
                if (!failed && actual == boundary) { failed = true; throw ProcessDeath() }
            })
            assertThrows(boundary.name, ProcessDeath::class.java) { runBlocking { outbox.reclaimDone(cursors) } }
            assertTrue(failed)
            assertEquals(manifests.last().generation, ChunkOutbox(root).load()!!.generation)
            runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
            manifests.take(2).forEach { assertFalse(File(root, it.generation).exists()) }
            manifests.takeLast(3).forEach { assertTrue(File(root, "${it.generation}/manifest.json").exists()) }
            assertEquals("do not touch", File(root, "unknown-file").readText())
            assertEquals("not a recognised generation", File(unknown, "child-0.json").readText())
        }
    }

    @Test fun tombstoneAtomicWriteCrashNeverReleasesBeforeItsDurabilityFence() {
        DurableBoundary.entries.forEach { boundary ->
            val (root, cursors, manifests) = fixture()
            val first = manifests.first()
            val bodies = first.children.associate { it.index to File(root, "${first.generation}/child-${it.index}.json").readBytes().toList() }
            val outbox = ChunkOutbox(root, DurableFiles(fault = { actual, name ->
                if (name == "receipt-${first.generation}.json" && actual == boundary) throw ProcessDeath()
            }))
            assertThrows(ProcessDeath::class.java) { runBlocking { outbox.reclaimDone(cursors) } }
            bodies.forEach { (index, bytes) -> assertEquals(bytes, File(root, "${first.generation}/child-$index.json").readBytes().toList()) }
            runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
            assertFalse(File(root, first.generation).exists())
            assertEquals(manifests.last().generation, ChunkOutbox(root).load()!!.generation)
        }
    }

    @Test fun neverDeletesUnprovenUnackedUnknownAcceptanceLegacyOrUnknownContents() {
        val (root, cursors, manifests) = fixture()
        cursors.prove = false
        var before = inventory(root)
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
        cursors.prove = true
        cursors.legacy = true
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
        cursors.legacy = false
        val oldest = File(root, "${manifests[0].generation}/manifest.json")
        oldest.writeText(JSONObject(oldest.readText()).put("phase", "PAUSED").put("reason", "UNKNOWN_ACCEPTANCE").toString())
        val unacked = File(root, "${manifests[1].generation}/manifest.json")
        val j = JSONObject(unacked.readText())
        j.getJSONArray("children").getJSONObject(0).put("acked", false)
        unacked.writeText(j.toString())
        File(root, "${manifests[2].generation}/unknown-name").writeText("unknown evidence")
        File(root, "receipt-unrecognised.json").writeText("not a receipt")
        before = inventory(root)
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
    }

    @Test fun releaseRestartsAfterIntentAndReservationMetadataRemoval() {
        for (metadataBoundary in listOf(2, 3)) {
            val (root, cursors, manifests) = fixture()
            var releasedGeneration: String? = null
            val retained = manifests.takeLast(3).associate { it.generation to inventory(File(root, it.generation)) }
            var removed = 0
            val outbox = ChunkOutbox(root, releaseFault = { boundary, id ->
                if (boundary == ReleaseBoundary.METADATA_REMOVED && ++removed == metadataBoundary) {
                    releasedGeneration = id
                    throw ProcessDeath()
                }
            })
            assertThrows(ProcessDeath::class.java) { runBlocking { outbox.reclaimDone(cursors) } }
            val oldest = File(root, checkNotNull(releasedGeneration))
            assertTrue(manifests.take(2).any { it.generation == oldest.name })
            assertFalse(File(oldest, ATTEMPT_INTENT_NAME).exists())
            assertEquals(metadataBoundary == 2, File(oldest, "reservation").exists())
            assertTrue(File(oldest, "manifest.json").isFile)
            assertTrue(File(root, "receipt-${oldest.name}.json").isFile)
            assertTrue(oldest.listFiles()!!.none { it.name.startsWith("child-") })
            runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
            manifests.take(2).forEach { assertFalse(File(root, it.generation).exists()) }
            retained.forEach { (id, bytes) -> assertEquals(bytes, inventory(File(root, id))) }
        }
    }

    @Test fun malformedIntentCannotAuthorizeCompletedRelease() {
        val (root, cursors, manifests) = fixture()
        File(root, "${manifests[0].generation}/$ATTEMPT_INTENT_NAME").writeText("malformed")
        val second = File(root, "${manifests[1].generation}/$ATTEMPT_INTENT_NAME")
        second.writeText(JSONObject(second.readText()).put("extra", true).toString())
        val before = inventory(root)
        runBlocking { ChunkOutbox(root, releaseFault = { _, _ -> error("No release") }).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
        assertTrue(root.listFiles()!!.none { it.name.startsWith("receipt-") })
    }

    @Test fun legacyCompletedReleaseKeepsItsIndependentAuthority() {
        val (root, cursors, manifests) = fixture()
        for (m in manifests) {
            val dir = File(root, m.generation)
            File(dir, ATTEMPT_INTENT_NAME).delete()
            val reservation = File(dir, "reservation")
            val fields = reservation.readLines().toMutableList()
            fields[5] = (fields[5].toLong() - 2 * attemptIntentBudget(fields[4].toLong())).toString()
            reservation.writeText(fields.joinToString("\n"))
        }
        val retained = manifests.takeLast(3).associate { it.generation to inventory(File(root, it.generation)) }
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        manifests.take(2).forEach { assertFalse(File(root, it.generation).exists()) }
        retained.forEach { (id, bytes) -> assertEquals(bytes, inventory(File(root, id))) }
    }

    @Test fun corruptMetadataUnderRecognisedNamesIsNeverReclaimed() {
        val (root, cursors, manifests) = fixture()
        File(root, "${manifests[0].generation}/published.json").writeText("unknown publication contents")
        File(root, "${manifests[1].generation}/reservation").writeText("unknown reservation contents")
        val before = inventory(root)
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
    }

    @Test fun restartedReleaseRechecksCursorAndLegacyAuthorityBeforeDeletingAnything() {
        val (root, cursors, manifests) = fixture()
        val outbox = ChunkOutbox(root, releaseFault = { boundary, _ ->
            if (boundary == ReleaseBoundary.TOMBSTONE_DURABLE) throw ProcessDeath()
        })
        assertThrows(ProcessDeath::class.java) { runBlocking { outbox.reclaimDone(cursors) } }
        val before = inventory(root)
        cursors.prove = false
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
        cursors.prove = true
        cursors.legacy = true
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertEquals(before, inventory(root))
        cursors.legacy = false
        runBlocking { ChunkOutbox(root).reclaimDone(cursors) }
        assertFalse(File(root, manifests.first().generation).exists())
        assertEquals(manifests.last().generation, ChunkOutbox(root).load()!!.generation)
    }

    @Test fun retentionClearsQuotaBackpressureOnlyAfterEnoughPayloadIsReleased() {
        val (root, cursors, manifests) = fixture()
        val outbox = ChunkOutbox(root)
        val firstBytes = File(root, manifests.first().generation).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        outbox.recordBackpressure(ChunkReason.OUTBOX_QUOTA, "next-generation",
            OUTBOX_HARD_QUOTA_BYTES - OUTBOX_CONTROL_RESERVE_BYTES + outbox.controlBytes() + firstBytes)
        assertEquals(ChunkReason.OUTBOX_QUOTA, outbox.backpressure())
        val before = outbox.retainedBytes()
        runBlocking { outbox.reclaimDone(cursors) }
        assertTrue(before - outbox.retainedBytes() >= firstBytes)
        assertNull(outbox.backpressure())
        assertFalse(File(root, "backpressure").exists())
        assertEquals(manifests.last().generation, outbox.load()!!.generation)
    }

    @Test fun tombstonesStayCappedAndControlPeakStaysBelowItsSeparateBudget() {
        val root = temporary.newFolder()
        val cursors = Cursors().apply { prove = true }
        var payloadPeak = 0L
        var controlPeak = 0L
        val files = DurableFiles(fault = { _, _ ->
            payloadPeak = maxOf(payloadPeak, ChunkOutbox(root).retainedBytes())
            controlPeak = maxOf(controlPeak, ChunkOutbox(root).controlBytes())
        })
        repeat(24) { complete(root, cursors, it + 1, count = 1, files = files) }
        assertTrue(payloadPeak <= OUTBOX_HARD_QUOTA_BYTES)
        assertTrue(controlPeak <= OUTBOX_CONTROL_RESERVE_BYTES)
        val outbox = ChunkOutbox(root)
        assertEquals(3, root.listFiles()!!.count { it.isDirectory })
        val receipts = root.listFiles()!!.filter { it.name.startsWith("receipt-") }
        assertEquals(COMPLETED_RECEIPT_COUNT, receipts.size)
        assertTrue(receipts.all { it.length() <= 2048 })
        assertTrue(outbox.controlBytes() <= OUTBOX_CONTROL_RESERVE_BYTES)
    }
}
