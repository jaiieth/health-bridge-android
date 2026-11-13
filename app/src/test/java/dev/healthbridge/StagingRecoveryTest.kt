package dev.healthbridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagingRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val manifest: ChunkManifest, val plan: ChunkPlan.Ready)

    private fun fixture(id: String): Fixture {
        val records = List(2) { syntheticRecord("$id-record-$it") }
        val children = records.mapIndexed { index, record ->
            val batch = "$id-batch-$index"
            val body = ChunkPartitioner.envelope(batch, listOf(record))
            PlannedChild(batch, listOf(record), body, 2)
        }
        val endpoint = sha256("https://synthetic.invalid".toByteArray())
        val ids = records.map { it.recordId }
        val hashes = records.map { sha256(it.json.toByteArray()) }
        val targets = mapOf("StepsRecord" to "2026-11-01T00:00:00Z")
        return Fixture(ChunkManifest(id, parentFingerprint(endpoint, ids, hashes, targets), endpoint,
            ids, hashes, targets, 1, 1, ChunkPhase.STAGE,
            children.mapIndexed { index, child -> ChunkChild(index, child.batchId, sha256(child.body), 1) }),
            ChunkPlan.Ready(children))
    }

    private fun inventory(root: File): Map<String, String> = root.walkTopDown().drop(1).associate { file ->
        file.relativeTo(root).path to when {
            Files.isSymbolicLink(file.toPath()) -> "symlink:${Files.readSymbolicLink(file.toPath())}"
            file.isDirectory -> "directory"
            else -> "${file.length()}:${sha256(file.readBytes())}"
        }
    }

    private fun envelope(root: File) {
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= OUTBOX_HARD_QUOTA_BYTES)
    }

    private fun stagePrior(root: File): Map<String, String> {
        val prior = fixture("prior")
        ChunkOutbox(root).stage(prior.manifest, prior.plan)
        return inventory(File(root, "prior"))
    }

    private fun noDanglingPointer(root: File) {
        for (name in listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp")) {
            val pointer = File(root, name)
            if (!pointer.isFile) continue
            val id = if (name.removeSuffix(".tmp") == "active") pointer.readText() else pointer.readLines()[1]
            assertTrue("$name references $id", File(root, id).isDirectory)
        }
    }

    private fun assertPrior(root: File, expected: Map<String, String>) {
        assertEquals(expected, inventory(File(root, "prior")))
        assertEquals("prior", ChunkOutbox(root).load()!!.generation)
        envelope(root)
    }

    private fun unpublished(root: File, manifest: ChunkManifest = fixture("attempt").manifest) {
        val outbox = ChunkOutbox(root)
        val plan = fixture(manifest.generation).plan
        val reservation = outbox.reserve(manifest, plan.children.sumOf { it.body.size.toLong() })
        outbox.beginReserved(reservation)
        plan.children.forEachIndexed { index, child -> outbox.stageReservedBody(reservation, index, child.body, child.body.size) }
        outbox.publishReserved(reservation, manifest)
        for (name in listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp")) {
            File(root, name).delete()
        }
    }

    @Test fun missingCommittedPhaseEvidenceIsRetainedCountedAndDoesNotBlockFreshStage() {
        for (metadata in listOf(emptyList(), listOf("manifest.json.tmp"), listOf("published.json.tmp"),
                listOf("manifest.json.tmp", "published.json.tmp"))) {
            val root = temporary.newFolder()
            unpublished(root)
            val dir = File(root, "attempt")
            val bytes = File(dir, "manifest.json").readBytes()
            File(dir, "manifest.json").delete()
            File(dir, "published.json").delete()
            metadata.forEach { File(dir, it).writeBytes(bytes) }
            val before = inventory(dir)
            val outbox = ChunkOutbox(root)
            val retained = dir.listFiles()!!.sumOf { it.length() }
            assertEquals(retained, outbox.retainedBytes())
            assertNull(outbox.recoverStaging())
            assertEquals(before, inventory(dir))
            assertEquals(retained, outbox.retainedBytes())
            val fresh = fixture("fresh")
            outbox.stage(fresh.manifest, fresh.plan)
            assertEquals("fresh", outbox.load()!!.generation)
            assertEquals(before, inventory(dir))
            assertTrue(outbox.retainedBytes() > retained)
        }
    }

    @Test fun committedPublishedStageAloneAuthorizesReclaim() {
        val root = temporary.newFolder()
        unpublished(root)
        File(root, "attempt/manifest.json").delete()
        assertNull(ChunkOutbox(root).recoverStaging())
        assertFalse(File(root, "attempt").exists())
    }

    @Test fun everyStagingAtomicBoundaryReclaimsOnlyProvenUnreferencedAttempt() {
        val names = listOf("reservation", "child-0.json", "child-1.json", "manifest.json", "published.json", "active-proof", "active")
        for (name in names) for (boundary in DurableBoundary.entries) {
            val root = temporary.newFolder()
            val prior = stagePrior(root)
            val attempt = fixture("attempt")
            val outbox = ChunkOutbox(root, DurableFiles(fault = { actual, file ->
                envelope(root)
                if (actual == boundary && file == name) throw ProcessDeath()
            }))
            assertThrows("$name/$boundary", ProcessDeath::class.java) { outbox.stage(attempt.manifest, attempt.plan) }
            val before = inventory(root)
            val hasReservation = File(root, "attempt/reservation").exists()
            val referenced = listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp").any {
                File(root, it).let { file -> file.isFile && file.readLines().any { line -> line == "attempt" } }
            }
            val hasCommittedMetadata = File(root, "attempt/manifest.json").exists() || File(root, "attempt/published.json").exists()
            val result = ChunkOutbox(root).recoverStaging()
            if (!hasReservation) {
                assertNull(result)
                assertEquals(before, inventory(root))
            } else if (!hasCommittedMetadata) {
                assertNull(result)
                assertEquals(before, inventory(root))
            } else if (referenced) {
                assertTrue(result == null || result == ChunkReason.CORRUPT_OUTBOX)
                assertEquals(before, inventory(root))
            } else {
                assertNull("$name/$boundary", result)
                assertFalse(File(root, "attempt").exists())
                assertEquals(prior, inventory(File(root, "prior")))
            }
            if (File(root, "active").readText() == "prior") assertPrior(root, prior)
            else assertEquals("attempt", ChunkOutbox(root).load()!!.generation)
            noDanglingPointer(root)
            val recovered = inventory(root)
            val again = ChunkOutbox(root).recoverStaging()
            assertEquals(recovered, inventory(root))
            if (result == null) assertNull(again)
            envelope(root)
        }
    }

    @Test fun directoryCreationCrashesNeverAuthorizeMissingReservationDeletion() {
        for (boundary in 1..2) {
            val root = File(temporary.newFolder(), "outbox")
            val attempt = fixture("attempt")
            var syncs = 0
            val outbox = ChunkOutbox(root, DurableFiles(syncDirectory = {
                if (++syncs == boundary) throw ProcessDeath()
            }))
            assertThrows(ProcessDeath::class.java) { outbox.stage(attempt.manifest, attempt.plan) }
            val before = inventory(root)
            val result = ChunkOutbox(root).recoverStaging()
            assertNull(result)
            assertEquals(before, inventory(root))
            assertEquals(result, ChunkOutbox(root).recoverStaging())
            assertEquals(before, inventory(root))
        }
    }

    @Test fun recoveryIsBoundedToOneGenerationAndThenBecomesNoOp() {
        val root = temporary.newFolder()
        unpublished(root, fixture("attempt-a").manifest)
        unpublished(root, fixture("attempt-b").manifest)
        assertNull(ChunkOutbox(root).recoverStaging())
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
        assertNull(ChunkOutbox(root).recoverStaging())
        assertEquals(0, root.listFiles()!!.count { it.isDirectory })
        val after = inventory(root)
        assertNull(ChunkOutbox(root).recoverStaging())
        assertEquals(after, inventory(root))
    }

    @Test fun deletionRestartsAtEveryDurabilityBoundary() {
        for (boundary in ReleaseBoundary.entries) {
            val root = temporary.newFolder()
            unpublished(root)
            var crashed = false
            val outbox = ChunkOutbox(root, releaseFault = { actual, _ ->
                envelope(root)
                if (!crashed && actual == boundary) { crashed = true; throw ProcessDeath() }
            })
            assertThrows(boundary.name, ProcessDeath::class.java) { outbox.recoverStaging() }
            assertTrue(crashed)
            assertTrue(File(root, "staging-tombstone").exists())
            if (boundary == ReleaseBoundary.MANIFEST_REMOVED) {
                assertTrue(File(root, "attempt/reservation").isFile)
                assertFalse(File(root, "attempt/manifest.json").exists())
                assertFalse(File(root, "attempt/published.json").exists())
            }
            assertNull(boundary.name, ChunkOutbox(root).recoverStaging())
            assertFalse(File(root, "attempt").exists())
            assertFalse(File(root, "staging-tombstone").exists())
            val after = inventory(root)
            assertNull(ChunkOutbox(root).recoverStaging())
            assertEquals(after, inventory(root))
            envelope(root)
        }
    }

    @Test fun tombstoneAtomicCrashesDoNotDeleteBeforeDurabilityAndResumeSafely() {
        for (boundary in DurableBoundary.entries) {
            val root = temporary.newFolder()
            unpublished(root)
            val before = inventory(File(root, "attempt"))
            val outbox = ChunkOutbox(root, DurableFiles(fault = { actual, name ->
                if (actual == boundary && name == "staging-tombstone") throw ProcessDeath()
            }))
            assertThrows(ProcessDeath::class.java) { outbox.recoverStaging() }
            assertEquals(before, inventory(File(root, "attempt")))
            val result = ChunkOutbox(root).recoverStaging()
            if (File(root, "staging-tombstone").exists()) {
                assertEquals(ChunkReason.CORRUPT_OUTBOX, result)
                assertEquals(before, inventory(File(root, "attempt")))
            } else {
                assertNull(result)
                assertFalse(File(root, "attempt").exists())
            }
            envelope(root)
        }
    }

    @Test fun everyTombstoneReplacementAtomicBoundaryResumesPartialDeletion() {
        for (writeNumber in 1..6) for (boundary in DurableBoundary.entries) {
            val root = temporary.newFolder()
            unpublished(root)
            var writes = 0
            val outbox = ChunkOutbox(root, DurableFiles(fault = { actual, name ->
                envelope(root)
                if (name == "staging-tombstone" && actual == boundary && ++writes == writeNumber) throw ProcessDeath()
            }))
            assertThrows("$writeNumber/$boundary", ProcessDeath::class.java) { outbox.recoverStaging() }
            assertNull("$writeNumber/$boundary", ChunkOutbox(root).recoverStaging())
            assertFalse(File(root, "attempt").exists())
            assertFalse(File(root, "staging-tombstone").exists())
            assertFalse(File(root, "staging-tombstone.tmp").exists())
            val after = inventory(root)
            assertNull(ChunkOutbox(root).recoverStaging())
            assertEquals(after, inventory(root))
        }
    }

    @Test fun absentDirectoryIsFencedBeforeItsTombstoneCanBeCleared() {
        val root = temporary.newFolder()
        unpublished(root)
        val deleting = ChunkOutbox(root, releaseFault = { boundary, _ ->
            if (boundary == ReleaseBoundary.GENERATION_REMOVED) throw ProcessDeath()
        })
        assertThrows(ProcessDeath::class.java) { deleting.recoverStaging() }
        assertFalse(File(root, "attempt").exists())
        val before = inventory(root)
        val resuming = ChunkOutbox(root, DurableFiles(syncDirectory = { directory ->
            assertEquals(root, directory)
            assertTrue(File(root, "staging-tombstone").isFile)
            throw ProcessDeath()
        }))
        assertThrows(ProcessDeath::class.java) { resuming.recoverStaging() }
        assertEquals(before, inventory(root))
        assertNull(ChunkOutbox(root).recoverStaging())
        assertTrue(inventory(root).isEmpty())
    }

    @Test fun ambiguousEvidenceHasZeroFileMutations() {
        val corruptions: List<(File) -> Unit> = listOf(
            { File(it, "attempt/reservation").delete() },
            { File(it, "attempt/reservation").writeText("1\nattempt\n0\n0\n0\n0\n0") },
            { File(it, "attempt/reservation").writeText("garbage") },
            { root ->
                val file = File(root, "attempt/reservation")
                val fields = file.readLines().toMutableList()
                fields[0] = "99"
                file.writeText(fields.joinToString("\n"))
            },
            { root ->
                val file = File(root, "attempt/reservation")
                val fields = file.readLines().toMutableList()
                fields[5] = (OUTBOX_HARD_QUOTA_BYTES + 1).toString()
                file.writeText(fields.joinToString("\n"))
            },
            { File(it, "attempt/reservation").writeText(File(it, "attempt/reservation").readText().replace("attempt", "other")) },
            { File(it, "attempt/unknown").writeText("preserve") },
            { File(it, "attempt/subdirectory").mkdir() },
            { Files.createSymbolicLink(File(it, "attempt/child-9.json").toPath(), File(it, "attempt/child-0.json").toPath()) },
            { File(it, "attempt/manifest.json").writeText("corrupt") },
            { File(it, "attempt/published.json").writeText("corrupt") },
            { File(it, "receipt-attempt.json").writeText("receipt evidence") },
            { File(it, "active").writeText("dangling") },
            { File(it, "active-proof").writeText("1\nattempt\nwrong\npublished") },
            { root ->
                val file = File(root, "attempt/manifest.json")
                file.writeText(JSONObject(file.readText()).put("fingerprint", "0".repeat(64)).toString())
            },
            { root ->
                val file = File(root, "attempt/manifest.json")
                val json = JSONObject(file.readText())
                json.getJSONArray("children").getJSONObject(0).put("batchId", "different")
                file.writeText(json.toString())
            },
        )
        for ((index, corrupt) in corruptions.withIndex()) {
            val root = temporary.newFolder()
            unpublished(root)
            corrupt(root)
            val before = inventory(root)
            assertEquals("corruption $index", if (index in 0..5 || index == 11) null else ChunkReason.CORRUPT_OUTBOX,
                ChunkOutbox(root).recoverStaging())
            assertEquals("corruption $index", before, inventory(root))
            envelope(root)
        }
    }

    @Test fun invalidCommittedIdentityAndInventoryBlockWithoutMutations() {
        val corruptions: List<(File) -> Unit> = listOf(
            { root ->
                val file = File(root, "attempt/manifest.json")
                file.writeText(JSONObject(file.readText()).put("generation", "other").toString())
            },
            { root ->
                val file = File(root, "attempt/manifest.json")
                val json = JSONObject(file.readText())
                json.getJSONArray("children").getJSONObject(0).put("index", 1)
                file.writeText(json.toString())
            },
            { File(it, "attempt/child-0.json").appendText("changed") },
            { File(it, "attempt/child-0.json").writeBytes(ByteArray(524_289)) },
        )
        for (corrupt in corruptions) {
            val root = temporary.newFolder()
            unpublished(root)
            corrupt(root)
            val before = inventory(root)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, ChunkOutbox(root).recoverStaging())
            assertEquals(before, inventory(root))
        }
    }

    @Test fun deliveryEvidenceAndUnknownAcceptanceAreNeverReclaimed() {
        val base = fixture("attempt").manifest
        val unsafe = listOf(
            base.copy(phase = ChunkPhase.SEND),
            base.copy(phase = ChunkPhase.ACK),
            base.copy(phase = ChunkPhase.COMMIT),
            base.copy(phase = ChunkPhase.DONE),
            base.copy(reason = ChunkReason.UNKNOWN_ACCEPTANCE),
            base.copy(children = base.children.map { it.copy(sentAt = 1) }),
            base.copy(children = base.children.map { it.copy(acked = true) }),
            base.copy(children = base.children.map { it.copy(ackedAt = 1) }),
        )
        for (manifest in unsafe) {
            val root = temporary.newFolder()
            unpublished(root, manifest)
            val before = inventory(root)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, ChunkOutbox(root).recoverStaging())
            assertEquals(before, inventory(root))
        }
    }

    @Test fun inertChildrenRemainCountedWhileOversizedInputReachesItsNormalResult() {
        for (streaming in listOf(false, true)) {
            val root = temporary.newFolder()
            val inert = File(root, "inert-leftover").apply { mkdir() }
            File(inert, "child-0.json").writeText("inert attempt bytes")
            val bytes = inventory(inert)
            val outbox = ChunkOutbox(root)
            assertEquals(19L, outbox.retainedBytes())
            assertNull(outbox.recoverStaging())
            assertEquals(bytes, inventory(inert))
            val cursors = FakeChunkCursors()
            var sends = 0
            var id = 0
            val record = syntheticRecord("oversized", "x".repeat(524_288))
            val input = if (streaming) FreshChunkInput(emptyMap(), FreshRecordProducer { it(record) })
                else FreshChunkInput(listOf(record), emptyMap())
            val runner = ChunkSync(outbox, cursors, { _, _ -> sends++; error("No send") }, newId = { "inert-${id++}" })
            val status = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { input } }
            assertEquals(ChunkPhase.BLOCKED, status.phase)
            assertEquals(ChunkReason.OVERSIZED, status.reason)
            assertEquals(0, sends)
            assertEquals(0, cursors.commits)
            assertEquals(bytes, inventory(inert))
            assertTrue(outbox.retainedBytes() >= 19L)
            val after = inventory(root)
            val second = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { error("No recollection") } }
            assertEquals(status, second)
            assertEquals(after, inventory(root))
        }
    }

    @Test fun incompleteCleanupProofWithSafeMetadataDoesNotBlockNormalPlanning() {
        for (damage in listOf("missing", "corrupt")) {
            val root = temporary.newFolder()
            unpublished(root)
            when (damage) {
                "missing" -> File(root, "attempt/reservation").delete()
                "corrupt" -> File(root, "attempt/reservation").writeText("invalid reservation")
            }
            val before = inventory(File(root, "attempt"))
            val cursors = FakeChunkCursors()
            var id = 0
            val outbox = ChunkOutbox(root)
            val runner = ChunkSync(outbox, cursors, { _, _ -> error("No send") }, newId = { "safe-${id++}" })
            val result = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) {
                FreshChunkInput(listOf(syntheticRecord("oversized", "x".repeat(524_288))), emptyMap())
            } }
            assertEquals(ChunkPhase.BLOCKED, result.phase)
            assertEquals(ChunkReason.OVERSIZED, result.reason)
            assertEquals(0, cursors.commits)
            assertEquals(before, inventory(File(root, "attempt")))
            envelope(root)
        }
    }

    @Test fun unreferencedUnknownSendBlocksBeforeCollectionWithoutAnyMutation() {
        val root = temporary.newFolder()
        unpublished(root, fixture("attempt").manifest.copy(phase = ChunkPhase.SEND, reason = ChunkReason.UNKNOWN_ACCEPTANCE))
        val before = inventory(root)
        val cursors = FakeChunkCursors()
        val runner = ChunkSync(ChunkOutbox(root), cursors, { _, _ -> error("No send") })
        repeat(2) {
            val status = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { error("No collection") } }
            assertEquals(ChunkPhase.BLOCKED, status.phase)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, status.reason)
            assertEquals(0, cursors.commits)
            assertEquals(before, inventory(root))
        }
    }

    @Test fun unreadablePointerEntriesBlockWithCorruptionWithoutAnyMutation() {
        for (name in listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp")) {
            val root = temporary.newFolder()
            File(root, name).mkdir()
            val before = inventory(root)
            val cursors = FakeChunkCursors()
            val runner = ChunkSync(ChunkOutbox(root), cursors, { _, _ -> error("No send") })
            val status = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { error("No collection") } }
            assertEquals(name, ChunkPhase.BLOCKED, status.phase)
            assertEquals(name, ChunkReason.CORRUPT_OUTBOX, status.reason)
            assertEquals(0, cursors.commits)
            assertEquals(before, inventory(root))
        }
    }

    @Test fun legacyPendingBlocksRecoveryBeforeAnyOutboxMutationOrCollection() {
        val root = temporary.newFolder()
        unpublished(root)
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = true
            override suspend fun commit(targets: Map<String, String>): Boolean = error("No cursor commit")
        }
        val before = inventory(root)
        val runner = ChunkSync(ChunkOutbox(root), cursors, { _, _ -> error("No send") })
        val status = runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { error("No collection") } }
        assertEquals(ChunkReason.LEGACY_PENDING, status.reason)
        assertEquals(before, inventory(root))
    }

    @Test fun tamperedTombstoneOrRemainingBodyNeverAuthorizesFurtherDeletion() {
        for (tamperTombstone in listOf(false, true)) {
            val root = temporary.newFolder()
            unpublished(root)
            val outbox = ChunkOutbox(root, releaseFault = { boundary, _ ->
                if (boundary == ReleaseBoundary.BODY_REMOVED) throw ProcessDeath()
            })
            assertThrows(ProcessDeath::class.java) { outbox.recoverStaging() }
            if (tamperTombstone) File(root, "staging-tombstone").writeText("corrupt")
            else File(root, "attempt/child-1.json").appendText("changed")
            val before = inventory(root)
            assertEquals(ChunkReason.CORRUPT_OUTBOX, ChunkOutbox(root).recoverStaging())
            assertEquals(before, inventory(root))
        }
    }
}
