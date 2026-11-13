package dev.healthbridge

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SyncStatusDisplayTest {
    private fun snapshot(enabled: Boolean = true, result: String = "Ready") =
        SyncStatusDisplay.Snapshot(enabled, true, true, result)

    @Test fun offOverridesStaleHealthSuccess() {
        val display = SyncStatusDisplay.format(snapshot(false, "Sync delivered: HTTP 204"))
        assertEquals(SyncStatusDisplay.State.OFF, display.state)
        assertEquals("Automatic sync is off. Retained data is kept.", display.reason)
    }

    @Test fun onWaitsAndRecordedPauseIsDistinct() {
        assertEquals(SyncStatusDisplay.State.WAITING, SyncStatusDisplay.format(snapshot()).state)
        val paused = SyncStatusDisplay.format(snapshot(result = "Health sync PAUSED: containment stage"))
        assertEquals(SyncStatusDisplay.State.PAUSED, paused.state)
        assertEquals("Health sync is paused during containment.", paused.reason)
    }

    @Test fun oldSuccessAndUnknownResultsCannotClaimDelivery() {
        listOf("Sync delivered: HTTP 204", "connected", "Success", "unrecognized").forEach {
            assertEquals(SyncStatusDisplay.State.UNKNOWN,
                SyncStatusDisplay.format(snapshot(result = it).copy(lastSuccess = "2026-01-01T00:00:00Z")).state)
            assertEquals("No recorded empty test acknowledgement.", SyncStatusDisplay.testResult(it))
        }
    }

    @Test fun onlyExactRecordedTestAcceptanceAcknowledges() {
        assertEquals("Empty test acknowledged by the server. ACCEPTED HTTP 204; one shot, no automatic retry. This does not confirm health delivery.",
            SyncStatusDisplay.testResult("Empty webhook test: ACCEPTED HTTP 204; one shot, no automatic retry"))
        listOf("Empty webhook test delivered: HTTP 204", "Empty webhook test: ACCEPTED HTTP 400; one shot, no automatic retry",
            "Empty webhook test: ACCEPTED HTTP 204").forEach {
            assertEquals("No recorded empty test acknowledgement.", SyncStatusDisplay.testResult(it))
        }
    }

    @Test fun unknownAcceptanceRemainsBlocked() {
        val result = "BLOCKED: empty Test acceptance unknown; no automatic retry"
        assertEquals("Blocked. Empty test acceptance is unknown. No automatic retry will run.", SyncStatusDisplay.testResult(result))
        assertEquals(SyncStatusDisplay.State.BLOCKED, SyncStatusDisplay.format(snapshot(result = result)).state)
    }

    @Test fun retryAndRejectionDoNotClaimAcceptance() {
        assertEquals("Empty test recorded. RETRY HTTP 503; one shot, no automatic retry. No acceptance is confirmed.",
            SyncStatusDisplay.testResult("Empty webhook test: RETRY HTTP 503; one shot, no automatic retry"))
        assertEquals("Empty test recorded. BLOCKED HTTP 401; one shot, no automatic retry. No acceptance is confirmed.",
            SyncStatusDisplay.testResult("Empty webhook test: BLOCKED HTTP 401; one shot, no automatic retry"))
        assertEquals("Empty test was not sent. No automatic retry will run.",
            SyncStatusDisplay.testResult("Empty webhook test: RETRY; request not sent; no automatic retry"))
    }

    @Test fun missingSetupAndAccessAreExplicit() {
        assertEquals("Save an HTTPS endpoint and a token to use Send test.",
            SyncStatusDisplay.format(snapshot().copy(configured = false)).action)
        assertEquals("Health read access is missing. Health delivery remains paused.",
            SyncStatusDisplay.format(snapshot().copy(permissions = false)).reason)
    }

    @Test fun progressHasNoInventedCounts() {
        assertEquals("Progress is ready. No health delivery progress is available during containment.", SyncStatusDisplay.progress())
    }

    @Test fun timestampsDescribeOnlyStoredTime() {
        assertEquals("Never recorded", SyncStatusDisplay.lastSuccess("Never", Instant.EPOCH, ZoneId.of("UTC")))
        assertEquals("01 Jan 2026, 00:00:00 UTC\n1 hour ago", SyncStatusDisplay.lastSuccess("2026-01-01T00:00:00Z",
            Instant.parse("2026-01-01T01:00:00Z"), ZoneId.of("UTC")))
    }

    @Test fun secretNeverEntersDisplayAndHostOmitsPath() {
        assertEquals("", SyncStatusDisplay.tokenFieldValue())
        assertEquals("Token saved. Leave blank to keep it.", SyncStatusDisplay.tokenIndicator(true))
        assertEquals("example.invalid", SyncStatusDisplay.host("https://example.invalid/private/path"))
    }
}
