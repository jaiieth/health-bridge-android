package dev.healthbridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.rules.TemporaryFolder

internal fun retainedInventory(root: File): Map<String, Pair<Long, String>> = root.walkTopDown()
    .filter { it.isFile }.associate { it.relativeTo(root).path to (it.length() to sha256(it.readBytes())) }

internal class DiskTestCursors(private val file: File, private val crashAfterCommit: Boolean = false) : ChunkCursorStore {
    override suspend fun hasLegacyPending() = false
    override suspend fun committed(targets: Map<String, String>) = file.isFile && file.readText() == JSONObject(targets).toString()
    override suspend fun commit(targets: Map<String, String>): Boolean {
        if (!committed(targets)) file.writeText(JSONObject(targets).toString())
        if (crashAfterCommit) throw ProcessDeath()
        return true
    }
}

@RunWith(Parameterized::class)
class AttemptBoundaryTest(private val boundary: String) {
    @get:Rule val temporary = TemporaryFolder()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun boundaries() = listOf(
            "B0_temp", "B0_fsync", "B0_rename", "B0", "B1_temp", "B1", "B2", "B3", "B4", "B5", "B6_manifest", "B6_referenced", "B7",
            "B8_first_ack", "B8_all_acks", "B8_before_commit", "B8_after_commit", "B8_done",
        ).map { arrayOf(it) }
    }

    @Test fun freshInstancesClassifyRetainAndResumeWithoutLosingBodiesOrDoubleSending() = runBlocking {
        val work = temporary.newFolder()
        val root = File(work, "outbox")
        val cursorFile = File(work, "test-cursors")
        val ledger = File(work, "test-transport-ledger")
        val input = FreshChunkInput(listOf(syntheticRecord("one"), syntheticRecord("two")),
            mapOf("StepsRecord" to "2026-11-01T00:00:00Z"))
        var ids = 0
        fun phase() = root.walkTopDown().firstOrNull { it.name == "manifest.json" }?.let { JSONObject(it.readText()) }
        val files = DurableFiles(fault = { at, name ->
            val kill = when (boundary) {
                "B0_temp" -> name == "reservation" && at == DurableBoundary.TEMP_WRITTEN
                "B0_fsync" -> name == "reservation" && at == DurableBoundary.FILE_SYNCED
                "B0_rename" -> name == "reservation" && at == DurableBoundary.RENAMED
                "B1_temp" -> name == ATTEMPT_INTENT_NAME && at == DurableBoundary.TEMP_WRITTEN
                "B8_done" -> name == "manifest.json" && at == DurableBoundary.DIRECTORY_SYNCED && phase()?.getString("phase") == "DONE"
                "B0" -> name == "reservation" && at == DurableBoundary.DIRECTORY_SYNCED
                "B1" -> name == ATTEMPT_INTENT_NAME && at == DurableBoundary.FILE_SYNCED
                "B2" -> name == ATTEMPT_INTENT_NAME && at == DurableBoundary.RENAMED
                "B3" -> name == ATTEMPT_INTENT_NAME && at == DurableBoundary.DIRECTORY_SYNCED
                "B4" -> name == "child-1.json" && at == DurableBoundary.TEMP_WRITTEN
                "B5" -> name == "child-1.json" && at == DurableBoundary.DIRECTORY_SYNCED
                "B6_manifest" -> name == "published.json" && at == DurableBoundary.DIRECTORY_SYNCED
                "B6_referenced" -> name == "active" && at == DurableBoundary.DIRECTORY_SYNCED
                "B8_first_ack", "B8_all_acks", "B8_before_commit" -> name == "manifest.json" && at == DurableBoundary.DIRECTORY_SYNCED && phase()?.let { m ->
                    when (boundary) {
                        "B8_first_ack" -> m.getString("phase") == "ACK" && !m.getJSONArray("children").getJSONObject(1).getBoolean("acked")
                        "B8_all_acks" -> m.getString("phase") == "ACK" && m.getJSONArray("children").getJSONObject(1).getBoolean("acked")
                        else -> m.getString("phase") == "COMMIT"
                    }
                } == true
                else -> false
            }
            if (kill) {
                if (boundary == "B4") root.walkTopDown().single { it.name == "child-1.json.tmp" }.writeText("partial")
                throw ProcessDeath()
            }
        })
        val send: suspend (String, ByteArray) -> ChunkResponse = { batch, body ->
            ledger.appendText("$batch ${sha256(body)}\n")
            if (boundary == "B7") throw ProcessDeath()
            ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
        }
        val initial = ChunkSync(ChunkOutbox(root, files), DiskTestCursors(cursorFile, boundary == "B8_after_commit"), send,
            newId = { "boundary-${ids++}" }, clock = { 1700 }, limits = ChunkLimits(count = 1))
        try { initial.run(true, "https://synthetic.invalid", syntheticMapping) { input }; fail("Boundary must be reached") }
        catch (_: ProcessDeath) { }
        val before = retainedInventory(root)
        val generation = root.listFiles()!!.single { it.isDirectory }
        assertEquals(boundary !in setOf("B0_temp", "B0_fsync"), File(generation, "reservation").isFile)
        assertEquals(boundary in setOf("B0_temp", "B0_fsync"), File(generation, "reservation.tmp").isFile)
        assertEquals(boundary !in setOf("B0_temp", "B0_fsync", "B0_rename", "B0", "B1_temp", "B1"), File(generation, ATTEMPT_INTENT_NAME).isFile)
        assertEquals(boundary in setOf("B1_temp", "B1"), File(generation, "$ATTEMPT_INTENT_NAME.tmp").isFile)
        if (boundary == "B4") assertEquals("partial", File(generation, "child-1.json.tmp").readText())
        val beforeSends = if (ledger.exists()) ledger.readLines().size else 0
        val expectedSends = when (boundary) { "B7", "B8_first_ack" -> 1; "B8_all_acks", "B8_before_commit", "B8_after_commit", "B8_done" -> 2; else -> 0 }
        assertEquals(expectedSends, beforeSends)
        val expectedPhase = when (boundary) {
            "B8_done" -> ChunkPhase.DONE
            "B6_referenced" -> ChunkPhase.STAGE
            "B7" -> ChunkPhase.SEND
            "B8_first_ack", "B8_all_acks" -> ChunkPhase.ACK
            "B8_before_commit", "B8_after_commit" -> ChunkPhase.COMMIT
            else -> null
        }
        repeat(3) {
            val restarted = ChunkOutbox(root, releaseFault = { _, _ -> error("No release authority at $boundary") })
            assertNull(restarted.recoverStaging())
            restarted.reclaimDone(DiskTestCursors(cursorFile))
            assertEquals(before, retainedInventory(root))
            assertEquals(expectedPhase, restarted.load()?.phase)
        }
        val originalPayloads = before.filterKeys { it.endsWith(".json") && (it.contains("child-") || it.endsWith(ATTEMPT_INTENT_NAME)) }
        if (expectedPhase != null && expectedPhase != ChunkPhase.DONE) {
            val restartSend: suspend (String, ByteArray) -> ChunkResponse = { batch, body ->
                ledger.appendText("$batch ${sha256(body)}\n")
                ChunkResponse(200, "{\"ok\":true,\"batchId\":\"$batch\"}")
            }
            fun runner() = ChunkSync(ChunkOutbox(root), DiskTestCursors(cursorFile), restartSend)
            val result = runner().run(true, "https://synthetic.invalid", syntheticMapping) { error("Must resume without recollection") }
            if (boundary == "B7") {
                assertEquals(ChunkReason.UNKNOWN_ACCEPTANCE, result.reason)
                repeat(2) {
                    assertEquals(ChunkPhase.PAUSED, runner().run(true, "https://synthetic.invalid", syntheticMapping) { error("No recollection") }.phase)
                }
                assertEquals(1, ledger.readLines().size)
                assertFalse(cursorFile.exists())
            } else {
                assertEquals(ChunkPhase.DONE, result.phase)
                assertTrue(DiskTestCursors(cursorFile).committed(input.targetCursors))
                assertEquals(2, ledger.readLines().size)
            }
            val sent = ledger.readLines()
            assertEquals(sent.map { it.substringBefore(' ') }.distinct().size, sent.size)
            originalPayloads.forEach { (name, value) -> assertEquals(value, retainedInventory(root)[name]) }
        } else if (expectedPhase == ChunkPhase.DONE) {
            assertEquals(ChunkPhase.DONE, ChunkOutbox(root).status()!!.phase)
            assertEquals(2, beforeSends)
            assertTrue(DiskTestCursors(cursorFile).committed(input.targetCursors))
        } else {
            assertEquals(0, beforeSends)
            assertFalse(cursorFile.exists())
        }
        println("BOUNDARY $boundary classification=Retain released=0 retained=${before.values.sumOf { it.first }} sends=$beforeSends resume=${expectedPhase ?: "unpublished-no-authority"}")
    }
}
