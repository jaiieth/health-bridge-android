package dev.healthbridge

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

internal fun syntheticRecord(id: String, value: String = "x") =
    ChunkRecord(id, "{\"recordId\":" + EcmaJson.quote(id) + ",\"value\":" + EcmaJson.quote(value) + ",\"samples\":[1,2,3]}")
internal val syntheticMapping = VerifiedReceiverMapping { "{\"id\":" + EcmaJson.quote(it.recordId) + "}" }

class ChunkPartitionerTest {
    private fun plan(records: List<ChunkRecord>, limits: ChunkLimits = ChunkLimits(), ids: List<String> = List(records.size.coerceAtLeast(1)) { "b$it" }): ChunkPlan =
        ChunkPartitioner.partition(records, ids, syntheticMapping, limits)
    private fun ready(records: List<ChunkRecord>, limits: ChunkLimits = ChunkLimits()) = (plan(records, limits) as ChunkPlan.Ready).children

    @Test fun exactEmptyWireEnvelope() {
        assertEquals("{\"schemaVersion\":1,\"batchId\":\"b0\",\"records\":[]}", ready(emptyList()).single().body.toString(Charsets.UTF_8))
        assertEquals(2L, ready(emptyList()).single().mappedBytes)
    }
    @Test fun exactLiteralMappedEscaping() {
        val record = ChunkRecord("r", "{\"recordId\":\"r\"}")
        val child = ready(listOf(record)).single()
        assertEquals("\"[{\\\"id\\\":\\\"r\\\"}]\"", EcmaJson.quote("[{\"id\":\"r\"}]"))
        assertEquals(12L, child.mappedBytes)
    }
    @Test fun utf8OneTwoThreeFourByteScalars() {
        assertEquals(1, "a".toByteArray(Charsets.UTF_8).size)
        assertEquals(2, "é".toByteArray(Charsets.UTF_8).size)
        assertEquals(3, "中".toByteArray(Charsets.UTF_8).size)
        assertEquals(4, "😀".toByteArray(Charsets.UTF_8).size)
        val children = ready(listOf(syntheticRecord("r", "aé中😀")))
        assertTrue(children.single().body.toString(Charsets.UTF_8).contains("aé中😀"))
    }
    @Test fun surrogatePairStaysFourBytes() {
        assertEquals("\"😀\"", EcmaJson.quote("\ud83d\ude00"))
        assertEquals(6, EcmaJson.quote("\ud83d\ude00").toByteArray(Charsets.UTF_8).size)
    }
    @Test fun loneHighSurrogateEscapes() { assertEquals("\"\\ud83d\"", EcmaJson.quote("\ud83d")) }
    @Test fun loneLowSurrogateEscapes() { assertEquals("\"\\ude00\"", EcmaJson.quote("\ude00")) }
    @Test fun adjacentLoneAndPairedSurrogates() {
        assertEquals("\"\\ud83d😀\\ude00\"", EcmaJson.quote("\ud83d\ud83d\ude00\ude00"))
    }
    @Test fun shortControlEscapesMatchEcmaLiteral() {
        assertEquals("\"\\b\\f\\n\\r\\t\"", EcmaJson.quote("\b\u000c\n\r\t"))
    }
    @Test fun otherControlsEscapeAsSixAsciiBytes() {
        assertEquals("\"\\u0000\\u0001\\u001f\"", EcmaJson.quote("\u0000\u0001\u001f"))
    }
    @Test fun quotesBackslashesAndSlash() {
        assertEquals("\"\\\"\\\\/\"", EcmaJson.quote("\"\\/"))
    }
    @Test fun ecmaDoesNotEscapeLineSeparators() { assertEquals("\"\u2028\u2029\"", EcmaJson.quote("\u2028\u2029")) }
    @Test fun countCapActsIndependently() {
        val chunks = ready((1..5).map { syntheticRecord("r$it") }, ChunkLimits(count = 2))
        assertEquals(listOf(2, 2, 1), chunks.map { it.records.size })
    }
    @Test fun mappedCapActsIndependently() {
        val chunks = ready(listOf(syntheticRecord("r"), syntheticRecord("s")), ChunkLimits(mappedBytes = 12))
        assertEquals(listOf(1, 1), chunks.map { it.records.size })
        assertEquals(listOf(12L, 12L), chunks.map { it.mappedBytes })
    }
    @Test fun wireCapActsIndependentlyAndIncludesComma() {
        val records = listOf(syntheticRecord("r"), syntheticRecord("s"))
        val limit = ChunkPartitioner.envelope("b0", records).size.toLong() - 1
        assertEquals(listOf(1, 1), ready(records, ChunkLimits(wireBytes = limit)).map { it.records.size })
    }
    @Test fun exactWireBoundaryFitsAndOneLessBlocks() {
        val records = listOf(syntheticRecord("r", "é中😀"))
        val size = ChunkPartitioner.envelope("b0", records).size.toLong()
        assertEquals(size, ready(records, ChunkLimits(wireBytes = size)).single().body.size.toLong())
        val blocked = plan(records, ChunkLimits(wireBytes = size - 1)) as ChunkPlan.Blocked
        assertEquals("r", blocked.recordId)
        assertEquals(size, blocked.wireBytes)
    }
    @Test fun mappedOversizedReportsMeasuredSizes() {
        val blocked = plan(listOf(syntheticRecord("r")), ChunkLimits(mappedBytes = 11)) as ChunkPlan.Blocked
        assertEquals("r", blocked.recordId)
        assertEquals(12L, blocked.mappedBytes)
        assertEquals(1, blocked.count)
    }
    @Test fun longEscapedChildIdCountsInWireEnvelope() {
        val records = listOf(syntheticRecord("r"))
        val id = "long\"\\😀".repeat(200)
        val result = plan(records, ChunkLimits(wireBytes = 200), listOf(id)) as ChunkPlan.Blocked
        assertEquals(ChunkPartitioner.envelope(id, records).size.toLong(), result.wireBytes)
    }
    @Test fun tooSmallEmptyEnvelopeBlocks() {
        val blocked = plan(emptyList(), ChunkLimits(wireBytes = 1)) as ChunkPlan.Blocked
        assertNull(blocked.recordId)
        assertEquals(0, blocked.count)
    }
    @Test fun indivisibleNestedSamplesRemainUnchanged() {
        val input = listOf(syntheticRecord("r", "quote\" slash\\ newline\n"), syntheticRecord("s", "中😀"))
        val children = ready(input, ChunkLimits(count = 1))
        assertEquals(input, children.flatMap { it.records })
        children.forEachIndexed { i, child -> assertArrayEquals(ChunkPartitioner.envelope("b$i", listOf(input[i])), child.body) }
    }
    @Test fun lateOversizedRecordDiscardsEntirePlan() {
        val result = plan(listOf(syntheticRecord("small"), syntheticRecord("huge", "x".repeat(1000))), ChunkLimits(wireBytes = 200, count = 1))
        assertTrue(result is ChunkPlan.Blocked)
        assertEquals("huge", (result as ChunkPlan.Blocked).recordId)
    }
    @Test fun fixedSeedConservationOrderDeterminismAndAllCaps() {
        val random = Random(739)
        repeat(50) {
            val records = List(random.nextInt(1, 80)) { index -> syntheticRecord("r$index", List(random.nextInt(1, 30)) { listOf("a", "é", "中", "😀", "\n", "\\", "\"")[random.nextInt(7)] }.joinToString("")) }
            val limits = ChunkLimits(wireBytes = 600, count = 4, mappedBytes = 90)
            val first = ready(records, limits)
            val second = ready(records, limits)
            assertEquals(records, first.flatMap { it.records })
            assertEquals(first.map { it.batchId }, second.map { it.batchId })
            first.zip(second).forEach { (a, b) -> assertArrayEquals(a.body, b.body) }
            first.forEach { assertTrue(it.body.size <= 600 && it.records.size <= 4 && it.mappedBytes <= 90) }
        }
    }
    @Test fun actualDefaultsStayWithinLocalBounds() {
        val records = List(5001) { syntheticRecord("r$it") }
        val chunks = ready(records)
        assertEquals(5001, chunks.sumOf { it.records.size })
        assertTrue(chunks.all { it.body.size <= 524288 && it.records.size <= 5000 && it.mappedBytes <= 900000 })
    }
    @Test fun childSpecificIdOverheadIsRecomputed() {
        val records = listOf(syntheticRecord("r"), syntheticRecord("s"))
        val blocked = plan(records, ChunkLimits(wireBytes = 150, count = 1), listOf("short", "x".repeat(300))) as ChunkPlan.Blocked
        assertEquals("s", blocked.recordId)
        assertTrue(blocked.wireBytes > 300)
    }
    @Test fun duplicatePreallocatedIdsRejected() {
        assertTrue(runCatching { plan(listOf(syntheticRecord("r"), syntheticRecord("s")), ids = listOf("same", "same")) }.isFailure)
    }
}
