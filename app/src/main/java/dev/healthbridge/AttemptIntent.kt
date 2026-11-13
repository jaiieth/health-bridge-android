package dev.healthbridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.json.JSONArray
import org.json.JSONObject

internal const val ATTEMPT_INTENT_NAME = "attempt-intent.json"
internal const val ATTEMPT_WRITER_PROTOCOL = 1

// Covers reservation, ownership, and at most 100 child filename/length descriptors.
internal fun attemptIntentBudget(manifestBytes: Long): Long = Math.addExact(manifestBytes, 32_768L)

internal data class AttemptChild(
    val index: Int,
    val batchId: String,
    val count: Int,
    val finalName: String,
    val tempName: String,
    val bodyBytes: Long,
    val bodySha: String,
)

internal data class AttemptIntent(
    val generation: String,
    val attemptId: String,
    val writerProtocol: Int,
    val createdAt: Long,
    val endpointHash: String,
    val fingerprint: String,
    val reservationFields: List<String>,
    val reservationBytes: Long,
    val reservationSha: String,
    val recordIds: List<String>,
    val recordHashes: List<String>,
    val targets: Map<String, String>,
    val children: List<AttemptChild>,
) {
    fun matches(manifest: ChunkManifest): Boolean = generation == manifest.generation && createdAt == manifest.createdAt &&
        endpointHash == manifest.endpointHash && fingerprint == manifest.fingerprint && recordIds == manifest.recordIds &&
        recordHashes == manifest.recordHashes && targets == manifest.targets &&
        children.map { listOf(it.index, it.batchId, it.count, it.bodySha) } ==
        manifest.children.map { listOf(it.index, it.batchId, it.count, it.bodySha) }

    fun encode(): ByteArray = JSONObject().apply {
        put("format", "health-bridge-attempt-v1")
        put("generation", generation)
        put("attemptId", attemptId)
        put("writerProtocol", writerProtocol)
        put("createdAt", createdAt)
        put("endpointHash", endpointHash)
        put("fingerprint", fingerprint)
        put("reservationFields", JSONArray(reservationFields))
        put("reservationBytes", reservationBytes)
        put("reservationSha", reservationSha)
        put("recordIds", JSONArray(recordIds))
        put("recordHashes", JSONArray(recordHashes))
        put("targets", JSONObject(targets))
        put("children", JSONArray().apply { children.forEach { child -> put(JSONObject().apply {
            put("index", child.index)
            put("batchId", child.batchId)
            put("count", child.count)
            put("finalName", child.finalName)
            put("tempName", child.tempName)
            put("bodyBytes", child.bodyBytes)
            put("bodySha", child.bodySha)
        }) } })
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun planned(reservation: ChunkOutbox.Reservation, manifest: ChunkManifest, lengths: List<Long>, attemptId: String): AttemptIntent {
            require(reservation.generation == manifest.generation && lengths.size == manifest.children.size)
            require(lengths.sum() == reservation.bodies)
            val bytes = reservation.bytes()
            return AttemptIntent(manifest.generation, attemptId, ATTEMPT_WRITER_PROTOCOL, manifest.createdAt,
                manifest.endpointHash, manifest.fingerprint, reservation.fields(), bytes.size.toLong(), sha256(bytes),
                manifest.recordIds.toList(), manifest.recordHashes.toList(), manifest.targets.toSortedMap(),
                manifest.children.mapIndexed { index, child ->
                    require(child.index == index)
                    AttemptChild(index, child.batchId, child.count, "child-$index.json", "child-$index.json.tmp", lengths[index], child.bodySha)
                }).also { it.validate() }
        }

        fun decode(bytes: ByteArray): AttemptIntent {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            EcmaJson.parse(text) // Reject duplicate keys and non-JSON syntax before JSONObject coercions.
            val j = JSONObject(text)
            j.exactKeys("format", "generation", "attemptId", "writerProtocol", "createdAt", "endpointHash", "fingerprint",
                "reservationFields", "reservationBytes", "reservationSha", "recordIds", "recordHashes", "targets", "children")
            require(j.string("format") == "health-bridge-attempt-v1")
            val targets = j.getJSONObject("targets").let { o -> o.keys().asSequence().associateWith { o.string(it) } }
            val children = j.getJSONArray("children").let { a -> List(a.length()) { index -> a.getJSONObject(index).let { c ->
                c.exactKeys("index", "batchId", "count", "finalName", "tempName", "bodyBytes", "bodySha")
                AttemptChild(c.integer("index").toIntExact(), c.string("batchId"), c.integer("count").toIntExact(),
                    c.string("finalName"), c.string("tempName"), c.integer("bodyBytes"), c.string("bodySha"))
            } } }
            return AttemptIntent(j.string("generation"), j.string("attemptId"), j.integer("writerProtocol").toIntExact(),
                j.integer("createdAt"), j.string("endpointHash"), j.string("fingerprint"), j.strings("reservationFields"),
                j.integer("reservationBytes"), j.string("reservationSha"), j.strings("recordIds"), j.strings("recordHashes"),
                targets, children).also { it.validate() }
        }
    }

    private fun validate() {
        val digest = Regex("[0-9a-f]{64}")
        require(generation.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        require(attemptId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(writerProtocol == ATTEMPT_WRITER_PROTOCOL && createdAt >= 0)
        require(digest.matches(endpointHash) && digest.matches(fingerprint) && digest.matches(reservationSha))
        require(reservationFields.size == 8 && reservationFields[0] == "2" && reservationFields[1] == generation)
        require(reservationFields.drop(2).all { it.matches(Regex("0|[1-9][0-9]*")) })
        val bytes = reservationFields.joinToString("\n").toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() == reservationBytes && sha256(bytes) == reservationSha)
        require(recordIds.size == recordHashes.size && recordHashes.all(digest::matches))
        require(fingerprint == parentFingerprint(endpointHash, recordIds, recordHashes, targets))
        require(children.size <= 100 && children.map { it.index } == children.indices.toList())
        require(children.map { it.batchId }.distinct().size == children.size)
        require(children.isEmpty() || children.sumOf { it.count } == recordIds.size)
        require(children.all { receiverBatchId.matches(it.batchId) && it.count >= 0 && it.bodyBytes in 1..524_288 &&
            digest.matches(it.bodySha) && it.finalName == "child-${it.index}.json" && it.tempName == it.finalName + ".tmp" })
    }
}

internal fun existsNoFollow(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

internal fun requireRegular(file: File) {
    require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && Files.isReadable(file.toPath()))
}

private fun JSONObject.exactKeys(vararg keys: String) = require(keys().asSequence().toSet() == keys.toSet())
private fun JSONObject.string(key: String): String = (get(key) as? String) ?: error("INTENT_STRING_REQUIRED")
private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long)
    return (value as Number).toLong()
}
private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { array ->
    List(array.length()) { (array.get(it) as? String) ?: error("INTENT_STRING_REQUIRED") }
}
private fun Long.toIntExact(): Int = Math.toIntExact(this)
