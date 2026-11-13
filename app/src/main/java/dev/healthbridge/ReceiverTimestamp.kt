package dev.healthbridge

import java.time.DateTimeException
import java.time.LocalDate

internal class InvalidRecordTimestamp : IllegalArgumentException("INVALID_TIMESTAMP")

private val receiverTimestampPattern = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]{1,9}))?Z")

internal fun receiverTimestampKey(value: Any?): String {
    val text = value as? String ?: throw InvalidRecordTimestamp()
    val match = receiverTimestampPattern.matchEntire(text) ?: throw InvalidRecordTimestamp()
    val prefix = match.groupValues[1]
    try {
        LocalDate.of(prefix.substring(0, 4).toInt(), prefix.substring(5, 7).toInt(), prefix.substring(8, 10).toInt())
    } catch (_: DateTimeException) {
        throw InvalidRecordTimestamp()
    }
    if (prefix.substring(11, 13).toInt() !in 0..23 || prefix.substring(14, 16).toInt() !in 0..59 || prefix.substring(17, 19).toInt() !in 0..59) throw InvalidRecordTimestamp()
    return prefix + "." + match.groupValues[2].padEnd(9, '0') + "Z"
}
