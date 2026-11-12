package dev.healthbridge

import androidx.health.connect.client.records.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal fun recordJson(r: Record, sourceName: String?): JSONObject {
    val metadata = r.metadata
    val source =
        JSONObject()
            .put("package", metadata.dataOrigin.packageName)
            .put("name", sourceName ?: JSONObject.NULL)
    val device =
        metadata.device?.let {
            JSONObject()
                .put("manufacturer", it.manufacturer ?: JSONObject.NULL)
                .put("model", it.model ?: JSONObject.NULL)
                .put("type", it.type)
        } ?: JSONObject.NULL
    val out =
        JSONObject()
            .put("type", r::class.simpleName)
            .put("recordId", metadata.id)
            .put("source", source.put("device", device))
            .put("lastModifiedTime", metadata.lastModifiedTime.toString())
            .put("recordingMethod", metadata.recordingMethod)
            .put("clientRecordId", metadata.clientRecordId ?: JSONObject.NULL)
            .put("clientRecordVersion", metadata.clientRecordVersion)
    val values = JSONObject()
    out.put("values", values)
    fun times(
        start: Instant,
        end: Instant,
        startOffset: java.time.ZoneOffset?,
        endOffset: java.time.ZoneOffset?,
    ) {
        out.put("startTime", start.toString())
            .put("endTime", end.toString())
            .put(
                "timezone",
                JSONObject()
                    .put("startOffset", startOffset?.id ?: JSONObject.NULL)
                    .put("endOffset", endOffset?.id ?: JSONObject.NULL),
            )
    }
    when (r) {
        is SleepSessionRecord -> {
            times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            values
                .put("unit", "stage_code")
                .put(
                    "stages",
                    JSONArray().apply {
                        r.stages.forEach {
                            put(
                                JSONObject()
                                    .put("start", it.startTime.toString())
                                    .put("end", it.endTime.toString())
                                    .put("stage", it.stage)
                            )
                        }
                    },
                )
        }
        is HeartRateVariabilityRmssdRecord -> {
            times(r.time, r.time, r.zoneOffset, r.zoneOffset)
            values.put("rmssd", r.heartRateVariabilityMillis).put("unit", "ms")
        }
        is RestingHeartRateRecord -> {
            times(r.time, r.time, r.zoneOffset, r.zoneOffset)
            values.put("beatsPerMinute", r.beatsPerMinute).put("unit", "bpm")
        }
        is HeartRateRecord -> {
            times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            values
                .put("unit", "bpm")
                .put(
                    "samples",
                    JSONArray().apply {
                        r.samples.forEach {
                            put(
                                JSONObject()
                                    .put("time", it.time.toString())
                                    .put("beatsPerMinute", it.beatsPerMinute)
                            )
                        }
                    },
                )
        }
        is StepsRecord -> {
            times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            values.put("count", r.count).put("unit", "count")
        }
        is ExerciseSessionRecord -> {
            times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            values.put("exerciseType", r.exerciseType).put("unit", "exercise_type_code")
        }
    }
    return out
}
