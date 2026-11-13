package dev.healthbridge

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-only interpretation. Never changes queue or delivery state. */
object SyncStatusDisplay {
    enum class State(val label: String, val icon: String) {
        DONE("Acknowledged", "check"), PARTIAL("In progress / partial", "clock"),
        IO_FAILURE("Storage failure", "warning"), OFF("OFF", "stop"), BLOCKED("Blocked", "warning"), PAUSED("Paused", "pause"),
        WAITING("ON, waiting / paused", "clock"), UNKNOWN("Unknown", "help"), GATED("Build gated", "pause")
    }
    data class Display(val state: State, val reason: String, val action: String, val recordedOutcome: String? = null)
    data class Snapshot(
        val enabled: Boolean,
        val configured: Boolean,
        val permissions: Boolean,
        val result: String = "Ready",
        val lastSuccess: String = "Never",
        val pending: Boolean = false,
        val freshChunkingEnabled: Boolean = ChunkContracts.freshChunkingEnabled,
    )

    const val buildUnavailableReason = "Health sync unavailable in this build - resource safety pending"

    data class Controls(val consentEnabled: Boolean, val manualEnabled: Boolean, val testEnabled: Boolean)

    fun controls(s: Snapshot) = Controls(
        consentEnabled = s.freshChunkingEnabled || s.enabled,
        manualEnabled = s.freshChunkingEnabled && s.enabled && s.configured && s.permissions,
        testEnabled = s.configured,
    )

    fun consentLabel(s: Snapshot): String = if (s.freshChunkingEnabled)
        if (s.enabled) "Sync ON" else "Sync OFF"
    else if (s.enabled) "Saved health consent: ON (turn OFF)" else "Saved health consent: OFF"

    private data class Recorded(val phase: ChunkPhase, val index: Int, val total: Int, val reason: ChunkReason)

    private fun recorded(result: String): Recorded? {
        val match = Regex("Health sync ([A-Z_]+): ([0-9]+)/([0-9]+); ([A-Z_0-9]+)").matchEntire(result) ?: return null
        val phase = ChunkPhase.entries.find { it.name == match.groupValues[1] } ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        val total = match.groupValues[3].toIntOrNull() ?: return null
        val reason = ChunkReason.entries.find { it.name == match.groupValues[4] } ?: return null
        if (if (total == 0) index != 0 else index !in 1..total) return null
        if (phase in setOf(ChunkPhase.DONE, ChunkPhase.COMMIT) && (index != total || reason != ChunkReason.NONE)) return null
        return Recorded(phase, index, total, reason)
    }

    private fun description(reason: ChunkReason): String = when (reason) {
        ChunkReason.NONE -> "No stop reason was recorded."
        ChunkReason.FLAG_OFF -> "Health delivery was disabled by consent or the fresh-chunking gate."
        ChunkReason.LEGACY_PENDING -> "A legacy Pending batch requires review before health sync can continue."
        ChunkReason.INVALID_TIMESTAMP -> "A record has an invalid timestamp."
        ChunkReason.UNKNOWN_ACCEPTANCE -> "Receiver acceptance is unknown. Check the receiver before any resend."
        ChunkReason.INVALID_ACK -> "The receiver acknowledgement was invalid. Acceptance is not confirmed."
        ChunkReason.HTTP_400 -> "The receiver rejected the request with HTTP 400."
        ChunkReason.HTTP_409 -> "The receiver reported a conflict with HTTP 409."
        ChunkReason.HTTP_413 -> "The receiver rejected the request size with HTTP 413."
        ChunkReason.HTTP_BLOCKED -> "The receiver blocked the request."
        ChunkReason.HTTP_RETRY -> "The receiver returned a retryable response."
        ChunkReason.IO_FAILURE -> "Local storage failed. Recorded counts may be unavailable."
        ChunkReason.CORRUPT_OUTBOX -> "The retained outbox could not be read safely."
        ChunkReason.ENDPOINT_CHANGED -> "The endpoint differs from the retained batch destination."
        ChunkReason.OVERSIZED -> "A record exceeds the delivery size limit."
    }

    private fun chunkDisplay(r: Recorded): Display {
        val state = when {
            r.reason == ChunkReason.IO_FAILURE -> State.IO_FAILURE
            r.reason == ChunkReason.FLAG_OFF -> State.OFF
            r.phase == ChunkPhase.DONE -> State.DONE
            r.phase == ChunkPhase.BLOCKED -> State.BLOCKED
            r.phase == ChunkPhase.PAUSED -> State.PAUSED
            else -> State.PARTIAL
        }
        val detail = when (r.phase) {
            ChunkPhase.DONE -> "Health delivery acknowledgement and cursor commit are recorded."
            ChunkPhase.COMMIT -> "All chunks are acknowledged. Cursor commit is not confirmed."
            else -> "Health sync is incomplete."
        }
        val action = if (state in setOf(State.PAUSED, State.BLOCKED, State.IO_FAILURE, State.OFF))
            "No automatic resend will run. Review the recorded reason before continuing."
        else if (state == State.DONE) "The last actual success time comes only from the stored last-success timestamp."
        else "Health sync remains incomplete."
        return Display(state, "$detail ${description(r.reason)}", action)
    }

    fun format(s: Snapshot): Display = when {
        !s.freshChunkingEnabled -> Display(State.GATED, buildUnavailableReason,
            if (s.enabled) "Consent is saved. This build cannot deliver health data yet. You can turn saved consent OFF. Send test remains separate."
            else "Saved consent is OFF. Enabling health sync is unavailable. Send test remains separate.",
            recordedOutcome = when {
                recorded(s.result) != null || s.result == "Health sync PAUSED: legacy Pending preserved; delivery blocked" ->
                    "Recorded past result: ${progress(s.result)}"
                s.result == "Health sync PAUSED: containment stage" ->
                    "Recorded past result: Health sync was paused during containment."
                else -> null
            })
        !s.enabled -> Display(State.OFF, "Automatic sync is off. Retained data is kept.", "An already active empty test may finish. Send test is separate from health sync.")
        s.result == "BLOCKED: empty Test acceptance unknown; no automatic retry" -> Display(State.BLOCKED, "Empty test acceptance is unknown. Health delivery remains paused.", "No automatic retry will run. Check the receiver before another explicit test.")
        s.result == "Health sync PAUSED: containment stage" -> Display(State.PAUSED, "Health sync is paused during containment.", "ON records your preference. It does not deliver health data.")
        s.result == "Health sync PAUSED: legacy Pending preserved; delivery blocked" -> Display(State.BLOCKED, "A retained legacy Pending batch blocks health delivery.", "No automatic resend will run. Review the retained batch before continuing.")
        recorded(s.result) != null -> chunkDisplay(checkNotNull(recorded(s.result)))
        !s.configured -> Display(State.BLOCKED, "Setup is incomplete. Health delivery remains paused.", "Save an HTTPS endpoint and a token to use Send test.")
        !s.permissions -> Display(State.BLOCKED, "Health read access is missing. Health delivery remains paused.", "Read permissions are separate from an empty webhook test.")
        s.result == "Ready" || s.result == "Sync OFF" || s.result.startsWith("Empty webhook test:") ->
            Display(State.WAITING, "Sync is ON and waiting. Health delivery remains paused.", "Only explicit empty tests can contact the webhook during containment.")
        else -> Display(State.UNKNOWN, "The recorded result is not recognized. No health delivery is confirmed.", "Health delivery remains paused during containment.")
    }

    fun progress(result: String = "Ready"): String {
        if (result == "Health sync PAUSED: legacy Pending preserved; delivery blocked")
            return "A retained legacy Pending batch blocks health delivery. Chunk counts are not recorded. No automatic resend will run."
        val r = recorded(result) ?: return if (result == "Ready" || result == "Sync OFF" || result.startsWith("Empty webhook test:") ||
            result == "BLOCKED: empty Test acceptance unknown; no automatic retry" || result == "Health sync PAUSED: containment stage")
            "Progress is ready. No health delivery progress is available during containment."
        else "No recognized health delivery progress is recorded."
        val counts = when {
            r.reason == ChunkReason.IO_FAILURE && r.total == 0 -> "Chunk counts are unavailable after a storage failure."
            r.phase in setOf(ChunkPhase.DONE, ChunkPhase.COMMIT) -> "${r.total} of ${r.total} chunks have recorded acknowledgements."
            r.total == 0 -> "0 of 0 chunks are recorded."
            r.index == r.total -> "${r.total - 1} to ${r.total} of ${r.total} chunks have recorded acknowledgements. 0 to 1 chunks are unacknowledged; the stored result cannot resolve the final acknowledgement."
            else -> "${r.index - 1} of ${r.total} chunks have recorded acknowledgements. ${r.total - r.index + 1} chunks are unacknowledged."
        }
        val outcome = when (r.phase) {
            ChunkPhase.DONE -> "Health sync completion is recorded."
            ChunkPhase.COMMIT -> "Cursor commit is not confirmed. Health sync is incomplete."
            else -> "Health sync is incomplete."
        }
        val stopped = r.phase in setOf(ChunkPhase.PAUSED, ChunkPhase.BLOCKED) || r.reason != ChunkReason.NONE
        return "$counts $outcome" + if (stopped) " ${description(r.reason)} No automatic resend will run." else ""

    }

    fun tokenIndicator(saved: Boolean): String = if (saved) "Token saved. Leave blank to keep it." else "Token not saved. Enter a token."
    fun tokenFieldValue(): String = ""
    fun host(endpoint: String): String = runCatching { URI(endpoint).host }.getOrNull() ?: "Not configured"
    fun testResult(result: String): String = when {
        result.matches(Regex("Empty webhook test: ACCEPTED HTTP 2[0-9]{2}; one shot, no automatic retry")) ->
            "Empty test acknowledged by the server. ${result.substringAfter(": ")}. This does not confirm health delivery."
        result.matches(Regex("Empty webhook test: (RETRY|BLOCKED) HTTP [0-9]+; one shot, no automatic retry")) ->
            "Empty test recorded. ${result.substringAfter(": ")}. No acceptance is confirmed."
        result == "Empty webhook test: RETRY; request not sent; no automatic retry" ->
            "Empty test was not sent. No automatic retry will run."
        result == "BLOCKED: empty Test acceptance unknown; no automatic retry" ->
            "Blocked. Empty test acceptance is unknown. No automatic retry will run."
        else -> "No recorded empty test acknowledgement."
    }
    private fun instant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    fun lastSuccess(value: String, now: Instant, zone: ZoneId): String {
        val time = instant(value) ?: return "Never recorded"
        val seconds = Duration.between(time, now).seconds
        val age = when {
            seconds < 0 -> "device clock is earlier than record"
            seconds < 60 -> "less than a minute ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            seconds < 86400 -> "${seconds / 3600} ${if (seconds / 3600 == 1L) "hour" else "hours"} ago"
            else -> "${seconds / 86400} days ago"
        }
        return "${DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss z", java.util.Locale.ENGLISH).withZone(zone).format(time)}\n$age"
    }
}
