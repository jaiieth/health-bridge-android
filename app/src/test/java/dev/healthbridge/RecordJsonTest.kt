package dev.healthbridge

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecordJsonTest {
    private val time = Instant.parse("2026-11-12T00:00:00Z")

    @Test
    fun instantRecordHasUtcTimesUnitsAndExplicitUnknownZone() {
        val record = RestingHeartRateRecord(time, null, 60, Metadata.manualEntryWithId("record-1"))
        val json = recordJson(record, "Source app")
        assertEquals("RestingHeartRateRecord", json.getString("type"))
        assertEquals("record-1", json.getString("recordId"))
        assertEquals("2026-11-12T00:00:00Z", json.getString("startTime"))
        assertEquals("2026-11-12T00:00:00Z", json.getString("endTime"))
        assertTrue(json.getJSONObject("timezone").isNull("startOffset"))
        assertEquals(60, json.getJSONObject("values").getInt("beatsPerMinute"))
        assertEquals("bpm", json.getJSONObject("values").getString("unit"))
        assertEquals("Source app", json.getJSONObject("source").getString("name"))
        assertTrue(json.getJSONObject("source").isNull("device"))
    }

    @Test
    fun stepsRetainOriginalOffsets() {
        val json =
            recordJson(
                StepsRecord(
                    time,
                    ZoneOffset.ofHours(7),
                    time.plusSeconds(3600),
                    ZoneOffset.ofHours(7),
                    1200,
                    Metadata.manualEntry(),
                ),
                null,
            )
        assertEquals("2026-11-12T01:00:00Z", json.getString("endTime"))
        assertEquals("+07:00", json.getJSONObject("timezone").getString("startOffset"))
        assertEquals(1200, json.getJSONObject("values").getInt("count"))
        assertEquals("count", json.getJSONObject("values").getString("unit"))
        assertTrue(json.getJSONObject("source").isNull("name"))
    }
}
