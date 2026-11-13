package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestByteBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun input(record: ChunkRecord, count: Int) = FreshChunkInput(emptyMap(), FreshRecordProducer { accept ->
        repeat(count) { accept(record) }
    })
    private fun stage(root: File, input: FreshChunkInput, budget: FreshGenerationLimits = FreshGenerationLimits(), count: Int = 5000): ChunkManifest = runBlocking {
        var id = 0
        StreamingChunkPlanner(ChunkOutbox(root), syntheticMapping, ChunkLimits(count = count),
            { "budget-${id++}" }, { 0L }, budget).stage(input, sha256("endpoint".toByteArray()))
    }

    @Test fun escapingBoundCoversRealJsonIncludingControlsAndSurrogates() {
        val value = (0..31).map { it.toChar() }.joinToString("") + "\"\\/é中😀\ud800\u0080\u2028"
        assertTrue(JSONObject.quote(value).toByteArray(Charsets.UTF_8).size <= manifestStringBytes(value))
        assertEquals(8L, manifestStringBytes("\u0000"))
        assertEquals(4L, manifestStringBytes("\""))
        assertEquals(4L, manifestStringBytes("\\"))
        assertEquals(4L, manifestStringBytes("/"))
        assertEquals(6L, manifestStringBytes("😀"))
    }

    @Test fun adversarialIdsBlockWithoutPublicationAndPreserveStagedBodiesAndActivePointer() {
        listOf(false, true).forEach { active ->
            val root = File(temporary.newFolder(), "outbox")
            root.mkdirs()
            if (active) File(root, "active").writeText("older-generation")
            val id = "\u0000\"\\/😀".repeat(5000)
            val record = ChunkRecord(id, "{\"recordId\":" + JSONObject.quote(id) + "}")
            val result = stage(root, input(record, 1000))
            assertEquals(ChunkPhase.BLOCKED, result.phase)
            assertEquals(ChunkReason.MANIFEST_LIMIT, result.reason)
            assertTrue(result.recordIds.isNotEmpty())
            assertTrue(result.children.isEmpty())
            assertFalse(File(root, "${result.generation}/manifest.json").exists())
            if (active) assertEquals("older-generation", File(root, "active").readText())
            else assertFalse(File(root, "active").exists())
            val bodies = (File(root, result.generation).listFiles() ?: emptyArray()).filter { it.name.startsWith("child-") }
            assertTrue(bodies.isEmpty())
            bodies.forEach { body ->
                val records = JSONObject(body.readText()).getJSONArray("records")
                repeat(records.length()) { assertEquals(id, records.getJSONObject(it).getString("recordId")) }
            }
        }
    }

    @Test fun maximumDefaultRecordGenerationHasBoundedActualManifest() {
        val root = File(temporary.newFolder(), "outbox")
        val budget = FreshGenerationLimits()
        val manifest = stage(root, input(syntheticRecord("r"), budget.records))
        assertEquals(ChunkPhase.STAGE, manifest.phase)
        assertEquals(100_000, manifest.recordIds.size)
        val file = File(root, "${manifest.generation}/manifest.json")
        assertTrue(file.length() <= budget.manifestBytes)
        assertTrue(file.length() <= manifestEncodedBytes(manifest))
        assertEquals(manifest, ChunkOutbox(root).load())
    }

    @Test fun largestAdmittedEscapedGenerationIsBoundedOnDisk() {
        val recordId = "\u0000\"\\/😀".repeat(5000)
        val record = ChunkRecord(recordId, "{\"recordId\":" + JSONObject.quote(recordId) + "}")
        val refused = stage(File(temporary.newFolder(), "outbox"), input(record, 1000))
        val root = File(temporary.newFolder(), "outbox")
        val admitted = stage(root, input(record, refused.recordIds.size))
        assertEquals(ChunkPhase.STAGE, admitted.phase)
        val bytes = File(root, "${admitted.generation}/manifest.json").length()
        assertTrue(bytes > 0)
        assertTrue(bytes <= FreshGenerationLimits().manifestBytes)
        assertEquals(admitted, ChunkOutbox(root).load())
    }

    @Test fun cumulativeQuotaRefusesOffendingBodyAndPreservesPriorBytes() {
        val root = File(temporary.newFolder(), "outbox")
        root.mkdirs()
        val older = File(root, "old-evidence").apply { writeText("retain") }
        val record = syntheticRecord("r")
        val body = ChunkPartitioner.envelope("budget-1", listOf(record))
        val manifest = stage(root, input(record, 3), FreshGenerationLimits(queueBytes = older.length() + body.size), count = 1)
        assertEquals(ChunkPhase.BLOCKED, manifest.phase)
        assertEquals("retain", older.readText())
        assertEquals(ChunkReason.OUTBOX_QUOTA, manifest.reason)
        assertFalse(File(root, manifest.generation).exists())
        assertFalse(File(root, "${manifest.generation}/child-1.json").exists())
        assertFalse(File(root, "active").exists())
        assertFalse(File(root, "${manifest.generation}/manifest.json").exists())
    }

    @Test fun syncBudgetRefusalNeverSendsOrCommits() {
        val root = File(temporary.newFolder(), "outbox")
        val cursors = FakeChunkCursors()
        var id = 0
        val recordId = "\u0000".repeat(50_000)
        val record = ChunkRecord(recordId, "{\"recordId\":" + JSONObject.quote(recordId) + "}")
        val status = runBlocking {
            ChunkSync(ChunkOutbox(root), cursors, { _, _ -> error("No send") }, newId = { "sync-${id++}" })
                .run(true, "synthetic", syntheticMapping) { input(record, 1000) }
        }
        assertEquals(ChunkPhase.BLOCKED, status.phase)
        assertEquals(0, cursors.commits)
        assertFalse(File(root, "active").exists())
        assertFalse(root.walkTopDown().any { it.name == "child-0.json" })
    }
}
