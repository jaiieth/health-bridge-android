package dev.healthbridge

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object Schedule {
    private val network =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodic(context: Context, minutes: Long) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(minutes, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(network)
                .build(),
        )
    }

    fun manual(context: Context, test: Boolean = false) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                if (test) "webhook-test" else "manual",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(network)
                    .setInputData(workDataOf("test" to test, "manual" to true))
                    .build(),
            )
    }

    fun pauseHealth(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf("periodic", "manual", "delivery-retry").forEach { manager.cancelUniqueWork(it) }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            AppState.mutex.withLock {
                Containment.run(
                    AppState.store(applicationContext),
                    Settings(applicationContext),
                    inputData.getBoolean("test", false),
                ) { endpoint, token, batch, beforeSend ->
                    Transport.sendEmpty(endpoint, token, batch, beforeSend)
                }
                Result.success()
            }
        }
}
