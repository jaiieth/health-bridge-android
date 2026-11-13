package dev.healthbridge

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException

interface SyncSettings {
    val syncEnabled: Boolean
    val endpoint: String
    val token: String
}

enum class Delivery { ACCEPTED, RETRY, BLOCKED }

class EmptyTest(val batchId: String = UUID.randomUUID().toString()) {
    init {
        require(UUID.fromString(batchId).toString() == batchId)
    }

    val body: ByteArray
        get() = "{\"schemaVersion\":1,\"batchId\":\"$batchId\",\"records\":[]}".toByteArray(Charsets.UTF_8)
}

object Containment {
    fun classify(code: Int): Delivery = when {
        code in 200..299 -> Delivery.ACCEPTED
        code == 0 || code == 408 || code == 425 || code == 429 || code in 500..599 -> Delivery.RETRY
        else -> Delivery.BLOCKED
    }

    suspend fun run(
        store: Store,
        settings: SyncSettings,
        test: Boolean,
        freshSync: suspend () -> Unit,
        send: suspend (String, String, EmptyTest, suspend () -> Unit) -> Int,
    ) {
        suspend fun status(result: String) {
            store.status((store.status() ?: SyncStatus()).copy(result = result))
        }
        if (!test) {
            if (!settings.syncEnabled) {
                status("Sync OFF")
                return
            }
            if (store.pending() != null) {
                status("Health sync PAUSED: legacy Pending preserved; delivery blocked")
                return
            }
            freshSync()
            return
        }
        var enteredTransport = false
        try {
            val code = try {
                send(settings.endpoint, settings.token, EmptyTest()) {
                    status("BLOCKED: empty Test acceptance unknown; no automatic retry")
                    enteredTransport = true
                }
            } catch (_: IOException) {
                0
            }
            status("Empty webhook test: ${classify(code)} HTTP $code; one shot, no automatic retry")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!enteredTransport) status("Empty webhook test: RETRY; request not sent; no automatic retry")
        }
    }
}
