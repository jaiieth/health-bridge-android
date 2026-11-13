package dev.healthbridge

import androidx.health.connect.client.records.*
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject

internal const val FRESH_RECORD_BYTES = 524_288

internal class OversizedFreshRecord(val recordId: String?, val wireBytes: Long) : RuntimeException()

internal class BoundedJsonWriter(private val capacity: Int = FRESH_RECORD_BYTES) {
    private val bytes = ByteArray(capacity)
    var size: Int = 0
        private set

    private fun byte(value: Int) {
        if (size == capacity) throw OversizedFreshRecord(null, size.toLong() + 1)
        bytes[size++] = value.toByte()
    }

    fun raw(value: String) { value.forEach { require(it.code < 128); byte(it.code) } }
    fun number(value: Number) = raw(JSONObject.numberToString(value))
    fun nullable(value: String?) { if (value == null) raw("null") else string(value) }
    fun string(value: String) {
        byte(34)
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            when (c) {
                '"' -> raw("\\\"")
                '\\' -> raw("\\\\")
                '\b' -> raw("\\b")
                '\u000c' -> raw("\\f")
                '\n' -> raw("\\n")
                '\r' -> raw("\\r")
                '\t' -> raw("\\t")
                else -> when {
                    c < ' ' || c.isSurrogate() && !(c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate()) -> {
                        raw("\\u")
                        for (shift in 12 downTo 0 step 4) byte("0123456789abcdef"[(c.code shr shift) and 15].code)
                    }
                    c.code < 128 -> byte(c.code)
                    c.code < 2048 -> { byte(192 or (c.code shr 6)); byte(128 or (c.code and 63)) }
                    c.isHighSurrogate() -> {
                        val code = Character.toCodePoint(c, value[index++])
                        byte(240 or (code shr 18)); byte(128 or ((code shr 12) and 63))
                        byte(128 or ((code shr 6) and 63)); byte(128 or (code and 63))
                    }
                    else -> {
                        byte(224 or (c.code shr 12)); byte(128 or ((c.code shr 6) and 63)); byte(128 or (c.code and 63))
                    }
                }
            }
        }
        byte(34)
    }
    fun json(): String = String(bytes, 0, size, Charsets.UTF_8)
}

internal fun boundedRecordJson(r: Record, sourceName: String?): ChunkRecord {
    val metadata = r.metadata
    val out = BoundedJsonWriter()
    try {
        out.raw("{\"type\":"); out.nullable(r::class.simpleName)
        out.raw(",\"recordId\":"); out.string(metadata.id)
        out.raw(",\"source\":{\"package\":"); out.string(metadata.dataOrigin.packageName)
        out.raw(",\"name\":"); out.nullable(sourceName)
        out.raw(",\"device\":")
        val device = metadata.device
        if (device == null) out.raw("null") else {
            out.raw("{\"manufacturer\":"); out.nullable(device.manufacturer)
            out.raw(",\"model\":"); out.nullable(device.model)
            out.raw(",\"type\":"); out.number(device.type); out.raw("}")
        }
        out.raw("},\"lastModifiedTime\":"); out.string(metadata.lastModifiedTime.toString())
        out.raw(",\"recordingMethod\":"); out.number(metadata.recordingMethod)
        out.raw(",\"clientRecordId\":"); out.nullable(metadata.clientRecordId)
        out.raw(",\"clientRecordVersion\":"); out.number(metadata.clientRecordVersion)
        fun times(start: Instant, end: Instant, startOffset: ZoneOffset?, endOffset: ZoneOffset?) {
            out.raw(",\"startTime\":"); out.string(start.toString())
            out.raw(",\"endTime\":"); out.string(end.toString())
            out.raw(",\"timezone\":{\"startOffset\":"); out.nullable(startOffset?.id)
            out.raw(",\"endOffset\":"); out.nullable(endOffset?.id); out.raw("}")
        }
        when (r) {
            is SleepSessionRecord -> {
                times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
                out.raw(",\"values\":{\"unit\":\"stage_code\",\"stages\":[")
                r.stages.forEachIndexed { i, stage ->
                    if (i > 0) out.raw(",")
                    out.raw("{\"start\":"); out.string(stage.startTime.toString())
                    out.raw(",\"end\":"); out.string(stage.endTime.toString())
                    out.raw(",\"stage\":"); out.number(stage.stage); out.raw("}")
                }
                out.raw("]}")
            }
            is HeartRateVariabilityRmssdRecord -> {
                times(r.time, r.time, r.zoneOffset, r.zoneOffset)
                out.raw(",\"values\":{\"rmssd\":"); out.number(r.heartRateVariabilityMillis); out.raw(",\"unit\":\"ms\"}")
            }
            is RestingHeartRateRecord -> {
                times(r.time, r.time, r.zoneOffset, r.zoneOffset)
                out.raw(",\"values\":{\"beatsPerMinute\":"); out.number(r.beatsPerMinute); out.raw(",\"unit\":\"bpm\"}")
            }
            is HeartRateRecord -> {
                times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
                out.raw(",\"values\":{\"unit\":\"bpm\",\"samples\":[")
                r.samples.forEachIndexed { i, sample ->
                    if (i > 0) out.raw(",")
                    out.raw("{\"time\":"); out.string(sample.time.toString())
                    out.raw(",\"beatsPerMinute\":"); out.number(sample.beatsPerMinute); out.raw("}")
                }
                out.raw("]}")
            }
            is StepsRecord -> {
                times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
                out.raw(",\"values\":{\"count\":"); out.number(r.count); out.raw(",\"unit\":\"count\"}")
            }
            is ExerciseSessionRecord -> {
                times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
                out.raw(",\"values\":{\"exerciseType\":"); out.number(r.exerciseType); out.raw(",\"unit\":\"exercise_type_code\"}")
            }
            else -> out.raw(",\"values\":{}")
        }
        out.raw("}")
        return ChunkRecord(metadata.id, out.json())
    } catch (e: OversizedFreshRecord) {
        throw OversizedFreshRecord(metadata.id.takeIf { it.length <= FRESH_RECORD_BYTES }, e.wireBytes)
    }
}
