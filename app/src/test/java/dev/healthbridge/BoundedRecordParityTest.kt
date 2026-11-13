package dev.healthbridge

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoundedRecordParityTest {
    private val start = Instant.parse("2026-11-12T00:00:00Z")
    private val end = start.plusSeconds(3600)
    private val zone = ZoneOffset.ofHours(7)
    private val metadata = Metadata.manualEntryWithId("synthetic-\"\\中😀", Device(manufacturer = "synthetic", model = "test", type = Device.TYPE_PHONE))

    @Test fun allSixTypesKeepLegacyFieldsValuesAndEveryNestedSample() {
        val records = listOf(
            SleepSessionRecord(start, zone, end, zone, metadata, stages = listOf(
                SleepSessionRecord.Stage(start, start.plusSeconds(600), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(start.plusSeconds(600), end, SleepSessionRecord.STAGE_TYPE_REM),
            )),
            HeartRateVariabilityRmssdRecord(start, null, 42.125, metadata),
            RestingHeartRateRecord(start, zone, 60, metadata),
            HeartRateRecord(start, null, end, zone, listOf(
                HeartRateRecord.Sample(start.plusSeconds(1), 60),
                HeartRateRecord.Sample(start.plusSeconds(2), 72),
            ), metadata),
            StepsRecord(start, zone, end, null, 1200, metadata),
            ExerciseSessionRecord(start, null, end, null, metadata, ExerciseSessionRecord.EXERCISE_TYPE_WALKING),
        )
        val mapping = ProductionReceiverMapping()
        records.forEach { record ->
            val name = "Synthetic \"app\" \\ \n é中😀"
            val expected = recordJson(record, name)
            val actual = boundedRecordJson(record, name)
            assertEquals(metadata.id, actual.recordId)
            assertTrue(record::class.simpleName, expected.similar(JSONObject(actual.json)))
            val legacyMapped = EcmaJson.parse(mapping.mappedRecordJson(ChunkRecord(metadata.id, expected.toString()))) as Map<*, *>
            val boundedMapped = EcmaJson.parse(mapping.mappedRecordJson(actual)) as Map<*, *>
            assertEquals(legacyMapped - "payload", boundedMapped - "payload")
            assertEquals(EcmaJson.parse(legacyMapped["payload"] as String), EcmaJson.parse(boundedMapped["payload"] as String))
        }
    }

    @Test fun oversizedNestedSamplesRejectTheWholeRecordInsteadOfDroppingSamples() {
        val samples = List(20_000) { HeartRateRecord.Sample(start.plusNanos(it.toLong()), 60) }
        val record = HeartRateRecord(start, null, end, null, samples, metadata)
        val failure = assertThrows(OversizedFreshRecord::class.java) { boundedRecordJson(record, null) }
        assertEquals(metadata.id, failure.recordId)
        assertTrue(failure.wireBytes > FRESH_RECORD_BYTES)
        assertEquals(20_000, record.samples.size)
    }

    @Test fun nullSourceAndDeviceKeepExplicitNullFields() {
        val record = StepsRecord(start, null, end, null, 1, Metadata.manualEntryWithId("synthetic-null"))
        val json = JSONObject(boundedRecordJson(record, null).json)
        assertTrue(json.getJSONObject("source").isNull("name"))
        assertTrue(json.getJSONObject("source").isNull("device"))
        assertTrue(recordJson(record, null).similar(json))
    }
}
