package dev.healthbridge

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-only interpretation. Never changes queue or delivery state. */
object SyncStatusDisplay {
    enum class State(val label: String, val icon: String) {
        OFF("OFF", "stop"), BLOCKED("Blocked", "warning"), PAUSED("Paused", "pause"),
        WAITING("ON, waiting / paused", "clock"), UNKNOWN("Unknown", "help")
    }
    data class Display(val state: State, val reason: String, val action: String)
    data class Snapshot(
        val enabled: Boolean,
        val configured: Boolean,
        val permissions: Boolean,
        val result: String = "Ready",
        val lastSuccess: String = "Never",
        val pending: Boolean = false,
    )

    fun format(s: Snapshot): Display = when {
        !s.enabled -> Display(State.OFF, "Automatic sync is off. Retained data is kept.", "An already active empty test may finish. Send test is separate from health sync.")
        s.result == "BLOCKED: empty Test acceptance unknown; no automatic retry" -> Display(State.BLOCKED, "Empty test acceptance is unknown. Health delivery remains paused.", "No automatic retry will run. Check the receiver before another explicit test.")
        s.result == "Health sync PAUSED: containment stage" -> Display(State.PAUSED, "Health sync is paused during containment.", "ON records your preference. It does not deliver health data.")
        !s.configured -> Display(State.BLOCKED, "Setup is incomplete. Health delivery remains paused.", "Save an HTTPS endpoint and a token to use Send test.")
        !s.permissions -> Display(State.BLOCKED, "Health read access is missing. Health delivery remains paused.", "Read permissions are separate from an empty webhook test.")
        s.result == "Ready" || s.result == "Sync OFF" || s.result.startsWith("Empty webhook test:") ->
            Display(State.WAITING, "Sync is ON and waiting. Health delivery remains paused.", "Only explicit empty tests can contact the webhook during containment.")
        else -> Display(State.UNKNOWN, "The recorded result is not recognized. No health delivery is confirmed.", "Health delivery remains paused during containment.")
    }

    fun progress(): String = "Progress is ready. No health delivery progress is available during containment."

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
