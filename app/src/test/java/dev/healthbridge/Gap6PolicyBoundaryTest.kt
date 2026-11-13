package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** TEST-ONLY premise characterization. This is not an admission reclamation driver. */
class Gap6PolicyBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Pair<File, DiskTestCursors> {
        val work = temporary.newFolder()
        val root = File(work, "outbox")
        val cursors = DiskTestCursors(File(work, "cursors"))
        repeat(5) { cycle ->
            var id = 0
            val runner = ChunkSync(ChunkOutbox(root), cursors,
                { batch, _ -> ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}") },
                newId = { "policy-$cycle-${id++}" }, clock = { cycle.toLong() })
            assertEquals(ChunkPhase.DONE, runBlocking {
                runner.run(true, "endpoint", syntheticMapping) {
                    FreshChunkInput(listOf(syntheticRecord("r-$cycle")),
                        mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
                }
            }.phase)
        }
        return root to cursors
    }

    @Test fun actualCompletedValidatorRejectsEveryIntentBearingDoneGeneration() = runBlocking {
        val (root, cursors) = fixture()
        val outbox = ChunkOutbox(root)
        val validator = ChunkOutbox::class.java.getDeclaredMethod("completedManifest", File::class.java)
        validator.isAccessible = true
        root.listFiles()!!.filter { it.isDirectory }.forEach {
            assertTrue(File(it, ATTEMPT_INTENT_NAME).isFile)
            assertNull("Production release inventory excludes immutable intent", validator.invoke(outbox, it))
        }
        val before = retainedInventory(root)
        repeat(3) { ChunkOutbox(root).reclaimDone(cursors); assertEquals(before, retainedInventory(root)) }
        println("GAP6_MODEL_BLOCKED=no-overlap-between-new-intent-eligibility-and-production-release-inventory")
    }

    @Test fun everyProposedPinPreservesActualOutboxInventoryOnRestart() = runBlocking {
        val cases = listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp",
            "legacy-Pending", "staging-tombstone", "receipt", "receipt-temp", "ambiguous", "corrupt")
        for (pin in cases) {
            val (root, cursors) = fixture()
            val id = root.listFiles()!!.filter { it.isDirectory }.minBy { it.name }.name
            when (pin) {
                "active", "active.tmp" -> File(root, pin).writeText(id)
                "active-proof", "active-proof.tmp", "previous", "previous.tmp" -> File(root, pin).writeText("1\n$id\n" + "0".repeat(64) + "\npublished")
                "receipt" -> File(root, "receipt-$id.json").writeText("{}")
                "receipt-temp" -> File(root, "receipt-$id.json.tmp").writeText("{}")
                "ambiguous" -> File(root, "$id/unknown").writeText("unknown")
                "corrupt" -> File(root, "$id/manifest.json").writeText("{")
                "staging-tombstone" -> File(root, pin).writeText("{")
                else -> File(root, pin).writeText("synthetic Pending marker; actual guard supplied below")
            }
            val guarded = object : ChunkCursorStore {
                override suspend fun hasLegacyPending() = pin == "legacy-Pending"
                override suspend fun committed(targets: Map<String, String>) = cursors.committed(targets)
                override suspend fun commit(targets: Map<String, String>) = error("No commit during classification")
            }
            val before = retainedInventory(root)
            repeat(2) {
                ChunkOutbox(root, releaseFault = { _, _ -> error("No release for $pin") }).reclaimDone(guarded)
                assertEquals(pin, before, retainedInventory(root))
            }
            println("GAP6_PIN $pin blocks; no implicit unpin; inventory unchanged")
        }
    }

    @Test fun legacyNoIntentDoneRemainsUntouchedBehindActualPendingGuard() = runBlocking {
        val (root, _) = fixture()
        val id = root.listFiles()!!.filter { it.isDirectory }.minBy { it.name }.name
        makeSyntheticLegacyGeneration(root, id)
        val before = retainedInventory(root)
        val pending = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = true
            override suspend fun committed(targets: Map<String, String>) = error("Pending must stop release first")
            override suspend fun commit(targets: Map<String, String>) = error("No commit")
        }
        repeat(2) { ChunkOutbox(root).reclaimDone(pending); assertEquals(before, retainedInventory(root)) }
        assertFalse(File(root, "$id/$ATTEMPT_INTENT_NAME").exists())
    }
}
