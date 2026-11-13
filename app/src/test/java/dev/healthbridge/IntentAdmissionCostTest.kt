package dev.healthbridge

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntentAdmissionCostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun intentMakesFourthAdmissionWorseInBytesAndStillRefusesWithoutSends() = runBlocking {
        val work = temporary.newFolder()
        val root = File(work, "outbox")
        val cursors = DiskTestCursors(File(work, "cursors"))
        val targets = mapOf("StepsRecord" to "2026-11-01T00:00:02Z")
        repeat(3) { cycle ->
            var id = 0
            var fileSyncs = 0
            var directorySyncs = 0
            val files = DurableFiles(syncDirectory = { dir ->
                directorySyncs++
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }, fault = { boundary, _ -> if (boundary == DurableBoundary.FILE_SYNCED) fileSyncs++ })
            val outbox = ChunkOutbox(root, files)
            val runner = ChunkSync(outbox, cursors,
                { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                newId = { "full-$cycle-${id++}" }, clock = { 1700 }, limits = ChunkLimits(count = 1))
            assertEquals(ChunkPhase.DONE, runner.run(true, "endpoint", syntheticMapping) {
                FreshChunkInput(List(42) { syntheticRecord("r-$cycle-$it", "x".repeat(520_000)) }, targets)
            }.phase)
            val manifest = outbox.load()!!
            val dir = File(root, manifest.generation)
            val m = manifestEncodedBytes(manifest)
            val reservation = File(dir, "reservation")
            val intent = File(dir, ATTEMPT_INTENT_NAME)
            assertEquals(if (cycle == 0) 134 else 135, fileSyncs)
            assertEquals(if (cycle == 0) 136 else 137, directorySyncs)
            println("ATTEMPT_COST cycle=${cycle + 1} reservation=${reservation.length()} intent=${intent.length()} M=$m I=${attemptIntentBudget(m)} peak_addition=${2 * attemptIntentBudget(m)} manifest_copy_budget=${3 * m} controls=${outbox.controlBytes()} file_fsyncs=$fileSyncs directory_fsyncs=$directorySyncs")
        }
        val actual = ChunkOutbox(root)
        val retained = actual.retainedBytes()
        val controls = actual.controlBytes()
        val counterfactual = temporary.newFolder()
        root.copyRecursively(counterfactual, overwrite = false)
        counterfactual.listFiles()!!.filter { it.isDirectory }.forEach { makeSyntheticLegacyGeneration(counterfactual, it.name) }
        val legacyBytes = ChunkOutbox(counterfactual).retainedBytes()
        val records = List(4) { syntheticRecord("next-$it", "x".repeat(520_000)) }
        val plan = ChunkPartitioner.partition(records, List(4) { "next-$it" }, syntheticMapping, ChunkLimits(count = 1)) as ChunkPlan.Ready
        val endpoint = sha256("endpoint".toByteArray())
        val recordIds = records.map { it.recordId }
        val hashes = records.map { sha256(it.json.toByteArray()) }
        val manifest = ChunkManifest("next-4", parentFingerprint(endpoint, recordIds, hashes, targets), endpoint,
            recordIds, hashes, targets, 1700, 1700, ChunkPhase.STAGE,
            plan.children.mapIndexed { index, child -> ChunkChild(index, child.batchId, sha256(child.body), 1) })
        val m = manifestEncodedBytes(manifest)
        val bodyBytes = plan.children.sumOf { it.body.size.toLong() }
        val oldPeak = legacyBytes + controls + bodyBytes + 3 * m + 256
        val newPeak = retained + controls + bodyBytes + 3 * m + 256 + 2 * attemptIntentBudget(m)
        assertTrue(oldPeak > OUTBOX_HARD_QUOTA_BYTES)
        assertTrue(newPeak > oldPeak)
        assertEquals(retained - legacyBytes + 2 * attemptIntentBudget(m), newPeak - oldPeak)
        var id = 0
        var sends = 0
        val before = retainedInventory(root)
        val next = ChunkSync(ChunkOutbox(root), cursors, { _, _ -> sends++; error("No send at refusal") },
            newId = { "next-${id++}" }, clock = { 1700 }, limits = ChunkLimits(count = 1))
        assertEquals(ChunkReason.OUTBOX_QUOTA, next.run(true, "endpoint", syntheticMapping) { FreshChunkInput(records, targets) }.reason)
        assertEquals(0, sends)
        assertEquals(newPeak.toString(), File(root, "backpressure").readLines()[3])
        before.forEach { (path, bytes) -> assertEquals(bytes, retainedInventory(root)[path]) }
        println("GAP6_COST retained_new=$retained retained_legacy_counterfactual=$legacyBytes controls=$controls next_body=$bodyBytes next_M=$m next_I=${attemptIntentBudget(m)} old_peak=$oldPeak new_peak=$newPeak worse_by=${newPeak - oldPeak} quota=$OUTBOX_HARD_QUOTA_BYTES outcome=OUTBOX_QUOTA sends=0")
    }
}
