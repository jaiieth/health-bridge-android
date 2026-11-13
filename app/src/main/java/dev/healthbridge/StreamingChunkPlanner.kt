package dev.healthbridge

import java.security.MessageDigest
import java.time.Instant

internal data class FreshGenerationLimits(
    val records: Int = 100_000,
    val children: Int = 100,
    val metadataUtf16Bytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(records in 1..100_000 && children in 1..100)
        require(metadataUtf16Bytes in 0..16L * 1024 * 1024)
    }
}

internal class StreamingRetention {
    var peakPayloadBytes = 0L
        private set
    var peakRecords = 0
        private set
    var peakIncomingRecords = 0
        private set
    var metadataUtf16Bytes = 0L
        internal set
    var recordCount = 0
        internal set
    var childCount = 0
        internal set
    internal fun sample(childCapacity: Int, childRecords: Int, incoming: ChunkRecord?, encodedBytes: Int) {
        val payload = childCapacity.toLong() + (incoming?.json?.length ?: 0) * 2L + encodedBytes
        peakPayloadBytes = maxOf(peakPayloadBytes, payload)
        peakRecords = maxOf(peakRecords, childRecords + if (incoming == null) 0 else 1)
        peakIncomingRecords = maxOf(peakIncomingRecords, if (incoming == null) 0 else 1)
    }
}

/**
 * Production retained payload is one W=524288 byte child array, one incoming JSON string
 * of <=R=524288 UTF-8 bytes / <=R UTF-16 chars, and its <=R byte encoding. Thus the
 * planner retains <=W+3R=2097152 payload bytes, at most 5000 buffered records plus one
 * incoming record, and no disk-body list. The source serializer adds one fixed R-byte
 * array while producing the string, giving <=W+4R=2621440 bytes conservatively.
 * UTF-8 conversion additionally permits <=4R=2097152 temporary codec buffer bytes.
 *
 * Metadata has two lists of <=100000 entries, <=100 child descriptors and batch IDs,
 * and six type/timestamp targets of <=40 chars each. Record ID + hash string content
 * is checked BEFORE insertion at <=16777216 UTF-16 bytes. A rejected ID, if retained,
 * is separately <=2R bytes. Child IDs are <=128 chars and SHA strings are 64 chars.
 * List backing capacity is <150001 references per list; child/set capacity is <=256.
 *
 * Production mapping scratch parses only the bounded incoming record. It retains
 * <=R JSON values/containers, <=R map entries and <=R logical list entries, <=R
 * decoded string chars, and <=129 parser/visitor frames. List backing arrays use
 * <=10R reference slots and map tables <=4R. Active string-builder capacity is
 * <=2R+2 chars. Numeric-key ordering adds <=6R+1290 reference slots, <=R pairs and
 * <=R boxed indices across active frames. No normalized-payload/mapped string is
 * allocated. Primitive conversion allows one <=R-char numeric token and a 1 MiB
 * scratch allowance for <=100 decimal temporaries of <=1076 digits / <=128 int
 * limbs each. This excludes arbitrary injected test mappings.
 *
 * Manifest encoding still uses the existing JSON format. With M=16777216 metadata
 * bytes plus a separate <=R-char rejected ID, its escaped text is bounded by
 * E=6*(M/2+R)+1024*100+4096=53583872 chars/UTF-8 bytes.
 * Encoding scratch conservatively permits 8E=428670976 string/buffer bytes, <=200000
 * JSON array entries, <=100 child objects and <=1100 child fields. Fingerprinting
 * hashes one bounded ID/hash at a time with <=3R=1572864 UTF-8 bytes, not the
 * concatenated window. These are
 * content/capacity and object-count bounds, NOT a JVM heap/RSS or allocator claim.
 * SDK record graphs/pages, page tokens and sourceName lookup allocations are outside
 * this app-owned serialization/planning bound and remain explicitly unverified.
 */
internal class StreamingChunkPlanner(
    private val outbox: ChunkOutbox,
    private val mapping: VerifiedReceiverMapping,
    private val limits: ChunkLimits,
    private val newId: () -> String,
    private val clock: () -> Long,
    private val generationLimits: FreshGenerationLimits = FreshGenerationLimits(),
    private val retention: StreamingRetention = StreamingRetention(),
) {
    private class Refused(val blocked: ChunkPlan.Blocked) : RuntimeException()

    suspend fun stage(input: FreshChunkInput, endpointHash: String): ChunkManifest {
        require(input.records.isEmpty()) { "STREAM_WITH_LIST" }
        val producer = checkNotNull(input.producer)
        require(limits.wireBytes <= FRESH_RECORD_BYTES && limits.count <= 5000 && limits.mappedBytes <= 900_000)
        require(input.targetCursors.size <= 6 && input.targetCursors.keys.all { it in freshTypeNames })
        input.targetCursors.values.forEach { require(it.length <= 40); Instant.parse(it) }
        val targets = input.targetCursors.toSortedMap()
        val generation = newId()
        outbox.beginStreaming(generation)
        val recordIds = mutableListOf<String>()
        val recordHashes = mutableListOf<String>()
        val children = mutableListOf<ChunkChild>()
        val usedIds = mutableSetOf<String>()
        val buffer = ByteArray(limits.wireBytes.toInt())
        var batchId = ""
        var size = 0
        var count = 0
        var mapped = 2L
        var metadataUtf16Bytes = 0L

        fun refuse(record: ChunkRecord?, wire: Long, mappedBytes: Long?, cause: ChunkBlockCause = ChunkBlockCause.OVERSIZED): Nothing {
            throw Refused(ChunkPlan.Blocked(record?.recordId?.takeIf { it.length <= FRESH_RECORD_BYTES }, wire, mappedBytes, if (record == null) 0 else 1, cause))
        }
        fun startChild() {
            batchId = newId()
            require(receiverBatchId.matches(batchId) && usedIds.add(batchId)) { "INVALID_BATCH_ID" }
            val prefix = ChunkPartitioner.prefix(batchId).toByteArray(Charsets.UTF_8)
            if (prefix.size <= buffer.size) prefix.copyInto(buffer)
            size = prefix.size
            count = 0
            mapped = 2
        }
        fun stageChild() {
            if (size + 2L > limits.wireBytes || mapped > limits.mappedBytes) refuse(null, size + 2L, mapped)
            buffer[size++] = ']'.code.toByte()
            buffer[size++] = '}'.code.toByte()
            val digest = MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, size) }.digest()
            val hash = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
            outbox.stageBody(generation, children.size, buffer, size)
            children += ChunkChild(children.size, batchId, hash, count)
            retention.childCount = children.size
        }
        var blocked: ChunkPlan.Blocked? = null
        try {
            startChild()
            producer.produce { record ->
                val wireSize = boundedUtf8Size(record.json)
                if (wireSize > FRESH_RECORD_BYTES || record.recordId.length > FRESH_RECORD_BYTES) refuse(record, wireSize, null)
                val metadataBytes = 2L * (record.recordId.length + 64L)
                if (recordIds.size == generationLimits.records || metadataBytes > generationLimits.metadataUtf16Bytes - metadataUtf16Bytes) {
                    refuse(record, wireSize, null)
                }
                val encoded = record.json.toByteArray(Charsets.UTF_8)
                retention.sample(buffer.size, count, record, encoded.size)
                recordIds += record.recordId
                recordHashes += sha256(encoded)
                metadataUtf16Bytes += metadataBytes
                retention.metadataUtf16Bytes = metadataUtf16Bytes
                retention.recordCount = recordIds.size
                val mappedSize = try {
                    // Identity preserves custom timestamp contracts on injected mapping instances.
                    if (mapping === ChunkContracts.verifiedMapping) productionMappedRecordBytes(record)
                    else mapping.mappedRecordJson(record).toByteArray(Charsets.UTF_8).size.toLong()
                } catch (_: InvalidRecordTimestamp) {
                    refuse(record, ChunkPartitioner.prefix(batchId).toByteArray(Charsets.UTF_8).size + 2L + wireSize, null, ChunkBlockCause.INVALID_TIMESTAMP)
                }
                val comma = if (count == 0) 0 else 1
                if (count + 1 > limits.count || size + 2L + encoded.size + comma > limits.wireBytes || mapped + mappedSize + comma > limits.mappedBytes) {
                    if (count == 0) refuse(record, size + 2L + encoded.size, 2 + mappedSize)
                    if (children.size + 1 == generationLimits.children) refuse(record, wireSize, 2 + mappedSize)
                    stageChild()
                    startChild()
                    if (size + 2L + encoded.size > limits.wireBytes || 2 + mappedSize > limits.mappedBytes) {
                        refuse(record, size + 2L + encoded.size, 2 + mappedSize)
                    }
                }
                if (count > 0) buffer[size++] = ','.code.toByte()
                encoded.copyInto(buffer, size)
                size += encoded.size
                count++
                mapped += mappedSize + if (count == 1) 0 else 1
                retention.sample(buffer.size, count, null, 0)
            }
            stageChild()
        } catch (e: Refused) {
            blocked = e.blocked
        } catch (e: OversizedFreshRecord) {
            blocked = ChunkPlan.Blocked(e.recordId?.takeIf { it.length <= FRESH_RECORD_BYTES }, e.wireBytes, null, 1)
        }
        val now = clock()
        val manifest = ChunkManifest(generation, parentFingerprint(endpointHash, recordIds, recordHashes, targets), endpointHash,
            recordIds, recordHashes, targets, now, now, if (blocked == null) ChunkPhase.STAGE else ChunkPhase.BLOCKED,
            if (blocked == null) children else emptyList(), when (blocked?.cause) {
                null -> ChunkReason.NONE
                ChunkBlockCause.OVERSIZED -> ChunkReason.OVERSIZED
                ChunkBlockCause.INVALID_TIMESTAMP -> ChunkReason.INVALID_TIMESTAMP
            }, blocked?.recordId, blocked?.wireBytes, blocked?.mappedBytes)
        outbox.publishStreaming(manifest)
        return manifest
    }
}

private fun boundedUtf8Size(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        bytes += when {
            c.code < 128 -> 1
            c.code < 2048 -> 2
            c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
            c.isSurrogate() -> 1
            else -> 3
        }
        if (bytes > FRESH_RECORD_BYTES) return bytes
    }
    return bytes
}
