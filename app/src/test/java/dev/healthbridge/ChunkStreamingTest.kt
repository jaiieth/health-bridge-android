package dev.healthbridge

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkStreamingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val time = Instant.parse("2026-11-12T00:00:00Z")
    private val targets = mapOf("StepsRecord" to "2026-11-12T00:00:00Z")
    private fun root() = File(temporary.newFolder(), "outbox")
    private fun input(produce: suspend (suspend (ChunkRecord) -> Unit) -> Unit) = FreshChunkInput(targets, FreshRecordProducer(produce))
    private fun input(records: List<ChunkRecord>) = input { accept -> records.forEach { accept(it) } }
    private fun record(i: Int, value: String = "aé中😀\n\\\"") = ChunkRecord("r$i",
        "{\"type\":\"StepsRecord\",\"source\":{\"package\":\"synthetic\"},\"recordId\":\"r$i\",\"lastModifiedTime\":\"2026-11-12T00:00:00.123456789Z\",\"values\":{\"v\":" + EcmaJson.quote(value) + ",\"samples\":[1,2,3]}}")

    private fun stage(
        outbox: ChunkOutbox, input: FreshChunkInput, limits: ChunkLimits = ChunkLimits(),
        budget: FreshGenerationLimits = FreshGenerationLimits(), retention: StreamingRetention = StreamingRetention(),
        mapping: VerifiedReceiverMapping = ChunkContracts.verifiedMapping,
    ): ChunkManifest = runBlocking {
        var id = 0
        StreamingChunkPlanner(outbox, mapping, limits, { "stream-${id++}" }, { 1700L }, budget, retention)
            .stage(input, sha256("synthetic".toByteArray()))
    }

    @Test fun manySingleRecordPagesConserveEveryHashInActualChildFilesWithBoundedBuffers() {
        val outbox = ChunkOutbox(root())
        val retention = StreamingRetention()
        val pages = 20_000
        var requests = 0
        var emitted = 0
        var insideAccept = false
        var largestChars = 0
        var largestBytes = 0
        val input = input { accept ->
            produceFreshPages(readPage = { token ->
                assertFalse(insideAccept)
                val index = token?.toInt() ?: 0
                assertEquals(emitted % pages, index)
                requests++
                FreshPage(listOf(record(index)), if (index + 1 < pages) (index + 1).toString() else null)
            }, accept = { record ->
                insideAccept = true
                largestChars = maxOf(largestChars, record.json.length)
                largestBytes = maxOf(largestBytes, record.json.toByteArray().size)
                accept(record)
                emitted++
                insideAccept = false
            })
        }
        val manifest = stage(outbox, input, retention = retention)
        assertEquals(ChunkPhase.STAGE, manifest.phase)
        assertEquals(pages * 2, requests)
        assertEquals(pages * 2, emitted)
        assertEquals(pages, retention.recordCount)
        assertEquals(1, retention.peakIncomingRecords)
        assertTrue(retention.peakRecords in 2..5001)
        assertEquals(524_288L + 2L * largestChars + largestBytes, retention.peakPayloadBytes)
        assertTrue(retention.peakPayloadBytes <= 2_097_152)
        assertEquals(manifest.children.size, retention.childCount)
        val hashes = mutableListOf<String>()
        val ids = mutableListOf<String>()
        manifest.children.forEachIndexed { index, child ->
            val bytes = outbox.body(manifest, index)
            assertTrue(bytes.size <= 524_288 && child.count <= 5000)
            val wire = EcmaJson.parse(bytes.toString(Charsets.UTF_8)) as Map<*, *>
            val records = wire["records"] as List<*>
            assertEquals(child.count, records.size)
            assertTrue(records.sumOf { productionMappedRecordBytes(ChunkRecord((it as Map<*, *>)["recordId"] as String, EcmaJson.stringify(it))) } +
                2 + (records.size - 1).coerceAtLeast(0) <= 900_000)
            records.forEach {
                val record = it as Map<*, *>
                ids += record["recordId"] as String
                hashes += sha256(EcmaJson.stringify(record).toByteArray())
            }
        }
        assertEquals((0 until pages).map { "r$it" }, ids)
        assertEquals((0 until pages).map { sha256(record(it).json.toByteArray()) }, hashes)
        assertEquals(hashes, manifest.recordHashes)
        assertEquals(hashes.size, hashes.distinct().size)
        assertEquals(retention.metadataUtf16Bytes, manifest.recordIds.sumOf { 2L * (it.length + 64) })
        assertEquals(manifest, outbox.load())
    }

    @Test fun exactReferenceWireBytesOrderAndBoundariesForIndependentCapsAndVariableBatchIds() {
        val records = List(31) { record(it, "😀\"\\\n".repeat(it % 5 + 1)) }
        listOf(ChunkLimits(count = 3), ChunkLimits(wireBytes = 800), ChunkLimits(mappedBytes = 1100)).forEach { limits ->
            val outbox = ChunkOutbox(root())
            var next = -1
            val ids = List(records.size) { "c${it}" + "z".repeat(it % 4 * 7) }
            val manifest = runBlocking {
                StreamingChunkPlanner(outbox, ChunkContracts.verifiedMapping, limits,
                    { if (next++ == -1) "generation" else ids[next - 1] }, { 1700L }).stage(input(records), sha256("endpoint".toByteArray()))
            }
            val expected = ChunkPartitioner.partition(records, ids, ProductionReceiverMapping(), limits) as ChunkPlan.Ready
            assertEquals(expected.children.map { it.batchId }, manifest.children.map { it.batchId })
            assertEquals(expected.children.map { it.records.size }, manifest.children.map { it.count })
            expected.children.forEachIndexed { index, child -> assertArrayEquals(child.body, outbox.body(manifest, index)) }
        }
    }

    @Test fun emptyStreamMatchesReferenceEmptyEnvelope() {
        val outbox = ChunkOutbox(root())
        val manifest = stage(outbox, input(emptyList()))
        assertEquals(1, manifest.children.size)
        assertArrayEquals("{\"schemaVersion\":1,\"batchId\":\"stream-1\",\"records\":[]}".toByteArray(), outbox.body(manifest, 0))
    }

    @Test fun singleRecordExactWireAndMappedBoundariesMatchReference() {
        val record = record(0)
        val wire = ChunkPartitioner.envelope("stream-1", listOf(record)).size.toLong()
        val mapped = ProductionReceiverMapping().mappedRecordJson(record).toByteArray().size + 2L
        listOf(ChunkLimits(wireBytes = wire), ChunkLimits(wireBytes = wire - 1),
            ChunkLimits(mappedBytes = mapped), ChunkLimits(mappedBytes = mapped - 1)).forEach { limits ->
            val expected = ChunkPartitioner.partition(listOf(record), listOf("stream-1"), ProductionReceiverMapping(), limits)
            val outbox = ChunkOutbox(root())
            val manifest = stage(outbox, input(listOf(record)), limits)
            when (expected) {
                is ChunkPlan.Ready -> {
                    assertEquals(ChunkPhase.STAGE, manifest.phase)
                    assertArrayEquals(expected.children.single().body, outbox.body(manifest, 0))
                }
                is ChunkPlan.Blocked -> {
                    assertEquals(ChunkReason.OVERSIZED, manifest.reason)
                    assertEquals(expected.recordId, manifest.blockedRecordId)
                    assertEquals(expected.wireBytes, manifest.blockedWireBytes)
                    assertEquals(expected.mappedBytes, manifest.blockedMappedBytes)
                    assertTrue(manifest.children.isEmpty())
                }
            }
        }
    }

    @Test fun emptyEnvelopeCapsAndLongChildOverheadMatchReferenceBlockedEvidence() {
        listOf(emptyList(), listOf(record(0))).forEach { records ->
            listOf(ChunkLimits(wireBytes = 1), ChunkLimits(mappedBytes = 1)).forEach { limits ->
                val outbox = ChunkOutbox(root())
                val expected = ChunkPartitioner.partition(records, listOf("stream-1"), ProductionReceiverMapping(), limits) as ChunkPlan.Blocked
                val actual = stage(outbox, input(records), limits)
                assertEquals(if (records.isEmpty()) ChunkReason.ENVELOPE_LIMIT else ChunkReason.OVERSIZED, actual.reason)
                assertEquals(expected.recordId, actual.blockedRecordId)
                assertEquals(expected.wireBytes, actual.blockedWireBytes)
                assertEquals(expected.mappedBytes, actual.blockedMappedBytes)
                assertTrue(actual.children.isEmpty())
            }
        }
    }

    @Test fun productionMappedCounterMatchesIndependentNodeFixturesAndReference() {
        val all = JSONObject(checkNotNull(javaClass.getResource("/receiver-mapping-fixtures.json")).readText())
        val cases = all.getJSONArray("fixtures")
        repeat(cases.length()) { index ->
            val fixture = cases.getJSONObject(index)
            val record = ChunkRecord(fixture.getString("id"), fixture.getString("input"))
            assertEquals(fixture.getString("mapped").toByteArray().size.toLong(), productionMappedRecordBytes(record))
        }
        val special = record(0, "\ud800\udc00\ud800\u0000\u2028\"\\")
        assertEquals(ProductionReceiverMapping().mappedRecordJson(special).toByteArray().size.toLong(), productionMappedRecordBytes(special))
        val numericKeys = record(1).copy(json = record(1).json.dropLast(1) + ",\"10\":1e23,\"2\":9007199254740993,\"01\":-0,\"0\":[1e-7,1e400]}")
        assertEquals(ProductionReceiverMapping().mappedRecordJson(numericKeys).toByteArray().size.toLong(), productionMappedRecordBytes(numericKeys))
    }

    @Test fun streamingFingerprintKeepsExistingLengthPrefixedFormat() {
        assertEquals("7df2bfa36abbda35932a52882e4921e8c75038856df6a75e9570b38186fa00c7", parentFingerprint(
            "endpoint", listOf("aé中😀", "second"), listOf("0".repeat(64), "1".repeat(64)),
            mapOf("StepsRecord" to "2026-11-12T00:00:00Z", "SleepSessionRecord" to "2026-11-12T01:00:00Z"),
        ))
    }

    @Test fun boundedSerializerMatchesEveryLegacySemanticFieldIncludingAllNestedSamples() {
        val offset = ZoneOffset.ofHours(7)
        val metadata = Metadata.manualEntryWithId("record-1")
        val end = time.plusSeconds(100)
        val records = listOf(
            StepsRecord(time, offset, end, null, 1200, metadata),
            RestingHeartRateRecord(time, null, 60, metadata),
            HeartRateVariabilityRmssdRecord(time, offset, 7.25, metadata),
            HeartRateRecord(time, offset, end, offset, listOf(HeartRateRecord.Sample(time, 60), HeartRateRecord.Sample(end, 70)), metadata),
            SleepSessionRecord(time, offset, end, null, metadata, stages = listOf(SleepSessionRecord.Stage(time, end, 1))),
            ExerciseSessionRecord(time, offset, end, null, metadata, 0),
        )
        records.forEach { sdk ->
            val fresh = boundedRecordJson(sdk, "app\"\\\né中😀\ud800")
            val legacy = recordJson(sdk, "app\"\\\né中😀\ud800").toString()
            assertEquals(EcmaJson.parse(legacy), EcmaJson.parse(fresh.json))
            assertEquals("record-1", fresh.recordId)
        }
    }

    @Test fun writerChecksEscapingBeforeAllocatingOversizedStringsAndFitsExactCap() {
        val writer = BoundedJsonWriter(6)
        writer.string("😀")
        assertEquals("\"😀\"", writer.json())
        val full = BoundedJsonWriter()
        full.string("x".repeat(524_286))
        assertEquals(524_288, full.size)
        val escaped = BoundedJsonWriter()
        val exception = assertThrows(OversizedFreshRecord::class.java) { escaped.string("\u0000".repeat(100_000)) }
        assertEquals(524_288, escaped.size)
        assertEquals(524_289L, exception.wireBytes)
    }

    @Test fun oversizedFirstSdkRecordDefaultCapsPublishesBlockedWithNoChildSendOrCursorChange() {
        val folder = root()
        val evidence = File(folder, "older/child-0.json")
        evidence.parentFile!!.mkdirs()
        evidence.writeText("retained evidence")
        val outbox = ChunkOutbox(folder)
        val cursors = FakeChunkCursors()
        var sent = 0
        var id = 0
        val runner = ChunkSync(outbox, cursors, send = { _, _ -> sent++; error("No send") }, newId = { "id-${id++}" })
        val status = runBlocking { runner.run(true, "synthetic", ChunkContracts.verifiedMapping) {
            input { accept -> accept(boundedRecordJson(StepsRecord(time, null, time.plusSeconds(1), null, 1, Metadata.manualEntryWithId("huge")), "x".repeat(524_288))) }
        } }
        assertEquals(ChunkPhase.BLOCKED, status.phase)
        assertEquals(ChunkReason.OVERSIZED, status.reason)
        assertEquals(0, status.total)
        assertEquals("huge", outbox.load()!!.blockedRecordId)
        assertEquals(emptyList<ChunkChild>(), outbox.load()!!.children)
        assertEquals(0, sent)
        assertEquals(0, cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), cursors.values)
        assertEquals("retained evidence", evidence.readText())
        assertEquals(listOf(evidence), folder.walkTopDown().filter { it.name.startsWith("child-") }.toList())
    }

    @Test fun lateOversizedRetainsUnpublishedEvidenceButReferencesNoChildrenAndNeverSends() {
        val folder = root()
        val outbox = ChunkOutbox(folder)
        val cursors = FakeChunkCursors()
        var id = 0
        val runner = ChunkSync(outbox, cursors, send = { _, _ -> error("No send") }, limits = ChunkLimits(count = 1), newId = { "id-${id++}" })
        val status = runBlocking { runner.run(true, "synthetic", ChunkContracts.verifiedMapping) {
            input { accept ->
                accept(record(0)); accept(record(1))
                assertFalse(File(folder, "active").exists())
                accept(record(2, "x".repeat(524_288)))
            }
        } }
        assertEquals(ChunkReason.OVERSIZED, status.reason)
        val manifest = outbox.load()!!
        assertEquals(ChunkPhase.BLOCKED, manifest.phase)
        assertTrue(manifest.children.isEmpty())
        assertEquals(0, cursors.commits)
        assertTrue(File(folder, manifest.generation).listFiles()!!.none { it.name.startsWith("child-") })
        val before = snapshot(folder)
        assertEquals(ChunkReason.OVERSIZED, runBlocking { runner.run(true, "synthetic", ChunkContracts.verifiedMapping) { error("No recollection") } }.reason)
        assertEquals(before, snapshot(folder))
    }

    @Test fun metadataCapsCheckBeforeInsertionAndRefuseEntireWindow() {
        val records = listOf(record(0), record(1), record(2))
        val perRecord = 2L * (records[0].recordId.length + 64)
        listOf(FreshGenerationLimits(records = 2), FreshGenerationLimits(metadataUtf16Bytes = perRecord * 2)).forEach { budget ->
            val outbox = ChunkOutbox(root())
            val retention = StreamingRetention()
            val manifest = stage(outbox, input(records), budget = budget, retention = retention)
            assertEquals(ChunkReason.GENERATION_RECORD_LIMIT, manifest.reason)
            assertEquals(ChunkPhase.BLOCKED, manifest.phase)
            assertEquals("r2", manifest.blockedRecordId)
            assertEquals(listOf("r0", "r1"), manifest.recordIds)
            assertEquals(perRecord * 2, retention.metadataUtf16Bytes)
            assertEquals(2, retention.recordCount)
            assertTrue(manifest.children.isEmpty())
            assertEquals(manifest, outbox.load())
        }
    }

    @Test fun exactMetadataBudgetAndHundredthChildAreAdmittedThenNextChildBlocks() {
        val records = listOf(record(0), record(1))
        val outbox = ChunkOutbox(root())
        val bytes = records.sumOf { 2L * (it.recordId.length + 64) }
        assertEquals(ChunkPhase.STAGE, stage(outbox, input(records), budget = FreshGenerationLimits(metadataUtf16Bytes = bytes)).phase)
        val full = ChunkOutbox(root())
        assertEquals(100, stage(full, input { accept -> repeat(100) { accept(record(it)) } }, ChunkLimits(count = 1)).children.size)
        val overflow = ChunkOutbox(root())
        val refused = stage(overflow, input { accept -> repeat(101) { accept(record(it)) } }, ChunkLimits(count = 1))
        assertEquals(ChunkReason.GENERATION_CHILD_LIMIT, refused.reason)
        assertEquals("r100", refused.blockedRecordId)
        assertTrue(refused.children.isEmpty())
        assertEquals(ChunkPhase.BLOCKED, overflow.load()!!.phase)
    }

    @Test fun defaultHundredThousandRecordCapBlocksWithoutSendOrCursorAdvance() {
        val outbox = ChunkOutbox(root())
        val cursors = FakeChunkCursors()
        var id = 0
        var accepted = 0
        val runner = ChunkSync(outbox, cursors, send = { _, _ -> error("No send") }, newId = { "id-${id++}" })
        val record = syntheticRecord("r")
        val status = runBlocking { runner.run(true, "synthetic", syntheticMapping) {
            input { accept -> repeat(100_001) { accept(record); accepted++ } }
        } }
        assertEquals(100_000, accepted)
        assertEquals(ChunkPhase.BLOCKED, status.phase)
        assertEquals(ChunkReason.GENERATION_RECORD_LIMIT, status.reason)
        assertEquals(0, status.total)
        val manifest = outbox.load()!!
        assertEquals(100_000, manifest.recordIds.size)
        assertTrue(manifest.children.isEmpty())
        assertEquals(0, cursors.commits)
        assertEquals(mapOf("steps" to "old", "sleep" to "old"), cursors.values)
    }

    @Test fun defaultManifestBudgetCountsIdsAndHashesBeforeInsertion() {
        val outbox = ChunkOutbox(root())
        val retention = StreamingRetention()
        val manifest = stage(outbox, input { accept ->
            repeat(1025) { index ->
                val id = "r" + index.toString().padStart(4, '0') + "x".repeat(8123)
                accept(ChunkRecord(id, "{\"recordId\":\"$id\"}"))
            }
        }, retention = retention, mapping = syntheticMapping)
        assertEquals(16_547_840L, retention.metadataUtf16Bytes)
        assertEquals(1010, retention.recordCount)
        assertEquals(1010, manifest.recordHashes.size)
        assertEquals(ChunkReason.MANIFEST_LIMIT, manifest.reason)
        assertTrue(manifest.children.isEmpty())
        assertNull(outbox.load())
    }

    @Test fun invalidAndRepeatedBatchIdsCannotPublishAStream() {
        listOf("invalid/child", "duplicate").forEach { childId ->
            val folder = root()
            val outbox = ChunkOutbox(folder)
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                StreamingChunkPlanner(outbox, syntheticMapping, ChunkLimits(count = 1),
                    { if (calls++ == 0) "generation" else childId }, { 1700L })
                    .stage(input(listOf(syntheticRecord("a"), syntheticRecord("b"))), sha256("endpoint".toByteArray()))
            } }
            assertNull(outbox.load())
            assertFalse(File(folder, "active").exists())
            assertFalse(File(folder, "generation/child-0.json").exists())
        }
    }

    @Test fun invalidTimestampAndRecordIdMappingMismatchFailClosed() {
        val outbox = ChunkOutbox(root())
        val manifest = stage(outbox, input(listOf(record(0).let { it.copy(json = it.json.replace("2026-11-12", "2026-11-32")) })))
        assertEquals(ChunkReason.INVALID_TIMESTAMP, manifest.reason)
        assertEquals("r0", manifest.blockedRecordId)
        assertTrue(manifest.children.isEmpty())
        val mismatch = ChunkOutbox(root())
        assertThrows(IllegalArgumentException::class.java) { stage(mismatch, input(listOf(record(0).copy(recordId = "other")))) }
        assertNull(mismatch.load())
    }

    @Test fun streamRestartUsesDurabilityFenceAndStoredBodiesWithoutRecollection() {
        val folder = root()
        val initial = ChunkOutbox(folder, DurableFiles(fault = { boundary, name ->
            if (boundary == DurableBoundary.RENAMED && name == "active") throw IOException("ENOSPC")
        }))
        val cursors = FakeChunkCursors()
        var id = 0
        val failed = ChunkSync(initial, cursors, send = { _, _ -> error("No send") }, newId = { "id-${id++}" })
        assertEquals(ChunkReason.IO_FAILURE, runBlocking { failed.run(true, "synthetic", ChunkContracts.verifiedMapping) { input(listOf(record(0))) } }.reason)
        val before = snapshot(folder)
        var failFence = true
        val synced = mutableListOf<File>()
        val resumed = ChunkOutbox(folder, DurableFiles(syncDirectory = { dir ->
            synced += dir
            if (failFence && dir == folder) throw IOException("ENOSPC")
        }))
        val saved = resumed.load()!!
        val runner = ChunkSync(resumed, cursors, send = { batch, body ->
            assertEquals(listOf(folder.parentFile, folder, File(folder, saved.generation)), synced.take(3))
            assertArrayEquals(resumed.body(saved, 0), body)
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
        })
        fun run() = runBlocking { runner.run(true, "synthetic", ChunkContracts.verifiedMapping) { error("No recollection") } }
        assertEquals(ChunkReason.IO_FAILURE, run().reason)
        assertEquals(before, snapshot(folder))
        failFence = false
        synced.clear()
        assertEquals(ChunkPhase.DONE, run().phase)
        assertEquals(1, cursors.commits)
        assertEquals(targets["StepsRecord"], cursors.values["StepsRecord"])
    }

    @Test fun streamBodyAndPublicationFailuresNeverRetryWriteOrDeleteEvidence() {
        listOf("child-0.json", "manifest.json", "active").forEach { name ->
            DurableBoundary.entries.forEach { boundary ->
                val folder = root()
                var failed: Map<String, List<Byte>>? = null
                var writesAfter = 0
                val outbox = ChunkOutbox(folder, DurableFiles(fault = { actual, file ->
                    if (failed != null) writesAfter++
                    else if (actual == boundary && file == name) {
                        failed = snapshot(folder)
                        throw IOException("ENOSPC")
                    }
                }))
                val cursors = FakeChunkCursors()
                var id = 0
                val runner = ChunkSync(outbox, cursors, send = { _, _ -> error("No send") }, newId = { "id-${id++}" })
                val status = runBlocking { runner.run(true, "synthetic", ChunkContracts.verifiedMapping) { input(listOf(record(0))) } }
                assertEquals("$name $boundary", ChunkReason.IO_FAILURE, status.reason)
                assertNotNull(failed)
                assertEquals(0, writesAfter)
                if (name == "active" && boundary in listOf(DurableBoundary.RENAMED, DurableBoundary.DIRECTORY_SYNCED)) {
                    assertEquals(failed, snapshot(folder))
                } else assertTrue(snapshot(folder).isEmpty())
                assertEquals(0, cursors.commits)
            }
        }
    }

    @Test fun freeSpaceIsCheckedBeforeEachBodyAndPublication() {
        repeat(3) { failureAt ->
            val folder = root()
            var probes = 0
            val outbox = ChunkOutbox(folder, usableSpace = { if (probes++ == failureAt) 0 else Long.MAX_VALUE })
            assertThrows(IOException::class.java) { stage(outbox, input(listOf(record(0)))) }
            assertEquals(failureAt + 1, probes)
            assertNull(outbox.load())
            assertEquals(0, folder.walkTopDown().count { it.name == "child-0.json" })
        }
    }

    @Test fun multiRecordPageAndUnboundedTargetsAreRefusedBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            produceFreshPages(readPage = { FreshPage(listOf(record(0), record(1)), null) }, accept = { fail("No oversized page accepted") })
        } }
        val outbox = ChunkOutbox(root())
        val bad = FreshChunkInput(mapOf("unknown" to time.toString()), FreshRecordProducer { error("No collection") })
        assertThrows(IllegalArgumentException::class.java) { stage(outbox, bad) }
        assertNull(outbox.load())
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }
}
