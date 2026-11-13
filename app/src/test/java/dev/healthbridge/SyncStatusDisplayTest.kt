package dev.healthbridge

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SyncStatusDisplayTest {
    private fun snapshot(enabled: Boolean = true, result: String = "Ready") =
        SyncStatusDisplay.Snapshot(enabled, true, true, result, freshChunkingEnabled = true)

    @Test fun closedBuildGateIsDistinctFromSavedConsentAndRecordedResults() {
        listOf(false, true).forEach { consent ->
            listOf("Ready", "Health sync PLAN: 0/0; FLAG_OFF", "Health sync DONE: 1/1; NONE",
                "Health sync PAUSED: legacy Pending preserved; delivery blocked").forEach { result ->
                val display = SyncStatusDisplay.format(snapshot(consent, result).copy(freshChunkingEnabled = false))
                assertEquals("Build gated", display.state.label)
                assertEquals("Health sync unavailable in this build - resource safety pending", display.reason)
                assertTrue(display.action.contains(if (consent) "Consent is saved" else "Saved consent is OFF"))
                assertEquals(if (result == "Ready") null else "Recorded past result: ${SyncStatusDisplay.progress(result)}",
                    display.recordedOutcome)
            }
        }
    }

    @Test fun gatedRecordedOutcomesRemainHistoricalForEitherSavedConsent() {
        val outcomes = mapOf(
            "Health sync BLOCKED: 2/3; HTTP_400" to "The receiver rejected the request with HTTP 400.",
            "Health sync PAUSED: 2/3; UNKNOWN_ACCEPTANCE" to "Receiver acceptance is unknown. Check the receiver before any resend.",
            "Health sync PAUSED: 2/3; ENDPOINT_CHANGED" to "The endpoint differs from the retained batch destination.",
            "Health sync ACK: 2/3; NONE" to "1 of 3 chunks have recorded acknowledgements.",
            "Health sync DONE: 3/3; NONE" to "Health sync completion is recorded.",
            "Health sync PAUSED: containment stage" to "Health sync was paused during containment.",
        )
        for (consent in listOf(false, true)) {
            outcomes.forEach { (result, expected) ->
                val display = SyncStatusDisplay.format(snapshot(consent, result).copy(freshChunkingEnabled = false))
                assertEquals(SyncStatusDisplay.State.GATED, display.state)
                assertEquals(SyncStatusDisplay.buildUnavailableReason, display.reason)
                assertTrue(display.action.contains(if (consent) "Consent is saved" else "Saved consent is OFF"))
                val outcome = requireNotNull(display.recordedOutcome)
                assertTrue(outcome.startsWith("Recorded past result: "))
                assertTrue(outcome.contains(expected))
                if (result.contains("UNKNOWN_ACCEPTANCE")) assertTrue(outcome.contains("No automatic resend will run."))
            }
            for (result in listOf("Ready", "unrecognized", "Sync delivered: HTTP 204")) {
                val display = SyncStatusDisplay.format(snapshot(consent, result).copy(
                    freshChunkingEnabled = false, lastSuccess = "2026-01-01T00:00:00Z"))
                assertEquals(SyncStatusDisplay.State.GATED, display.state)
                assertNull(display.recordedOutcome)
                assertFalse(display.toString().contains("completion is recorded"))
            }
        }
    }

    @Test fun gatedControlsAllowOnlyConsentOffAndKeepConfiguredTestAvailable() {
        for (consent in listOf(false, true)) for (configured in listOf(false, true))
            for (permissions in listOf(false, true)) for (pending in listOf(false, true)) {
                val s = SyncStatusDisplay.Snapshot(consent, configured, permissions, pending = pending)
                assertEquals(SyncStatusDisplay.Controls(consent, false, configured), SyncStatusDisplay.controls(s))
                assertEquals(if (consent) "Saved health consent: ON (turn OFF)" else "Saved health consent: OFF",
                    SyncStatusDisplay.consentLabel(s))
                assertEquals(consent, s.enabled)
            }
    }

    @Test fun gateEnabledControlsRetainExistingRequirements() {
        for (consent in listOf(false, true)) for (configured in listOf(false, true))
            for (permissions in listOf(false, true)) {
                val s = snapshot(consent).copy(configured = configured, permissions = permissions)
                assertEquals(SyncStatusDisplay.Controls(true, consent && configured && permissions, configured),
                    SyncStatusDisplay.controls(s))
                assertEquals(if (consent) "Sync ON" else "Sync OFF", SyncStatusDisplay.consentLabel(s))
            }
    }

    @Test fun recordedChunkStatusesAreRecognized() {
        data class Case(val phase: String, val reason: String, val state: SyncStatusDisplay.State, val label: String)
        val cases = listOf(
            Case("PLAN", "FLAG_OFF", SyncStatusDisplay.State.OFF, "OFF"),
            Case("DONE", "NONE", SyncStatusDisplay.State.DONE, "Acknowledged"),
            Case("PAUSED", "IO_FAILURE", SyncStatusDisplay.State.IO_FAILURE, "Storage failure"),
        ) + listOf("LEGACY_PENDING", "HTTP_400", "HTTP_409", "HTTP_413", "HTTP_BLOCKED", "OVERSIZED", "INVALID_TIMESTAMP").map {
            Case("BLOCKED", it, SyncStatusDisplay.State.BLOCKED, "Blocked")
        } + listOf("CORRUPT_OUTBOX", "UNKNOWN_ACCEPTANCE", "ENDPOINT_CHANGED", "HTTP_RETRY", "INVALID_ACK").map {
            Case("PAUSED", it, SyncStatusDisplay.State.PAUSED, "Paused")
        } + listOf("PLAN", "STAGE", "SEND", "ACK", "COMMIT").map {
            Case(it, "NONE", SyncStatusDisplay.State.PARTIAL, "In progress / partial")
        }
        cases.forEach { c ->
            val display = SyncStatusDisplay.format(snapshot(result = "Health sync ${c.phase}: 1/1; ${c.reason}"))
            assertEquals(c.toString(), c.state, display.state)
            assertEquals(c.toString(), c.label, display.state.label)
            if (c.phase in listOf("PAUSED", "BLOCKED")) assertTrue(display.action.contains("No automatic resend"))
        }
        assertEquals(ChunkPhase.entries.map { it.name }.toSet(), cases.map { it.phase }.toSet())
        assertEquals(ChunkReason.entries.map { it.name }.toSet(), cases.map { it.reason }.toSet())
    }

    @Test fun progressUsesFirstUnacknowledgedIndexAndPreservesFinalUncertainty() {
        assertEquals("0 of 0 chunks are recorded. Health sync is incomplete. Health delivery was disabled by consent or the fresh-chunking gate. No automatic resend will run.",
            SyncStatusDisplay.progress("Health sync PLAN: 0/0; FLAG_OFF"))
        assertEquals("1 of 1 chunks have recorded acknowledgements. Health sync completion is recorded.",
            SyncStatusDisplay.progress("Health sync DONE: 1/1; NONE"))
        assertEquals("0 of 0 chunks have recorded acknowledgements. Health sync completion is recorded.",
            SyncStatusDisplay.progress("Health sync DONE: 0/0; NONE"))
        assertEquals("1 of 3 chunks have recorded acknowledgements. 2 chunks are unacknowledged. Health sync is incomplete. Receiver acceptance is unknown. Check the receiver before any resend. No automatic resend will run.",
            SyncStatusDisplay.progress("Health sync PAUSED: 2/3; UNKNOWN_ACCEPTANCE"))
        assertEquals("0 to 1 of 1 chunks have recorded acknowledgements. 0 to 1 chunks are unacknowledged; the stored result cannot resolve the final acknowledgement. Health sync is incomplete. The receiver acknowledgement was invalid. Acceptance is not confirmed. No automatic resend will run.",
            SyncStatusDisplay.progress("Health sync PAUSED: 1/1; INVALID_ACK"))
        assertEquals("Chunk counts are unavailable after a storage failure. Health sync is incomplete. Local storage failed. Recorded counts may be unavailable. No automatic resend will run.",
            SyncStatusDisplay.progress("Health sync PAUSED: 0/0; IO_FAILURE"))
        assertEquals("3 of 3 chunks have recorded acknowledgements. Cursor commit is not confirmed. Health sync is incomplete.",
            SyncStatusDisplay.progress("Health sync COMMIT: 3/3; NONE"))
        val blocked = SyncStatusDisplay.progress("Health sync BLOCKED: 2/5; LEGACY_PENDING")
        assertTrue(blocked.contains("1 of 5 chunks"))
        assertTrue(blocked.contains("4 chunks are unacknowledged"))
        assertTrue(blocked.contains("legacy Pending"))
        assertTrue(blocked.contains("No automatic resend"))
    }

    @Test fun onlyDoneClaimsCompletionRegardlessOfStoredSuccessTime() {
        (ChunkPhase.entries.map { "Health sync $it: 1/1; NONE" } + "unrecognized").forEach { result ->
            val display = SyncStatusDisplay.format(snapshot(result = result).copy(lastSuccess = "2026-01-01T00:00:00Z"))
            val isDone = result == "Health sync DONE: 1/1; NONE"
            assertEquals(result, isDone, display.state == SyncStatusDisplay.State.DONE)
            assertEquals(result, isDone, display.reason.contains("acknowledgement and cursor commit are recorded"))
            assertEquals(result, isDone, SyncStatusDisplay.progress(result).contains("completion is recorded"))
        }
    }

    @Test fun retainedLegacyPendingIsExplicitWithoutInventedCounts() {
        val result = "Health sync PAUSED: legacy Pending preserved; delivery blocked"
        val display = SyncStatusDisplay.format(snapshot(result = result))
        assertEquals(SyncStatusDisplay.State.BLOCKED, display.state)
        assertEquals("Blocked", display.state.label)
        assertEquals("A retained legacy Pending batch blocks health delivery.", display.reason)
        assertFalse(SyncStatusDisplay.progress(result).contains("of 0"))
    }

    @Test fun malformedOrInjectedRecordsStayUnknownAndNeverEchoInput() {
        listOf(
            "Health sync DONE: 1/1; NONE secret-token",
            "Health sync PAUSED: 1/1; https://secret.invalid/token",
            "Health sync MADE_UP: 1/1; NONE", "Health sync PAUSED: 1/1; SECRET",
            "Health sync DONE: 0/1; NONE", "Health sync ACK: 2/1; NONE",
            "Health sync ACK: -1/1; NONE", "Health sync ACK: 1/0; NONE",
            "Health sync ACK: 999999999999/999999999999; NONE",
            "Health sync DONE: 1/1; HTTP_RETRY", "Health sync DONE: 1/1; NONE\n",
        ).forEach {
            val display = SyncStatusDisplay.format(snapshot(result = it))
            assertEquals(it, SyncStatusDisplay.State.UNKNOWN, display.state)
            assertEquals("No recognized health delivery progress is recorded.", SyncStatusDisplay.progress(it))
            assertFalse(display.toString().contains("secret", ignoreCase = true))
        }
    }

    @Test fun reasonsAreExplicitAndSuccessTimeIsIndependentOfResult() {
        val reasons = mapOf(
            "NONE" to "No stop reason was recorded.",
            "FLAG_OFF" to "Health delivery was disabled by consent or the fresh-chunking gate.",
            "LEGACY_PENDING" to "A legacy Pending batch requires review before health sync can continue.",
            "INVALID_TIMESTAMP" to "A record has an invalid timestamp.",
            "UNKNOWN_ACCEPTANCE" to "Receiver acceptance is unknown. Check the receiver before any resend.",
            "INVALID_ACK" to "The receiver acknowledgement was invalid. Acceptance is not confirmed.",
            "HTTP_400" to "The receiver rejected the request with HTTP 400.",
            "HTTP_409" to "The receiver reported a conflict with HTTP 409.",
            "HTTP_413" to "The receiver rejected the request size with HTTP 413.",
            "HTTP_BLOCKED" to "The receiver blocked the request.",
            "HTTP_RETRY" to "The receiver returned a retryable response.",
            "IO_FAILURE" to "Local storage failed. Recorded counts may be unavailable.",
            "CORRUPT_OUTBOX" to "The retained outbox could not be read safely.",
            "ENDPOINT_CHANGED" to "The endpoint differs from the retained batch destination.",
            "OVERSIZED" to "A record exceeds the delivery size limit.",
        )
        assertEquals(ChunkReason.entries.map { it.name }.toSet(), reasons.keys)
        reasons.forEach { (reason, expected) ->
            val s = snapshot(result = "Health sync PAUSED: 1/1; $reason")
            assertEquals("Health sync is incomplete. $expected", SyncStatusDisplay.format(s).reason)
            assertEquals("Never recorded", SyncStatusDisplay.lastSuccess(s.lastSuccess, Instant.EPOCH, ZoneId.of("UTC")))
        }
        val done = snapshot(result = "Health sync DONE: 1/1; NONE")
        assertEquals("Never recorded", SyncStatusDisplay.lastSuccess(done.lastSuccess, Instant.EPOCH, ZoneId.of("UTC")))
    }

    @Test fun containmentProducerResultsKeepTheirLabels() {
        val cases = listOf(
            Triple("Ready", SyncStatusDisplay.State.WAITING, "ON, waiting / paused"),
            Triple("Sync OFF", SyncStatusDisplay.State.WAITING, "ON, waiting / paused"),
            Triple("Health sync PAUSED: containment stage", SyncStatusDisplay.State.PAUSED, "Paused"),
            Triple("Health sync PAUSED: legacy Pending preserved; delivery blocked", SyncStatusDisplay.State.BLOCKED, "Blocked"),
            Triple("BLOCKED: empty Test acceptance unknown; no automatic retry", SyncStatusDisplay.State.BLOCKED, "Blocked"),
            Triple("Empty webhook test: RETRY; request not sent; no automatic retry", SyncStatusDisplay.State.WAITING, "ON, waiting / paused"),
        ) + (listOf(0) + (100..599)).map { code ->
            val delivery = when (code) { in 200..299 -> "ACCEPTED"; 0, 408, 425, 429, in 500..599 -> "RETRY"; else -> "BLOCKED" }
            Triple("Empty webhook test: $delivery HTTP $code; one shot, no automatic retry", SyncStatusDisplay.State.WAITING, "ON, waiting / paused")
        }
        cases.forEach { (result, state, label) ->
            val display = SyncStatusDisplay.format(snapshot(result = result))
            assertEquals(result, state, display.state)
            assertEquals(result, label, display.state.label)
        }
    }

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
