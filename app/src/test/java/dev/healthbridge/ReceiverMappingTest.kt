package dev.healthbridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverMappingTest {
    private val fixtures get() = JSONObject(checkNotNull(javaClass.getResource("/receiver-mapping-fixtures.json")).readText())
    private val productionMapping = ProductionReceiverMapping()

    @Test fun fullRecordMappingMatchesIndependentNodeOracle() {
        val cases = fixtures.getJSONArray("fixtures")
        repeat(cases.length()) { index ->
            val fixture = cases.getJSONObject(index)
            val record = ChunkRecord(fixture.getString("id"), fixture.getString("input"))
            assertEquals(fixture.getString("mapped"), productionMapping.mappedRecordJson(record))
        }
    }
    @Test fun fullReserializedPayloadAndEscapedArrayBytesMatchNodeOracle() {
        val all = fixtures
        val cases = all.getJSONArray("fixtures")
        val records = List(cases.length()) { i -> cases.getJSONObject(i).let { ChunkRecord(it.getString("id"), it.getString("input")) } }
        val expected = all.getString("array")
        assertEquals(expected, records.joinToString(",", "[", "]") { productionMapping.mappedRecordJson(it) })
        val plan = ChunkPartitioner.partition(records, List(records.size) { "b$it" }, productionMapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(all.getLong("arrayBytes"), plan.children.single().mappedBytes)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), plan.children.single().mappedBytes)
        records.forEachIndexed { i, record ->
            val single = ChunkPartitioner.partition(listOf(record), listOf("b"), productionMapping, ChunkLimits()) as ChunkPlan.Ready
            assertEquals(cases.getJSONObject(i).getLong("mappedArrayBytes"), single.children.single().mappedBytes)
        }
    }
    @Test fun mappingUsesAllFiveReceiverFieldsAndSuppliedNormalization() {
        val record = ChunkRecord("r", "{\"type\":\"StepsRecord\",\"source\":{\"package\":\"synthetic\"},\"recordId\":\"r\",\"lastModifiedTime\":\"input-time\",\"values\":{\"count\":7}}")
        val mapped = ProductionReceiverMapping { value -> assertEquals("input-time", value); "synthetic-key" }.mappedRecordJson(record)
        assertEquals("{\"type\":\"StepsRecord\",\"source\":\"synthetic\",\"id\":\"r\",\"modified\":\"synthetic-key\",\"payload\":\"{\\\"type\\\":\\\"StepsRecord\\\",\\\"source\\\":{\\\"package\\\":\\\"synthetic\\\"},\\\"recordId\\\":\\\"r\\\",\\\"lastModifiedTime\\\":\\\"input-time\\\",\\\"values\\\":{\\\"count\\\":7}}\"}", mapped)
    }
    @Test fun recordStringsReserializeWithEcmaEscapesAndNumberRounding() {
        val parsed = EcmaJson.parse("{\"n\":9007199254740993,\"v\":1e23,\"zero\":-0,\"small\":1e-7,\"s\":\"a\\/b\\ud800\"}")
        assertEquals("{\"n\":9007199254740992,\"v\":1e+23,\"zero\":0,\"small\":1e-7,\"s\":\"a/b\\ud800\"}", EcmaJson.stringify(parsed))
    }
    @Test fun suppliedTimestampContractCreatesProductionMapping() {
        assertTrue(ChunkContracts.verifiedMapping is ProductionReceiverMapping)
    }
    @Test fun requiredReceiverFieldsFailClosedInsteadOfInventingMapping() {
        assertTrue(runCatching { productionMapping.mappedRecordJson(syntheticRecord("r")) }.isFailure)
    }
    @Test fun independentDefaultMarginsMatchSuppliedReceiverLimits() {
        val limits = ChunkLimits()
        assertEquals(524288L, 1048576L - limits.wireBytes)
        assertEquals(5000, 10000 - limits.count)
        assertEquals(1000000L, 1900000L - limits.mappedBytes)
    }
    @Test fun strictParsingRejectsJsonExtensionsAndDuplicateProperties() {
        listOf("{'x':1}", "{x:1}", "[1,]", "{\"x\":01}", "{\"x\":+1}", "{\"x\":1,}", "{\"x\":1,\"x\":2}", "null garbage", "\"raw\nline\"").forEach {
            assertTrue(it, runCatching { EcmaJson.parse(it) }.isFailure)
        }
    }
}
