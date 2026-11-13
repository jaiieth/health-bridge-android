package dev.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass
import org.json.JSONArray
import org.json.JSONObject

class HealthReader(private val context: Context) {
    companion object {
        val types: List<KClass<out Record>> =
            listOf(
                SleepSessionRecord::class,
                HeartRateVariabilityRmssdRecord::class,
                RestingHeartRateRecord::class,
                HeartRateRecord::class,
                StepsRecord::class,
                ExerciseSessionRecord::class,
            )
        val permissions = types.map { HealthPermission.getReadPermission(it) }.toSet()
    }

    fun requestedPermissions(): Set<String> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            return permissions
        val features = HealthConnectClient.getOrCreate(context).features
        return if (
            features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        )
            permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        else permissions
    }

    suspend fun hasPermissions(): Boolean {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            return false
        return HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
            .containsAll(permissions)
    }

    suspend fun canCollect(): Boolean {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            return false
        val granted =
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return granted.containsAll(permissions) &&
            (AppState.foreground ||
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted)
    }

    internal fun freshInput(cursors: Map<String, String>, end: Instant): FreshChunkInput =
        FreshChunkInput(types.associate { checkNotNull(it.simpleName) to end.toString() }, FreshRecordProducer { accept ->
            check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                "Health Connect unavailable"
            }
            val client = HealthConnectClient.getOrCreate(context)
            check(client.permissionController.getGrantedPermissions().containsAll(permissions)) { "Missing read permissions" }
            for (type in types) {
                val start = cursors[type.simpleName]?.let(Instant::parse) ?: end.minusSeconds(30L * 86400)
                produceFreshPages(readPage = { token ->
                    val response = client.readRecords(ReadRecordsRequest(
                        type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageToken = token,
                        pageSize = 1,
                    ))
                    FreshPage(response.records, response.pageToken)
                }, accept = { record ->
                    val name = runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(record.metadata.dataOrigin.packageName, 0),
                        ).toString()
                    }.getOrNull()
                    accept(boundedRecordJson(record, name))
                })
            }
        })

    suspend fun read(cursors: Map<String, String>, end: Instant): Pair<JSONArray, JSONObject> {
        check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect unavailable"
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        check(granted.containsAll(permissions)) { "Missing read permissions" }
        val records = JSONArray()
        val next = JSONObject()
        for (type in types) {
            val key = type.simpleName!!
            val start = cursors[key]?.let(Instant::parse) ?: end.minusSeconds(30L * 86400)
            var page: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            type,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageToken = page,
                            pageSize = 1000,
                        )
                    )
                for (record in response.records) {
                    val name =
                        runCatching {
                                context.packageManager
                                    .getApplicationLabel(
                                        context.packageManager.getApplicationInfo(
                                            record.metadata.dataOrigin.packageName,
                                            0,
                                        )
                                    )
                                    .toString()
                            }
                            .getOrNull()
                    records.put(recordJson(record, name))
                }
                page = response.pageToken
            } while (page != null)
            next.put(key, end.toString())
        }
        return records to next
    }
}
