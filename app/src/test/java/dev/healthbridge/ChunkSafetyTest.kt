package dev.healthbridge

import org.junit.Assert.*
import org.junit.Test

class ChunkSafetyTest {
    private val record = ChunkRecord("r", "{\"recordId\":\"r\"}")
    private val minimalMapping = VerifiedReceiverMapping { "{}" }

    @Test fun mappedArrayCountsJsonOnce() {
        val mapping = VerifiedReceiverMapping { "{\"id\":\"r\"}" }
        val plan = ChunkPartitioner.partition(listOf(record), listOf("b"), mapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(12L, plan.children.single().mappedBytes)
    }

    @Test fun mappedEscapesHaveLiteralExpectedUtf8Size() {
        val mapping = VerifiedReceiverMapping { """{"v":"\"\\\n\u0001é中😀"}""" }
        val plan = ChunkPartitioner.partition(listOf(record), listOf("b"), mapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(31L, plan.children.single().mappedBytes)
    }

    @Test fun mappedArrayEmptyIsTwoBytes() {
        val plan = ChunkPartitioner.partition(emptyList(), listOf("b"), minimalMapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(2L, plan.children.single().mappedBytes)
    }

    @Test fun wireDefaultCanBeFilledExactlyBelowSuppliedOneMiBThreshold() {
        val empty = ChunkRecord("r", "{\"recordId\":\"r\",\"value\":\"\"}")
        val overhead = ChunkPartitioner.envelope("b", listOf(empty)).size
        val full = ChunkRecord("r", "{\"recordId\":\"r\",\"value\":\"" + "x".repeat(524288 - overhead) + "\"}")
        val plan = ChunkPartitioner.partition(listOf(full), listOf("b"), minimalMapping, ChunkLimits()) as ChunkPlan.Ready
        val measured = plan.children.single().body.size
        assertEquals(524288, measured)
        assertEquals(524288, 1048576 - measured)
        println("WIRE measured=$measured suppliedThreshold=1048576 margin=${1048576 - measured}")
    }

    @Test fun mappedDefaultCanBeFilledExactlyWithIndependentSmallWireBody() {
        val mapping = VerifiedReceiverMapping { "{\"v\":\"" + "x".repeat(899990) + "\"}" }
        val records = listOf(record, ChunkRecord("s", "{\"recordId\":\"s\"}"))
        val plan = ChunkPartitioner.partition(records, listOf("b", "c"), mapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(listOf(1, 1), plan.children.map { it.records.size })
        assertEquals(listOf(900000L, 900000L), plan.children.map { it.mappedBytes })
        assertTrue(plan.children.all { it.body.size < 100 })
        println("MAPPED measured=900000 suppliedThreshold=1900000 margin=1000000 mapping=SYNTHETIC")
    }

    @Test fun countDefaultForcesSplitWithIndependentSmallWireAndMappedSizes() {
        val records = List(5001) { ChunkRecord("r$it", "{\"recordId\":\"r$it\"}") }
        val ids = List(5001) { "b$it" }
        val plan = ChunkPartitioner.partition(records, ids, minimalMapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(listOf(5000, 1), plan.children.map { it.records.size })
        assertTrue(plan.children.all { it.body.size < 524288 && it.mappedBytes < 900000 })
        println("COUNT measured=5000 suppliedThreshold=10000 margin=5000")
    }

    @Test fun eachUtf8ScalarFitsAtExactWireLimitAndBlocksOneByteBelow() {
        listOf("a", "é", "中", "😀").forEach { scalar ->
            val item = ChunkRecord("r", "{\"recordId\":\"r\",\"v\":\"$scalar\"}")
            val bytes = ChunkPartitioner.envelope("b", listOf(item)).size.toLong()
            val fits = ChunkPartitioner.partition(listOf(item), listOf("b"), minimalMapping, ChunkLimits(wireBytes = bytes))
            val blocked = ChunkPartitioner.partition(listOf(item), listOf("b"), minimalMapping, ChunkLimits(wireBytes = bytes - 1))
            assertTrue(fits is ChunkPlan.Ready)
            assertEquals(bytes, (blocked as ChunkPlan.Blocked).wireBytes)
        }
    }
    @Test fun fullMappedPayloadCanFillDefaultStorageBudgetWhileWireStillFits() {
        val mapping = ProductionReceiverMapping()
        fun item(id: String, slashes: Int, padding: Int): ChunkRecord = ChunkRecord(id,
            "{\"type\":\"StepsRecord\",\"source\":{\"package\":\"p\"},\"recordId\":\"$id\",\"lastModifiedTime\":\"2026-11-13T03:00:00.000Z\",\"values\":{\"note\":\"" +
                "\\\\".repeat(slashes) + "x".repeat(padding) + "\"}}")
        val baseBytes = mapping.mappedRecordJson(item("r", 0, 0)).toByteArray(Charsets.UTF_8).size + 2
        val slashes = (900000 - baseBytes) / 4
        val padding = (900000 - baseBytes) % 4
        val records = listOf(item("r", slashes, padding), item("s", slashes, padding))
        val plan = ChunkPartitioner.partition(records, listOf("b", "c"), mapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(listOf(1, 1), plan.children.map { it.records.size })
        assertEquals(listOf(900000L, 900000L), plan.children.map { it.mappedBytes })
        assertTrue(plan.children.all { it.body.size <= 524288 })
        assertEquals(records, plan.children.flatMap { it.records })
        println("FULL_MAPPING measured=900000 suppliedThreshold=1900000 margin=1000000 wireBytes=${plan.children.first().body.size} timestampKey=RECEIVER_NANOSECOND_PADDING")
    }

}
