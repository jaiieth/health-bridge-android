package dev.healthbridge

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttemptIntentTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val manifest: ChunkManifest, val plan: ChunkPlan.Ready) {
        val lengths get() = plan.children.map { it.body.size.toLong() }
    }

    private fun fixture(id: String = "attempt"): Fixture {
        val records = listOf(syntheticRecord("first"), syntheticRecord("second"))
        val endpoint = sha256("https://synthetic.invalid".toByteArray())
        val ids = records.map { it.recordId }
        val hashes = records.map { sha256(it.json.toByteArray()) }
        val targets = mapOf("StepsRecord" to "2026-11-01T00:00:00Z")
        val children = records.mapIndexed { index, record ->
            val batch = "batch-$index"
            PlannedChild(batch, listOf(record), ChunkPartitioner.envelope(batch, listOf(record)), 2)
        }
        return Fixture(ChunkManifest(id, parentFingerprint(endpoint, ids, hashes, targets), endpoint,
            ids, hashes, targets, 1700, 1700, ChunkPhase.STAGE,
            children.mapIndexed { index, child -> ChunkChild(index, child.batchId, sha256(child.body), 1) }), ChunkPlan.Ready(children))
    }

    private fun inventory(root: File): Map<String, String> = root.walkTopDown().drop(1).associate {
        it.relativeTo(root).path to when {
            Files.isSymbolicLink(it.toPath()) -> "symlink:${Files.readSymbolicLink(it.toPath())}"
            it.isDirectory -> "directory"
            else -> "${it.length()}:${sha256(it.readBytes())}"
        }
    }

    private fun assertRetained(root: File) = runBlocking {
        val before = inventory(root)
        var releases = 0
        val outbox = ChunkOutbox(root, releaseFault = { _, _ -> releases++ })
        val bytes = outbox.retainedBytes()
        val cursors = object : ChunkCursorStore {
            override suspend fun hasLegacyPending() = false
            override suspend fun commit(targets: Map<String, String>) = error("No cursor writes")
            override suspend fun committed(targets: Map<String, String>) = true
        }
        repeat(3) {
            outbox.recoverStaging()
            outbox.reclaimDone(cursors)
            assertEquals(before, inventory(root))
            assertEquals(bytes, outbox.retainedBytes())
        }
        assertEquals(0, releases)
    }

    private fun unpublished(root: File): Fixture {
        val fixture = fixture()
        val outbox = ChunkOutbox(root)
        val r = outbox.reserve(fixture.manifest, fixture.lengths.sum())
        outbox.beginReserved(r, fixture.manifest, fixture.lengths)
        fixture.plan.children.forEachIndexed { index, child -> outbox.stageReservedBody(r, index, child.body, child.body.size) }
        return fixture
    }

    @Test fun intentContainsExactReservationAndPlannedInventory() {
        val root = temporary.newFolder()
        val fixture = unpublished(root)
        val dir = File(root, "attempt")
        val bytes = File(dir, ATTEMPT_INTENT_NAME).readBytes()
        val intent = AttemptIntent.decode(bytes)
        val reservation = File(dir, "reservation").readBytes()
        assertEquals("health-bridge-attempt-v1", JSONObject(bytes.toString(Charsets.UTF_8)).getString("format"))
        assertEquals("attempt", intent.generation)
        assertEquals(intent.attemptId, UUID.fromString(intent.attemptId).toString())
        assertEquals(1, intent.writerProtocol)
        assertEquals(1700L, intent.createdAt)
        assertEquals(fixture.manifest.endpointHash, intent.endpointHash)
        assertEquals(fixture.manifest.fingerprint, intent.fingerprint)
        assertEquals(reservation.toString(Charsets.UTF_8).split('\n'), intent.reservationFields)
        assertEquals(8, intent.reservationFields.size)
        assertEquals(reservation.size.toLong(), intent.reservationBytes)
        assertEquals(sha256(reservation), intent.reservationSha)
        assertEquals(fixture.manifest.recordIds, intent.recordIds)
        assertEquals(fixture.manifest.recordHashes, intent.recordHashes)
        assertEquals(fixture.manifest.targets, intent.targets)
        assertEquals(listOf("child-0.json", "child-1.json"), intent.children.map { it.finalName })
        assertEquals(listOf("child-0.json.tmp", "child-1.json.tmp"), intent.children.map { it.tempName })
        assertEquals(listOf(0, 1), intent.children.map { it.index })
        assertEquals(listOf("batch-0", "batch-1"), intent.children.map { it.batchId })
        assertEquals(listOf(1, 1), intent.children.map { it.count })
        assertEquals(fixture.lengths, intent.children.map { it.bodyBytes })
        assertEquals(fixture.plan.children.map { sha256(it.body) }, intent.children.map { it.bodySha })
        assertTrue(intent.matches(fixture.manifest))
        assertRetained(root)
    }

    @Test fun bodyCrashesPreserveByteExactDurableIntentForListAndStreaming() {
        for (streaming in listOf(false, true)) for (kill in listOf(DurableBoundary.TEMP_WRITTEN, DurableBoundary.DIRECTORY_SYNCED)) {
            val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
            if (streaming) h.input = FreshChunkInput(mapOf("StepsRecord" to "2026-11-01T00:00:00Z"),
                FreshRecordProducer { accept -> listOf(syntheticRecord("r"), syntheticRecord("s")).forEach { accept(it) } })
            var durable: ByteArray? = null
            var generation: File? = null
            h.fault = { boundary, name ->
                if (name == ATTEMPT_INTENT_NAME && boundary == DurableBoundary.DIRECTORY_SYNCED) {
                    val file = h.root.walkTopDown().single { it.name == name }
                    durable = file.readBytes()
                    generation = file.parentFile
                }
                if (name == "child-0.json" && boundary == kill) {
                    assertNotNull(durable)
                    throw ProcessDeath()
                }
            }
            assertThrows(ProcessDeath::class.java) { h.run() }
            assertArrayEquals(durable, File(generation, ATTEMPT_INTENT_NAME).readBytes())
            val intent = AttemptIntent.decode(checkNotNull(durable))
            assertEquals(2, intent.children.size)
            assertTrue(File(generation, if (kill == DurableBoundary.TEMP_WRITTEN) "child-0.json.tmp" else "child-0.json").isFile)
            assertFalse(File(generation, "manifest.json").exists())
            assertFalse(File(h.root, "active").exists())
            assertTrue(h.sent.isEmpty())
            assertEquals(0, h.cursors.commits)
            assertRetained(h.root)
        }
    }

    @Test fun everyIntentFenceFailureAbortsBeforeBodyStagingAndSend() {
        for (streaming in listOf(false, true)) for (kill in DurableBoundary.entries) for (death in listOf(false, true)) {
            val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
            if (streaming) h.input = FreshChunkInput(mapOf("StepsRecord" to "2026-11-01T00:00:00Z"),
                FreshRecordProducer { it(syntheticRecord("r")) })
            var bodyWrites = 0
            h.fault = { boundary, name ->
                if (name.startsWith("child-")) bodyWrites++
                if (name == ATTEMPT_INTENT_NAME && boundary == kill) {
                    if (death) throw ProcessDeath() else throw IOException("synthetic intent fence failure")
                }
            }
            if (death) assertThrows(ProcessDeath::class.java) { h.run() }
            else assertEquals(ChunkReason.IO_FAILURE, h.run().reason)
            assertEquals(0, bodyWrites)
            assertTrue(h.sent.isEmpty())
            assertEquals(0, h.cursors.commits)
            assertFalse(h.root.walkTopDown().any { it.name.startsWith("child-") })
            if (death) assertRetained(h.root)
        }
    }

    @Test fun directorySyncFailureAfterIntentRenameCannotStageOrSend() {
        val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
        val outbox = ChunkOutbox(h.root, DurableFiles(syncDirectory = { dir ->
            if (File(dir, ATTEMPT_INTENT_NAME).exists()) throw IOException("synthetic directory fsync failure")
        }))
        val runner = ChunkSync(outbox, h.cursors, { _, _ -> error("No send") })
        assertEquals(ChunkReason.IO_FAILURE, runBlocking { runner.run(true, "https://synthetic.invalid", syntheticMapping) { h.input } }.reason)
        assertFalse(h.root.walkTopDown().any { it.name.startsWith("child-") })
        assertEquals(0, h.cursors.commits)
    }

    @Test fun reservationFencePrecedesIntentAndIntentFencePrecedesBodyAndSend() {
        val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
        val events = mutableListOf<String>()
        h.fault = { boundary, name -> events += "$name/$boundary" }
        h.reply = { id, _ ->
            assertTrue(events.indexOf("reservation/DIRECTORY_SYNCED") < events.indexOf("$ATTEMPT_INTENT_NAME/TEMP_WRITTEN"))
            assertTrue(events.indexOf("$ATTEMPT_INTENT_NAME/DIRECTORY_SYNCED") < events.indexOf("child-0.json/TEMP_WRITTEN"))
            assertEquals("SEND", h.manifestPhase())
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$id\"}")
        }
        assertEquals(ChunkPhase.DONE, h.run().phase)
        assertEquals(2, h.sent.size)
    }

    @Test fun stagingAndPublicationRejectAReservationWithoutTheDurableIntentFence() {
        val fixture = fixture()
        val root = temporary.newFolder()
        val outbox = ChunkOutbox(root)
        val r = outbox.reserve(fixture.manifest, fixture.lengths.sum())
        assertThrows(IllegalStateException::class.java) { outbox.stageReservedBody(r, 0, fixture.plan.children[0].body, fixture.lengths[0].toInt()) }
        assertThrows(IllegalStateException::class.java) { outbox.publishReserved(r, fixture.manifest) }
        assertTrue(inventory(root).isEmpty())
        outbox.beginReserved(r, fixture.manifest, fixture.lengths)
        val before = inventory(root)
        assertThrows(IllegalStateException::class.java) { ChunkOutbox(root).stageReservedBody(r, 0, fixture.plan.children[0].body, fixture.lengths[0].toInt()) }
        assertEquals(before, inventory(root))
    }

    @Test fun renameWithoutReplacementRefusesExistingTargetEvenAfterFileFsync() {
        for (injectAfterSync in listOf(false, true)) {
            val root = temporary.newFolder()
            val target = File(root, ATTEMPT_INTENT_NAME)
            val existing = "existing immutable intent".toByteArray()
            if (!injectAfterSync) target.writeBytes(existing)
            val files = DurableFiles(fault = { boundary, name ->
                if (injectAfterSync && boundary == DurableBoundary.FILE_SYNCED && name == ATTEMPT_INTENT_NAME) target.writeBytes(existing)
            })
            assertThrows(if (injectAfterSync) FileAlreadyExistsException::class.java else IllegalStateException::class.java) {
                files.write(target, "replacement".toByteArray(), replace = false, exclusiveTemp = true)
            }
            assertArrayEquals(existing, target.readBytes())
        }
    }

    @Test fun noReplacementAlsoRefusesAnExistingHardLinkToTheTemporaryIntent() {
        val root = temporary.newFolder()
        val target = File(root, ATTEMPT_INTENT_NAME)
        val files = DurableFiles(fault = { boundary, _ ->
            if (boundary == DurableBoundary.FILE_SYNCED) Files.createLink(target.toPath(), File(root, "$ATTEMPT_INTENT_NAME.tmp").toPath())
        })
        assertThrows(FileAlreadyExistsException::class.java) { files.write(target, "immutable".toByteArray(), replace = false, exclusiveTemp = true) }
        assertEquals("immutable", target.readText())
        assertEquals("immutable", File(root, "$ATTEMPT_INTENT_NAME.tmp").readText())
    }

    @Test fun immutableTemporaryIntentAlsoRefusesExistingFileOrSymlink() {
        for (symlink in listOf(false, true)) {
            val root = temporary.newFolder()
            val target = File(root, ATTEMPT_INTENT_NAME)
            val temp = File(root, "$ATTEMPT_INTENT_NAME.tmp")
            val evidence = File(root, "evidence").apply { writeText("preserve") }
            if (symlink) Files.createSymbolicLink(temp.toPath(), evidence.toPath()) else temp.writeText("preserve")
            assertThrows(FileAlreadyExistsException::class.java) { DurableFiles().write(target, byteArrayOf(1), replace = false, exclusiveTemp = true) }
            assertEquals("preserve", evidence.readText())
            assertEquals("preserve", temp.readText())
            assertFalse(target.exists())
        }
    }

    @Test fun conflictingIntentAtRenameFailsClosedAndPreservesConflict() {
        val root = temporary.newFolder()
        val fixture = fixture()
        val outbox = ChunkOutbox(root, DurableFiles(fault = { boundary, name ->
            if (name == ATTEMPT_INTENT_NAME && boundary == DurableBoundary.FILE_SYNCED) File(root, "attempt/$name").writeText("conflict")
        }))
        assertThrows(FileAlreadyExistsException::class.java) { outbox.stage(fixture.manifest, fixture.plan) }
        assertEquals("conflict", File(root, "attempt/$ATTEMPT_INTENT_NAME").readText())
        assertFalse(File(root, "attempt/child-0.json").exists())
        assertRetained(root)
    }

    @Test fun malformedMissingExtraAndDuplicateFieldsFailClosed() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("format") }, { it.put("extra", true) }, { it.put("format", "unknown") },
            { it.put("writerProtocol", 2) }, { it.put("createdAt", "1700") }, { it.put("reservationBytes", 1) },
            { it.put("reservationSha", "0".repeat(64)) }, { it.put("fingerprint", "0".repeat(64)) },
            { it.getJSONArray("recordIds").put(0, "conflicting-record") },
            { it.getJSONArray("children").getJSONObject(0).put("extra", 1) },
            { it.getJSONArray("children").getJSONObject(0).remove("bodySha") },
            { it.getJSONArray("children").getJSONObject(0).put("finalName", "../outside") },
        )
        for (mutate in mutations) {
            val root = temporary.newFolder()
            unpublished(root)
            val intent = File(root, "attempt/$ATTEMPT_INTENT_NAME")
            val json = JSONObject(intent.readText()).also(mutate)
            intent.writeText(json.toString())
            assertThrows(IllegalArgumentException::class.java) { AttemptIntent.decode(intent.readBytes()) }
            assertRetained(root)
        }
        for (text in listOf("{", "{}", "{\"format\":1,\"format\":2}")) {
            val root = temporary.newFolder()
            unpublished(root)
            val intent = File(root, "attempt/$ATTEMPT_INTENT_NAME").apply { writeText(text) }
            assertTrue(runCatching { AttemptIntent.decode(intent.readBytes()) }.isFailure)
            assertRetained(root)
        }
    }

    @Test fun mismatchingPartialTempBodiesAndUnknownEntriesAreRetained() {
        val mutations: List<(File) -> Unit> = listOf(
            { File(it, "child-0.json").writeText("partial") },
            { dir -> Files.move(File(dir, "child-0.json").toPath(), File(dir, "child-0.json.tmp").toPath()); File(dir, "child-0.json.tmp").writeText("partial") },
            { File(it, "child-0.json.tmp").writeBytes(File(it, "child-0.json").readBytes()) },
            { File(it, "child-9.json").writeText("unknown") },
            { File(it, "unknown").writeText("unknown") },
            { File(it, "reservation").appendText("\n") },
            { File(it, "reservation").writeText("malformed") },
            { File(it, "reservation").delete() },
            { File(it, ATTEMPT_INTENT_NAME).delete() },
            { File(it, "$ATTEMPT_INTENT_NAME.tmp").writeBytes(File(it, ATTEMPT_INTENT_NAME).readBytes()) },
        )
        for (mutate in mutations) {
            val root = temporary.newFolder()
            unpublished(root)
            mutate(File(root, "attempt"))
            assertRetained(root)
        }
    }

    @Test fun symlinksAndNonRegularIntentEntriesNeverAuthorizeDeletion() {
        for (name in listOf(ATTEMPT_INTENT_NAME, "reservation", "child-0.json")) for (directory in listOf(false, true)) {
            val root = temporary.newFolder()
            unpublished(root)
            val file = File(root, "attempt/$name")
            val outside = File(temporary.newFolder(), "evidence").apply { writeBytes(file.readBytes()) }
            file.delete()
            if (directory) file.mkdir() else Files.createSymbolicLink(file.toPath(), outside.toPath())
            val before = outside.readBytes()
            assertRetained(root)
            assertArrayEquals(before, outside.readBytes())
        }
    }

    @Test fun unreadableIntentFailsClosedWithoutChangingRetainedBytes() {
        val root = temporary.newFolder()
        unpublished(root)
        val intent = File(root, "attempt/$ATTEMPT_INTENT_NAME")
        val bytes = intent.readBytes()
        val before = inventory(root)
        assertTrue(intent.setReadable(false, false))
        try {
            assertFalse(Files.isReadable(intent.toPath()))
            val outbox = ChunkOutbox(root, releaseFault = { _, _ -> error("No deletion") })
            repeat(3) { assertNull(outbox.recoverStaging()) }
        } finally { assertTrue(intent.setReadable(true, true)) }
        assertArrayEquals(bytes, intent.readBytes())
        assertEquals(before, inventory(root))
    }

    @Test fun allPointerNamesReceiptsAndTombstonesPreventUseAndRetainInventory() {
        for (name in listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp",
                "receipt-attempt.json", "receipt-attempt.json.tmp", "staging-tombstone", "staging-tombstone.tmp")) {
            val root = temporary.newFolder()
            val fixture = fixture()
            ChunkOutbox(root).stage(fixture.manifest, fixture.plan)
            val proof = File(root, "active-proof").readBytes()
            listOf("active", "active-proof").forEach { File(root, it).delete() }
            val bytes = if (name.startsWith("active-proof") || name.startsWith("previous")) proof
                else if (name.startsWith("active")) "attempt".toByteArray() else "conflicting evidence".toByteArray()
            File(root, name).writeBytes(bytes)
            assertRetained(root)
            File(root, name).writeText("malformed reference")
            assertRetained(root)
        }
    }

    @Test fun legacyNoIntentPrepublicationInventoryRetainsExactly() {
        val root = temporary.newFolder()
        unpublished(root)
        val dir = File(root, "attempt")
        val fields = File(dir, "reservation").readLines().toMutableList()
        fields[5] = (fields[5].toLong() - 2 * attemptIntentBudget(fields[4].toLong())).toString()
        File(dir, "reservation").writeText(fields.joinToString("\n"))
        File(dir, ATTEMPT_INTENT_NAME).delete()
        File(root, "legacy-Pending").writeText("legacy pending evidence")
        File(root, "legacy-cursors").writeText("legacy cursor evidence")
        File(dir, "child-0.json.tmp").writeText("partial legacy temp")
        val outbox = ChunkOutbox(root)
        val before = inventory(root)
        repeat(3) { assertNull(outbox.recoverStaging()); assertEquals(before, inventory(root)) }
        assertRetained(root)
    }

    @Test fun intentAdmissionReservesBothCopiesWithoutChangingLimits() {
        val root = temporary.newFolder()
        val fixture = fixture()
        var peak = 0L
        val outbox = ChunkOutbox(root, DurableFiles(fault = { _, _ ->
            peak = maxOf(peak, root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }))
        val r = outbox.reserve(fixture.manifest, fixture.lengths.sum())
        assertEquals(r.legacyPeak() + 2 * attemptIntentBudget(r.manifest), r.peak)
        assertEquals(64L * 1024 * 1024, OUTBOX_HARD_QUOTA_BYTES)
        assertEquals(3, RETAINED_DONE_GENERATIONS)
        assertEquals(524_288L, ChunkLimits().wireBytes)
        outbox.beginReserved(r, fixture.manifest, fixture.lengths)
        val intentBytes = File(root, "attempt/$ATTEMPT_INTENT_NAME").length()
        assertTrue(intentBytes <= attemptIntentBudget(r.manifest))
        assertTrue(2 * attemptIntentBudget(r.manifest) >= 2 * intentBytes)
        assertTrue(peak >= intentBytes + File(root, "attempt/reservation").length())
        assertTrue(peak <= r.peak)
    }

    @Test fun generationAttemptsHaveDistinctIdsAndIntentSurvivesManifestUpdates() {
        val root = temporary.newFolder()
        val fixture = fixture()
        val outbox = ChunkOutbox(root)
        outbox.stage(fixture.manifest, fixture.plan)
        val first = File(root, "attempt/$ATTEMPT_INTENT_NAME").readBytes()
        outbox.save(fixture.manifest.copy(phase = ChunkPhase.SEND, children = fixture.manifest.children.map { it.copy(sentAt = 1701) }))
        assertArrayEquals(first, File(root, "attempt/$ATTEMPT_INTENT_NAME").readBytes())
        val second = fixture("another")
        ChunkOutbox(root).stage(second.manifest, second.plan)
        assertNotEquals(AttemptIntent.decode(first).attemptId, AttemptIntent.decode(File(root, "another/$ATTEMPT_INTENT_NAME").readBytes()).attemptId)
    }
}
