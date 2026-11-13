package dev.healthbridge

import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal data class ChunkRecord(val recordId: String, val json: String)
internal data class ChunkLimits(val wireBytes: Long = 524_288, val count: Int = 5_000, val mappedBytes: Long = 900_000) {
    init { require(wireBytes > 0 && count > 0 && mappedBytes > 0) }
}

/** Models the reference receiver's ECMAScript JSON.stringify representation of one mapped record.
 * The storage budget includes the mapped array's UTF-8 bytes, escaping, and punctuation.
 */
internal fun interface VerifiedReceiverMapping {
    fun mappedRecordJson(record: ChunkRecord): String
}

internal data class PlannedChild(val batchId: String, val records: List<ChunkRecord>, val body: ByteArray, val mappedBytes: Long)
internal enum class ChunkBlockCause { OVERSIZED, INVALID_TIMESTAMP }
internal sealed interface ChunkPlan {
    data class Ready(val children: List<PlannedChild>) : ChunkPlan
    data class Blocked(val recordId: String?, val wireBytes: Long, val mappedBytes: Long?, val count: Int, val cause: ChunkBlockCause = ChunkBlockCause.OVERSIZED) : ChunkPlan
}

internal object ChunkPartitioner {
    fun envelope(batchId: String, records: List<ChunkRecord>): ByteArray =
        (prefix(batchId) + records.joinToString(",") { it.json } + "]}").toByteArray(Charsets.UTF_8)

    internal fun prefix(batchId: String) = "{\"schemaVersion\":1,\"batchId\":" + EcmaJson.quote(batchId) + ",\"records\":["

    fun partition(records: List<ChunkRecord>, childIds: List<String>, mapping: VerifiedReceiverMapping, limits: ChunkLimits): ChunkPlan {
        require(childIds.size >= records.size.coerceAtLeast(1))
        require(childIds.distinct().size == childIds.size && childIds.none { it.isBlank() })
        val mappedSizes = records.map { record ->
            try { mapping.mappedRecordJson(record).toByteArray(Charsets.UTF_8).size.toLong() }
            catch (_: InvalidRecordTimestamp) {
                return ChunkPlan.Blocked(record.recordId, envelope(childIds.first(), listOf(record)).size.toLong(), null, 1, ChunkBlockCause.INVALID_TIMESTAMP)
            }
        }
        val recordSizes = records.map { it.json.toByteArray(Charsets.UTF_8).size.toLong() }
        val children = mutableListOf<PlannedChild>()
        var start = 0
        do {
            val id = childIds[children.size]
            val overhead = envelope(id, emptyList()).size.toLong()
            var end = start
            var wire = overhead
            var mapped = 2L
            while (end < records.size) {
                val comma = if (end == start) 0 else 1
                val nextWire = wire + recordSizes[end] + comma
                val nextMapped = mapped + mappedSizes[end] + comma
                if (end - start + 1 > limits.count || nextWire > limits.wireBytes || nextMapped > limits.mappedBytes) break
                wire = nextWire
                mapped = nextMapped
                end++
            }
            if (end == start && start < records.size) {
                return ChunkPlan.Blocked(records[start].recordId, overhead + recordSizes[start], 2 + mappedSizes[start], 1)
            }
            if (wire > limits.wireBytes || mapped > limits.mappedBytes) return ChunkPlan.Blocked(null, wire, mapped, 0)
            val slice = records.subList(start, end).toList()
            children += PlannedChild(id, slice, envelope(id, slice), mapped)
            start = end
        } while (start < records.size)
        return ChunkPlan.Ready(children)
    }
}
