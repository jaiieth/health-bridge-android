package dev.healthbridge

import java.security.MessageDigest
import java.time.Instant

internal data class FreshGenerationLimits(
    val records: Int = 100_000,
    val children: Int = 100,
    val manifestBytes: Long = 8L * 1024 * 1024,
    // UTF-16 content is at most twice its UTF-8 bytes; escaped-byte admission is stricter.
    val metadataUtf16Bytes: Long = 2 * (manifestBytes - 106_496).coerceAtLeast(0),
    val queueBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(records in 1..100_000 && children in 1..100)
        require(manifestBytes in 1..8L * 1024 * 1024)
        require(metadataUtf16Bytes in 0..2 * (manifestBytes - 106_496).coerceAtLeast(0))
        require(queueBytes in 0..64L * 1024 * 1024)
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
 * is checked BEFORE insertion at <=16564224 UTF-16 bytes. A rejected ID, if retained,
 * is separately <=2R bytes. Child IDs are <=128 chars and SHA strings are 64 chars.
 * Each of the two immutable intent record-list snapshots adds <=100000 references,
 * sharing the existing string content. Planner list backing capacity is <150001
 * references per list; child/set capacity is <=256.
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
 * Intent encoding has a separate reservation-derived bound of manifest bytes +32768.
 * Its JSON tree, encoded text, and UTF-8 bytes are bounded metadata allocations.
 * Manifest encoding is guarded before allocating JSON objects or encoded text at
 * <=8388608 UTF-8 bytes. Raw ID/hash content is <=16564224 UTF-16 bytes by default;
 * escaped strings and JSON overhead are counted separately before insertion.
 * A read-only planning pass counts bodies and manifest metadata before admission.
 * Replay verifies child hashes and the complete manifest before publication, retaining
 * the same bounded payload buffer. Source changes fail closed. Reservation includes
 * all retained files, body bytes, two bounded manifests, two pointer copies and
 * intent metadata and its atomic-copy cost, and reservation/marker metadata. It stays fixed across resume.
 * Refusal never deletes previously retained evidence or commits cursors. These are enforced content
 * bounds, not JVM heap/RSS or allocator claims.
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
    private data class PlannedGeneration(val manifest: ChunkManifest, val bodyLengths: List<Long>) {
        val bodyBytes: Long get() = bodyLengths.sum()
    }

    suspend fun stage(input: FreshChunkInput, endpointHash: String): ChunkManifest {
        outbox.backpressure(limit = generationLimits.queueBytes)?.let { throw ChunkBudgetExceeded(it) }
        val generation = newId()
        val ids = mutableListOf<String>()
        val now = clock()
        val planned = build(input, endpointHash, generation, now, { newId().also(ids::add) })
        val manifest = planned.manifest
        if (manifest.reason == ChunkReason.MANIFEST_LIMIT) {
            outbox.recordBackpressure(manifest.reason, generation, Long.MAX_VALUE)
            return manifest
        }
        val reservation = try {
            outbox.reserve(manifest, planned.bodyBytes, generationLimits.queueBytes)
        } catch (e: ChunkBudgetExceeded) {
            return manifest.copy(phase = ChunkPhase.BLOCKED, children = emptyList(), reason = e.reason)
        }
        val attempt = outbox.beginReserved(reservation, manifest, planned.bodyLengths)
        try {
            if (manifest.phase != ChunkPhase.BLOCKED) {
                var idIndex = 0
                val replay = build(input, endpointHash, generation, now, { ids[idIndex++] }, manifest) { index, bytes, size ->
                    val child = manifest.children.getOrNull(index) ?: error("SOURCE_CHANGED")
                    val hash = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, size) }.digest()
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(child.bodySha == hash) { "SOURCE_CHANGED" }
                    outbox.stageReservedBody(reservation, index, bytes, size)
                }
                check(replay == planned) { "SOURCE_CHANGED" }
            }
            outbox.publishReserved(reservation, manifest)
            return manifest
        } catch (e: Exception) {
            outbox.rollback(attempt)
            throw e
        }
    }

    private suspend fun build(
        input: FreshChunkInput, endpointHash: String, generation: String, now: Long,
        childId: () -> String,
        expected: ChunkManifest? = null,
        write: (Int, ByteArray, Int) -> Unit = { _, _, _ -> },
    ): PlannedGeneration {
        require(input.records.isEmpty()) { "STREAM_WITH_LIST" }
        val producer = checkNotNull(input.producer)
        require(limits.wireBytes <= FRESH_RECORD_BYTES && limits.count <= 5000 && limits.mappedBytes <= 900_000)
        require(input.targetCursors.size <= 6 && input.targetCursors.keys.all { it in freshTypeNames })
        input.targetCursors.values.forEach { require(it.length <= 40); Instant.parse(it) }
        val targets = input.targetCursors.toSortedMap()
        val collectedIds = if (expected == null) mutableListOf<String>() else null
        val collectedHashes = if (expected == null) mutableListOf<String>() else null
        val recordIds = expected?.recordIds ?: checkNotNull(collectedIds)
        val recordHashes = expected?.recordHashes ?: checkNotNull(collectedHashes)
        var seen = 0
        val children = mutableListOf<ChunkChild>()
        val usedIds = mutableSetOf<String>()
        val buffer = ByteArray(limits.wireBytes.toInt())
        var batchId = ""
        var size = 0
        var count = 0
        var mapped = 2L
        var metadataUtf16Bytes = 0L
        var manifestBound = manifestBaseBytes(generation, endpointHash, targets) + generationLimits.children * 1024L
        val bodyLengths = mutableListOf<Long>()
        var publish = true
        var limitReason: ChunkReason? = null
        val hashPlaceholder = "0".repeat(64)

        fun refuse(record: ChunkRecord?, wire: Long, mappedBytes: Long?, cause: ChunkBlockCause = ChunkBlockCause.OVERSIZED): Nothing {
            throw Refused(ChunkPlan.Blocked(record?.recordId?.takeIf { it.length <= FRESH_RECORD_BYTES }, wire, mappedBytes, if (record == null) 0 else 1, cause))
        }
        fun startChild() {
            batchId = childId()
            require(receiverBatchId.matches(batchId) && usedIds.add(batchId)) { "INVALID_BATCH_ID" }
            val prefix = ChunkPartitioner.prefix(batchId).toByteArray(Charsets.UTF_8)
            if (prefix.size <= buffer.size) prefix.copyInto(buffer)
            size = prefix.size
            count = 0
            mapped = 2
        }
        fun stageChild() {
            if (size + 2L > limits.wireBytes || mapped > limits.mappedBytes) refuse(null, size + 2L, mapped, ChunkBlockCause.ENVELOPE_LIMIT)
            buffer[size++] = ']'.code.toByte()
            buffer[size++] = '}'.code.toByte()
            val digest = MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, size) }.digest()
            val hash = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
            write(children.size, buffer, size)
            bodyLengths += size.toLong()
            children += ChunkChild(children.size, batchId, hash, count)
            retention.childCount = children.size
        }
        var blocked: ChunkPlan.Blocked? = null
        try {
            if (manifestBound > generationLimits.manifestBytes) {
                publish = false
                refuse(null, 0, null)
            }
            startChild()
            producer.produce { record ->
                val wireSize = boundedUtf8Size(record.json)
                if (wireSize > FRESH_RECORD_BYTES || record.recordId.length > FRESH_RECORD_BYTES) refuse(record, wireSize, null)
                val contribution = manifestRecordBytes(record.recordId, hashPlaceholder)
                if (contribution > generationLimits.manifestBytes - manifestBound) {
                    publish = false
                    refuse(record, wireSize, null)
                }
                val metadataBytes = 2L * (record.recordId.length + 64L)
                if (seen == generationLimits.records || metadataBytes > generationLimits.metadataUtf16Bytes - metadataUtf16Bytes) {
                    limitReason = ChunkReason.GENERATION_RECORD_LIMIT
                    refuse(record, wireSize, null)
                }
                val encoded = record.json.toByteArray(Charsets.UTF_8)
                retention.sample(buffer.size, count, record, encoded.size)
                val recordHash = sha256(encoded)
                if (expected == null) {
                    checkNotNull(collectedIds).add(record.recordId)
                    checkNotNull(collectedHashes).add(recordHash)
                } else check(recordIds.getOrNull(seen) == record.recordId && recordHashes.getOrNull(seen) == recordHash) { "SOURCE_CHANGED" }
                seen++
                manifestBound += contribution
                metadataUtf16Bytes += metadataBytes
                retention.metadataUtf16Bytes = metadataUtf16Bytes
                retention.recordCount = seen
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
                    if (children.size + 1 == generationLimits.children) {
                        limitReason = ChunkReason.GENERATION_CHILD_LIMIT
                        refuse(record, wireSize, 2 + mappedSize)
                    }
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
            if (expected != null) check(seen == expected.recordIds.size) { "SOURCE_CHANGED" }
            stageChild()
        } catch (e: Refused) {
            blocked = e.blocked
        } catch (e: OversizedFreshRecord) {
            blocked = ChunkPlan.Blocked(e.recordId?.takeIf { it.length <= FRESH_RECORD_BYTES }, e.wireBytes, null, 1)
        }
        val manifest = ChunkManifest(generation, parentFingerprint(endpointHash, recordIds, recordHashes, targets), endpointHash,
            recordIds, recordHashes, targets, now, now, if (blocked == null) ChunkPhase.STAGE else ChunkPhase.BLOCKED,
            if (blocked == null) children else emptyList(), limitReason ?: when (blocked?.cause) {
                null -> ChunkReason.NONE
                ChunkBlockCause.OVERSIZED -> ChunkReason.OVERSIZED
                ChunkBlockCause.INVALID_TIMESTAMP -> ChunkReason.INVALID_TIMESTAMP
                ChunkBlockCause.ENVELOPE_LIMIT -> ChunkReason.ENVELOPE_LIMIT
            }, blocked?.recordId, blocked?.wireBytes, blocked?.mappedBytes)
        val encodedBound = manifestEncodedBytes(manifest)
        if (!publish || encodedBound > generationLimits.manifestBytes) {
            return PlannedGeneration(manifest.copy(phase = ChunkPhase.BLOCKED, children = emptyList(), reason = ChunkReason.MANIFEST_LIMIT), emptyList())
        }
        return PlannedGeneration(manifest, if (blocked == null) bodyLengths else emptyList())
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
