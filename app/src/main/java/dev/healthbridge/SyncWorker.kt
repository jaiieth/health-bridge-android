package dev.healthbridge

import android.content.Context
import androidx.work.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object Schedule {
    private val network =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodic(context: Context, minutes: Long) {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                    .setConstraints(network)
                    .build(),
            )
    }

    fun manual(context: Context, test: Boolean = false) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "manual",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(network)
                    .setInputData(workDataOf("test" to test, "manual" to true))
                    .build(),
            )
    }

    suspend fun retry(context: Context, delay: Long) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "delivery-retry",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(network)
                    .setInputData(workDataOf("retry" to true))
                    .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build(),
            )
            .await()
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            AppState.mutex.withLock {
                val store = AppState.store(applicationContext)
                try {
                    val settings = Settings(applicationContext)
                    Transport.validateUrl(settings.endpoint)
                    require(settings.token.isNotBlank())
                    var pending = store.pending()
                    val test = inputData.getBoolean("test", false)
                    if (pending == null && inputData.getBoolean("retry", false))
                        return@withLock Result.success()
                    val reader = HealthReader(applicationContext)
                    val healthBatch = !test && pending?.isTest != true
                    if (healthBatch && !reader.hasPermissions()) {
                        store.status(
                            (store.status() ?: SyncStatus()).copy(
                                result = "Sync skipped: all six read permissions are required"
                            )
                        )
                        return@withLock Result.success()
                    }
                    if (healthBatch && pending == null && !reader.canCollect()) {
                        store.status(
                            (store.status() ?: SyncStatus()).copy(
                                result =
                                    "Collection skipped: open the app or grant background read access"
                            )
                        )
                        return@withLock Result.success()
                    }
                    if (test && pending != null) {
                        store.status(
                            (store.status() ?: SyncStatus()).copy(
                                result = "Test not sent: deliver pending batch first"
                            )
                        )
                        return@withLock Result.success()
                    }
                    if (pending != null && inputData.getBoolean("manual", false)) {
                        pending = pending.copy(attempts = 0, nextAttempt = 0)
                        store.put(pending)
                    }
                    if (pending == null) {
                        val end = Instant.now()
                        val (records, cursors) =
                            if (test) JSONArray() to JSONObject()
                            else reader.read(store.cursors().associate { it.type to it.until }, end)
                        val id = UUID.randomUUID().toString()
                        val bytes =
                            JSONObject()
                                .put("schemaVersion", 1)
                                .put("batchId", id)
                                .put("records", records)
                                .toString()
                                .toByteArray(Charsets.UTF_8)
                        pending =
                            Pending(
                                batchId = id,
                                body = bytes,
                                cursors = cursors.toString(),
                                isTest = test,
                            )
                        store.put(pending)
                    }
                    if (RetryPolicy.exhausted(pending.attempts)) {
                        store.status(
                            (store.status() ?: SyncStatus()).copy(
                                result =
                                    "Queue exhausted after 5 attempts; Sync now explicitly retries retained batch"
                            )
                        )
                        return@withLock Result.success()
                    }
                    if (pending.nextAttempt > System.currentTimeMillis()) {
                        Schedule.retry(
                            applicationContext,
                            pending.nextAttempt - System.currentTimeMillis(),
                        )
                        return@withLock Result.success()
                    }
                    val attempt = pending.attempts + 1
                    val attempted =
                        pending.copy(
                            attempts = attempt,
                            nextAttempt =
                                System.currentTimeMillis() + RetryPolicy.delayMillis(attempt),
                        )
                    store.put(attempted)
                    // Schedule before the network operation so process death cannot strand a retry.
                    if (!RetryPolicy.exhausted(attempt))
                        Schedule.retry(applicationContext, RetryPolicy.delayMillis(attempt))
                    val code =
                        try {
                            Transport.send(settings.endpoint, settings.token, attempted)
                        } catch (_: java.io.IOException) {
                            0
                        }
                    if (code in 200..299) {
                        val map = JSONObject(attempted.cursors)
                        val cursors =
                            map.keys().asSequence().map { Cursor(it, map.getString(it)) }.toList()
                        store.success(
                            cursors,
                            Instant.now().toString(),
                            attempted.isTest,
                            if (attempted.isTest) "Empty webhook test delivered: HTTP $code"
                            else "Sync delivered: HTTP $code",
                        )
                    } else {
                        val result =
                            if (RetryPolicy.exhausted(attempt))
                                "Queue exhausted after 5 attempts; Sync now retries"
                            else "Attempt $attempt failed; retry scheduled"
                        store.status(
                            (store.status() ?: SyncStatus()).copy(
                                result = "$result (HTTP $code; 0 means network failure)"
                            )
                        )
                    }
                    Result.success()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    store.status(
                        (store.status() ?: SyncStatus()).copy(
                            result =
                                "Sync unavailable: check settings, Health Connect availability and permissions"
                        )
                    )
                    Result.success()
                }
            }
        }
}
