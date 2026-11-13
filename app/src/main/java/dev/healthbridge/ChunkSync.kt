package dev.healthbridge

import java.io.IOException
import java.util.UUID

internal data class FreshChunkInput(
    val records: List<ChunkRecord>,
    val targetCursors: Map<String, String>,
    val producer: FreshRecordProducer? = null,
) {
    constructor(targetCursors: Map<String, String>, producer: FreshRecordProducer) : this(emptyList(), targetCursors, producer)
}
internal data class ChunkResponse(val code: Int, val body: String)
internal interface ChunkCursorStore {
    suspend fun hasLegacyPending(): Boolean
    /** Must check Pending and replace all targets in one transaction. False means Pending exists. */
    suspend fun commit(targets: Map<String, String>): Boolean
    /** Read durable cursor values. Implementations without this proof cannot authorize retention. */
    suspend fun committed(targets: Map<String, String>): Boolean = false
}

internal object ChunkContracts {
    const val freshChunkingEnabled: Boolean = false
    val verifiedMapping: VerifiedReceiverMapping = ProductionReceiverMapping()
}

/** All methods run while the caller holds AppState.mutex. No automatic unknown-outcome recovery. */
internal class ChunkSync(
    private val outbox: ChunkOutbox,
    private val cursors: ChunkCursorStore,
    private val send: suspend (String, ByteArray) -> ChunkResponse,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val limits: ChunkLimits = ChunkLimits(),
) {
    suspend fun run(
        enabled: Boolean,
        endpoint: String,
        mapping: VerifiedReceiverMapping,
        collect: suspend () -> FreshChunkInput,
    ): ChunkStatus {
        if (!enabled) return ChunkStatus(ChunkPhase.PLAN, reason = ChunkReason.FLAG_OFF)
        if (cursors.hasLegacyPending()) return ChunkStatus(ChunkPhase.BLOCKED, reason = ChunkReason.LEGACY_PENDING)
        try { outbox.reclaimDone(cursors) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
        }
        var manifest = try { outbox.load()?.takeUnless { it.phase == ChunkPhase.DONE } } catch (_: IOException) {
            return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
        } catch (_: Exception) {
            return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.CORRUPT_OUTBOX)
        }
        val endpointHash = sha256(endpoint.toByteArray(Charsets.UTF_8))
        if (manifest != null) {
            if (manifest.phase == ChunkPhase.PAUSED || manifest.phase == ChunkPhase.BLOCKED) return manifest.status()
            if (manifest.phase == ChunkPhase.SEND) return stop(manifest, ChunkPhase.PAUSED, ChunkReason.UNKNOWN_ACCEPTANCE)
            if (manifest.endpointHash != endpointHash) return stop(manifest, ChunkPhase.PAUSED, ChunkReason.ENDPOINT_CHANGED)
            try { outbox.resume(manifest) } catch (_: Exception) {
                return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
            }
        } else {
            try { outbox.backpressure()?.let { return ChunkStatus(ChunkPhase.BLOCKED, reason = it) } }
            catch (_: Exception) { return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE) }
            val input = collect()
            if (input.producer != null) {
                manifest = try {
                    StreamingChunkPlanner(outbox, mapping, limits, newId, clock).stage(input, endpointHash)
                } catch (e: ChunkBudgetExceeded) {
                    return ChunkStatus(ChunkPhase.BLOCKED, reason = e.reason)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
                }
                if (manifest.phase == ChunkPhase.BLOCKED) return manifest.status()
            } else {
                val ids = List(input.records.size.coerceAtLeast(1)) { newId() }
                require(ids.all { receiverBatchId.matches(it) }) { "INVALID_BATCH_ID" }
                val plan = ChunkPartitioner.partition(input.records, ids, mapping, limits)
                val now = clock()
                val recordIds = input.records.map { it.recordId }
                val recordHashes = input.records.map { sha256(it.json.toByteArray(Charsets.UTF_8)) }
                val targets = input.targetCursors.toSortedMap()
                val children = (plan as? ChunkPlan.Ready)?.children?.mapIndexed { index, child ->
                    ChunkChild(index, child.batchId, sha256(child.body), child.records.size)
                } ?: emptyList()
                val blocked = plan as? ChunkPlan.Blocked
                manifest = ChunkManifest(newId(), parentFingerprint(endpointHash, recordIds, recordHashes, targets), endpointHash,
                    recordIds, recordHashes, targets, now, now, if (blocked == null) ChunkPhase.STAGE else ChunkPhase.BLOCKED, children,
                    when (blocked?.cause) {
                        null -> ChunkReason.NONE
                        ChunkBlockCause.OVERSIZED -> ChunkReason.OVERSIZED
                        ChunkBlockCause.INVALID_TIMESTAMP -> ChunkReason.INVALID_TIMESTAMP
                        ChunkBlockCause.ENVELOPE_LIMIT -> ChunkReason.ENVELOPE_LIMIT
                    }, blocked?.recordId, blocked?.wireBytes, blocked?.mappedBytes)
                try { outbox.stage(manifest, plan as? ChunkPlan.Ready) } catch (e: ChunkBudgetExceeded) {
                    return ChunkStatus(ChunkPhase.BLOCKED, reason = e.reason)
                } catch (_: Exception) {
                    return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
                }
                if (blocked != null) return manifest.status()
            }
        }
        var current = checkNotNull(manifest)
        try {
            current.children.indices.forEach { index ->
                if (current.children[index].acked) return@forEach
                if (cursors.hasLegacyPending()) return stop(current, ChunkPhase.BLOCKED, ChunkReason.LEGACY_PENDING)
                val bytes = outbox.body(current, index)
                val child = current.children[index]
                current = current.copy(phase = ChunkPhase.SEND, updatedAt = clock(), children = current.children.mapIndexed { i, c ->
                    if (i == index) c.copy(sentAt = clock()) else c
                })
                outbox.save(current)
                val response = try { send(child.batchId, bytes) } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return stop(current, ChunkPhase.PAUSED, ChunkReason.UNKNOWN_ACCEPTANCE)
                }
                when (Containment.classify(response.code)) {
                    Delivery.BLOCKED -> return stop(current, ChunkPhase.BLOCKED, when (response.code) {
                        400 -> ChunkReason.HTTP_400
                        409 -> ChunkReason.HTTP_409
                        413 -> ChunkReason.HTTP_413
                        else -> ChunkReason.HTTP_BLOCKED
                    })
                    Delivery.RETRY -> return stop(current, ChunkPhase.PAUSED, ChunkReason.HTTP_RETRY)
                    Delivery.ACCEPTED -> if (response.code != 200 || !validChunkAck(response.body, child.batchId)) {
                        return stop(current, ChunkPhase.PAUSED, ChunkReason.INVALID_ACK)
                    }
                }
                val now = clock()
                current = current.copy(phase = ChunkPhase.ACK, updatedAt = now, children = current.children.mapIndexed { i, c ->
                    if (i == index) c.copy(acked = true, ackedAt = now) else c
                })
                outbox.save(current)
            }
            current = current.copy(phase = ChunkPhase.COMMIT, updatedAt = clock())
            outbox.save(current)
            if (!cursors.commit(current.targets)) return stop(current, ChunkPhase.BLOCKED, ChunkReason.LEGACY_PENDING)
            current = current.copy(phase = ChunkPhase.DONE, updatedAt = clock())
            outbox.save(current)
            outbox.reclaimDone(cursors)
            return current.status()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE)
        }
    }

    private fun stop(manifest: ChunkManifest, phase: ChunkPhase, reason: ChunkReason): ChunkStatus {
        val stopped = manifest.copy(phase = phase, reason = reason, updatedAt = clock())
        return try { outbox.save(stopped); stopped.status() } catch (_: Exception) { ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE) }
    }
}

internal val receiverBatchId = Regex("[a-zA-Z0-9_-]{1,128}")

internal fun validChunkAck(body: String, batchId: String): Boolean = try {
    val value = EcmaJson.parse(body) as? Map<*, *>
    val accepted = value?.get("accepted")
    value != null && value.keys.all { it in setOf("ok", "batchId", "accepted", "duplicate") } && value["ok"] == true && value["batchId"] == batchId &&
        (!value.containsKey("duplicate") || value["duplicate"] is Boolean) &&
        (!value.containsKey("accepted") || (accepted is Double && accepted.isFinite() && accepted >= 0 && accepted % 1.0 == 0.0))
} catch (_: Exception) { false }
