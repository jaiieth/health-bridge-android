package dev.healthbridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiverTimestampTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun invalid(value: Any?) {
        try { receiverTimestampKey(value); fail("Expected INVALID_TIMESTAMP") } catch (_: InvalidRecordTimestamp) {}
    }
    private fun record(timestamp: Any?) = ChunkRecord("r", "{\"type\":\"StepsRecord\",\"source\":{\"package\":\"synthetic\"},\"recordId\":\"r\",\"lastModifiedTime\":" + EcmaJson.stringify(timestamp) + ",\"values\":{\"note\":\"中😀\\n\\\"\\\\\"}}")

    @Test fun noFractionPadsNineZeros() {
        assertEquals("2024-02-29T12:34:56.000000000Z", receiverTimestampKey("2024-02-29T12:34:56Z"))
    }
    @Test fun oneDigitPadsEightZeros() {
        assertEquals("2024-02-29T12:34:56.100000000Z", receiverTimestampKey("2024-02-29T12:34:56.1Z"))
    }
    @Test fun threeDigitsPadSixZeros() {
        assertEquals("2024-02-29T12:34:56.123000000Z", receiverTimestampKey("2024-02-29T12:34:56.123Z"))
    }
    @Test fun sixDigitsPadThreeZeros() {
        assertEquals("2024-02-29T12:34:56.123456000Z", receiverTimestampKey("2024-02-29T12:34:56.123456Z"))
    }
    @Test fun nineDigitsStayUnchanged() {
        assertEquals("2024-02-29T12:34:56.123456789Z", receiverTimestampKey("2024-02-29T12:34:56.123456789Z"))
    }
    @Test fun invalidMonthsAreBlocked() {
        invalid("2024-00-01T00:00:00Z")
        invalid("2024-13-01T00:00:00Z")
    }
    @Test fun invalidDaysAndReceiverAcceptedRolloversAreBlocked() {
        listOf("2024-02-30T00:00:00Z", "2023-02-29T00:00:00Z", "2024-04-31T00:00:00Z", "2024-01-32T00:00:00Z", "2024-01-00T00:00:00Z").forEach(::invalid)
    }
    @Test fun hour24IsConservativelyBlocked() {
        invalid("2024-01-01T24:00:00Z")
        invalid("2024-01-01T24:00:00.000000000Z")
        invalid("2024-01-01T24:00:00.000000001Z")
    }
    @Test fun hour25IsBlocked() { invalid("2024-01-01T25:00:00Z") }
    @Test fun invalidMinutesAndLeapSecondsAreBlocked() {
        invalid("2024-01-01T23:60:00Z")
        invalid("2024-01-01T23:59:60Z")
    }
    @Test fun offsetsAreBlocked() { invalid("2024-01-01T00:00:00+07:00") }
    @Test fun lowercaseZIsBlocked() { invalid("2024-01-01T00:00:00z") }
    @Test fun tenFractionDigitsAreBlocked() { invalid("2024-01-01T00:00:00.1234567890Z") }
    @Test fun missingZAndEmptyFractionAreBlocked() {
        invalid("2024-01-01T00:00:00")
        invalid("2024-01-01T00:00:00.Z")
    }
    @Test fun nonStringInputIsBlocked() { listOf(null, 0, true, JSONObject()).forEach(::invalid) }
    @Test fun emptyStringIsBlocked() { invalid("") }
    @Test fun yearZeroAndFourDigitUpperYearAreAccepted() {
        assertEquals("0000-02-29T00:00:00.000000000Z", receiverTimestampKey("0000-02-29T00:00:00Z"))
        assertEquals("9999-12-31T23:59:59.999999999Z", receiverTimestampKey("9999-12-31T23:59:59.999999999Z"))
    }
    @Test fun matchesLocalNodeContractOracleExceptDocumentedBlockedRollovers() {
        val cases = JSONObject(checkNotNull(javaClass.getResource("/receiver-timestamp-fixtures.json")).readText()).getJSONArray("fixtures")
        assertEquals(29, cases.length())
        var blockedRollovers = 0
        repeat(cases.length()) { i ->
            val fixture = cases.getJSONObject(i)
            val input = fixture.opt("input").let { if (it == JSONObject.NULL) null else it }
            if (fixture.getBoolean("receiverAccepts") && !fixture.getBoolean("kotlinBlocksRollover")) {
                assertEquals(fixture.getString("normalized"), receiverTimestampKey(input))
            } else {
                invalid(input)
                if (fixture.getBoolean("kotlinBlocksRollover")) blockedRollovers++
            }
        }
        assertEquals(5, blockedRollovers)
    }
    @Test fun paddingIncreasesMappedBytesByEightForOneDigitInput() {
        val item = record("2024-02-29T12:34:56.1Z")
        val mapped = ChunkContracts.verifiedMapping.mappedRecordJson(item)
        val unpaddedCounterexample = JSONObject(mapped).put("modified", "2024-02-29T12:34:56.1Z").toString()
        val actualBytes = ("[" + mapped + "]").toByteArray(Charsets.UTF_8).size
        val incorrectBytes = ("[" + unpaddedCounterexample + "]").toByteArray(Charsets.UTF_8).size
        assertEquals(8, actualBytes - incorrectBytes)
        val plan = ChunkPartitioner.partition(listOf(item), listOf("b"), ChunkContracts.verifiedMapping, ChunkLimits()) as ChunkPlan.Ready
        assertEquals(actualBytes.toLong(), plan.children.single().mappedBytes)
    }
    @Test fun normalizedModifiedDoesNotRewriteWireOrPayloadTimestamp() {
        val item = record("2024-02-29T12:34:56.1Z")
        val plan = ChunkPartitioner.partition(listOf(item), listOf("b"), ChunkContracts.verifiedMapping, ChunkLimits()) as ChunkPlan.Ready
        assertArrayEquals(("{\"schemaVersion\":1,\"batchId\":\"b\",\"records\":[" + item.json + "]}").toByteArray(Charsets.UTF_8), plan.children.single().body)
        val mapped = JSONObject(ChunkContracts.verifiedMapping.mappedRecordJson(item))
        assertEquals("2024-02-29T12:34:56.100000000Z", mapped.getString("modified"))
        assertEquals("2024-02-29T12:34:56.1Z", JSONObject(mapped.getString("payload")).getString("lastModifiedTime"))
    }
    @Test fun rolloverAndInvalidTypesDurablyBlockWithoutStagingAnyChildBody() {
        listOf("2024-02-30T00:00:00Z", "2024-01-01T24:00:00Z", "2024-01-32T00:00:00Z", "2024-01-01T25:00:00Z", 42).forEach { timestamp ->
            val h = ChunkHarness(File(temporary.newFolder(), "outbox"))
            h.input = h.input.copy(records = listOf(record(timestamp)))
            val status = h.run(mapping = ChunkContracts.verifiedMapping)
            assertEquals(ChunkPhase.BLOCKED, status.phase)
            assertEquals(ChunkReason.INVALID_TIMESTAMP, status.reason)
            assertTrue(h.sent.isEmpty())
            assertEquals(0, h.cursors.commits)
            assertEquals(mapOf("steps" to "old", "sleep" to "old"), h.cursors.values)
            assertTrue(h.root.walkTopDown().none { it.name.startsWith("child-") })
            val manifest = checkNotNull(h.outbox.load())
            assertEquals(ChunkReason.INVALID_TIMESTAMP, manifest.reason)
            assertEquals("r", manifest.blockedRecordId)
            assertNull(manifest.blockedMappedBytes)
            h.run(mapping = ChunkContracts.verifiedMapping)
            assertTrue(h.sent.isEmpty())
        }
    }
}
