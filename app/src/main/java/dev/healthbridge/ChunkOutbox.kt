package dev.healthbridge

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal const val STAGE_HEADROOM_BYTES = 1_048_576L
internal const val OUTBOX_HARD_QUOTA_BYTES = 64L * 1024 * 1024
internal const val OUTBOX_CONTROL_QUOTA_BYTES = 64L * 1024
private const val CONTROL_RECORD_BYTES = 2048

internal class ChunkBudgetExceeded(val reason: ChunkReason) : RuntimeException()

internal enum class ChunkPhase { PLAN, STAGE, SEND, ACK, COMMIT, DONE, PAUSED, BLOCKED }
internal enum class ChunkReason { NONE, FLAG_OFF, LEGACY_PENDING, INVALID_TIMESTAMP, UNKNOWN_ACCEPTANCE, INVALID_ACK, HTTP_400, HTTP_409, HTTP_413, HTTP_BLOCKED, HTTP_RETRY, IO_FAILURE, CORRUPT_OUTBOX, ENDPOINT_CHANGED, OVERSIZED, OUTBOX_QUOTA, MANIFEST_LIMIT, GENERATION_RECORD_LIMIT, GENERATION_CHILD_LIMIT, ENVELOPE_LIMIT }
internal data class ChunkStatus(val phase: ChunkPhase, val index: Int = 0, val total: Int = 0, val lastAcknowledgedId: String? = null, val batchId: String? = null, val reason: ChunkReason = ChunkReason.NONE)
internal data class ChunkChild(val index: Int, val batchId: String, val bodySha: String, val count: Int, val acked: Boolean = false, val sentAt: Long? = null, val ackedAt: Long? = null)
internal data class ChunkManifest(
    val generation: String,
    val fingerprint: String,
    val endpointHash: String,
    val recordIds: List<String>,
    val recordHashes: List<String>,
    val targets: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long,
    val phase: ChunkPhase,
    val children: List<ChunkChild>,
    val reason: ChunkReason = ChunkReason.NONE,
    val blockedRecordId: String? = null,
    val blockedWireBytes: Long? = null,
    val blockedMappedBytes: Long? = null,
) {
    fun status(): ChunkStatus {
        val index = children.indexOfFirst { !it.acked }.let { if (it < 0) children.size else it + 1 }
        return ChunkStatus(if (phase == ChunkPhase.SEND) ChunkPhase.PAUSED else phase, index, children.size,
            children.lastOrNull { it.acked }?.batchId, children.getOrNull(index - 1)?.batchId,
            if (phase == ChunkPhase.SEND) ChunkReason.UNKNOWN_ACCEPTANCE else reason)
    }
}

internal fun parentFingerprint(endpointHash: String, records: List<String>, hashes: List<String>, targets: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun part(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
    }
    part("fresh-sync-parent-v1"); part(endpointHash); part(records.size.toString())
    for (index in 0 until minOf(records.size, hashes.size)) { part(records[index]); part(hashes[index]) }
    part(targets.size.toString())
    targets.toSortedMap().forEach { (type, until) -> part(type); part(until) }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal enum class DurableBoundary { TEMP_WRITTEN, FILE_SYNCED, RENAMED, DIRECTORY_SYNCED }
internal class DurableFiles(
    private val syncDirectory: (File) -> Unit = { directory -> FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    private val fault: (DurableBoundary, String) -> Unit = { _, _ -> },
) {
    fun directory(directory: File) {
        if (!directory.exists() && !directory.mkdir()) {
            throw IOException("DIRECTORY_CREATE_FAILED")
        }
        if (!directory.isDirectory) throw IOException("NOT_A_DIRECTORY")
        syncDirectory(checkNotNull(directory.parentFile))
    }

    fun sync(directory: File) = syncDirectory(directory)

    fun write(file: File, bytes: ByteArray, replace: Boolean = true, size: Int = bytes.size) {
        check(replace || !file.exists())
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use {
            it.write(bytes, 0, size)
            it.flush()
            fault(DurableBoundary.TEMP_WRITTEN, file.name)
            it.fd.sync()
            fault(DurableBoundary.FILE_SYNCED, file.name)
        }
        if (replace) Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        fault(DurableBoundary.RENAMED, file.name)
        syncDirectory(checkNotNull(file.parentFile))
        fault(DurableBoundary.DIRECTORY_SYNCED, file.name)
    }
}

/** The caller serializes all access using AppState.mutex. Reads never repair or delete files. */
internal class ChunkOutbox(
    private val root: File,
    private val files: DurableFiles = DurableFiles(),
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private fun generation(value: String): File {
        require(value.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        return File(root, value)
    }
    private fun bodyFile(manifest: ChunkManifest, index: Int) = File(generation(manifest.generation), "child-$index.json")
    fun body(manifest: ChunkManifest, index: Int): ByteArray = bodyFile(manifest, index).readBytes().also {
        require(sha256(it) == manifest.children[index].bodySha)
    }

    fun stage(manifest: ChunkManifest, plan: ChunkPlan.Ready?) {
        val bodies = plan?.children?.sumOf { it.body.size.toLong() } ?: 0L
        val reservation = reserve(manifest, bodies)
        val attempt = beginReserved(reservation)
        try {
            plan?.children?.forEachIndexed { index, child ->
                stageReservedBody(reservation, index, child.body, child.body.size)
            }
            publishReserved(reservation, manifest)
        } catch (e: Exception) { rollback(attempt); throw e }
    }

    fun save(manifest: ChunkManifest) {
        val reservation = File(generation(manifest.generation), "reservation")
        if (reservation.exists()) {
            val fields = reservation.readLines()
            check(manifestEncodedBytes(manifest) <= fields[4].toLong())
            check(retainedBytes() + manifestEncodedBytes(manifest) <= fields[5].toLong())
        }
        files.write(File(generation(manifest.generation), "manifest.json"), encode(manifest).toByteArray(Charsets.UTF_8))
    }

    internal data class Reservation(val generation: String, val retained: Long, val bodies: Long,
        val manifest: Long, val peak: Long, val limit: Long)

    // Payload peak = retained payload + bodies + 3 * manifest bound + 256 reservation bytes.
    // One immutable publication manifest plus current manifest and its replacement temp coexist.
    // New bodies have only a temp OR final name. Controls have a separate fixed 64 KiB budget.
    fun reserve(manifest: ChunkManifest, bodies: Long, limit: Long = OUTBOX_HARD_QUOTA_BYTES): Reservation {
        generation(manifest.generation)
        require(bodies >= 0 && limit in 0..OUTBOX_HARD_QUOTA_BYTES)
        val bound = manifestEncodedBytes(manifest)
        if (bound > FreshGenerationLimits().manifestBytes) {
            recordBackpressure(ChunkReason.MANIFEST_LIMIT, manifest.generation, Long.MAX_VALUE, limit)
            throw ChunkBudgetExceeded(ChunkReason.MANIFEST_LIMIT)
        }
        val retained = retainedBytes()
        val peak = Math.addExact(retained, Math.addExact(bodies, 3 * bound + 256))
        if (peak > limit) {
            recordBackpressure(ChunkReason.OUTBOX_QUOTA, manifest.generation, peak, limit)
            throw ChunkBudgetExceeded(ChunkReason.OUTBOX_QUOTA)
        }
        return Reservation(manifest.generation, retained, bodies, bound, peak, limit)
    }

    fun recordBackpressure(reason: ChunkReason, generation: String, required: Long, limit: Long = OUTBOX_HARD_QUOTA_BYTES) {
        generation(generation)
        val file = File(root, "backpressure")
        if (file.exists()) return
        if (File(root, "backpressure.tmp").exists()) throw IOException("UNPUBLISHED_BACKPRESSURE")
        val bytes = listOf("1", reason.name, generation, required.toString(), limit.toString(), retainedBytes().toString(), FreshGenerationLimits().manifestBytes.toString()).joinToString("\n").toByteArray()
        files.directory(root)
        try { writeControl(file, bytes, replace = false) }
        catch (e: Exception) {
            // A failed first marker write has no committed reference. Do not accumulate temp bytes.
            if (!file.exists()) { Files.deleteIfExists(File(root, "backpressure.tmp").toPath()); files.sync(root) }
            throw e
        }
    }

    fun backpressure(explicitRetry: Boolean = false, limit: Long = OUTBOX_HARD_QUOTA_BYTES, allowRetry: Boolean = true): ChunkReason? {
        val file = File(root, "backpressure")
        if (!file.exists() && File(root, "backpressure.tmp").exists()) return ChunkReason.IO_FAILURE
        if (!file.exists()) {
            if (limit == OUTBOX_HARD_QUOTA_BYTES && retainedBytes() > limit - 8192) {
                if (allowRetry) recordBackpressure(ChunkReason.OUTBOX_QUOTA, "uncollected", retainedBytes() + 8192, limit)
                return ChunkReason.OUTBOX_QUOTA
            }
            return null
        }
        val fields = file.readLines()
        require(fields.size == 7 && fields[0] == "1")
        val reason = ChunkReason.valueOf(fields[1])
        val previous = fields[5].toLong()
        // Only actual release of retained bytes, a changed fixed budget, or explicit retry permits collection.
        if (explicitRetry || fields[4].toLong() != limit || fields[6].toLong() != FreshGenerationLimits().manifestBytes ||
            reason == ChunkReason.OUTBOX_QUOTA && fields[3].toLong() - previous <= limit - retainedBytes()) {
            if (!allowRetry) return reason
            Files.delete(file.toPath())
            files.sync(root)
            return null
        }
        return reason
    }

    fun beginReserved(reservation: Reservation): StagingAttempt {
        check(reservation.peak <= reservation.limit && retainedBytes() == reservation.retained)
        check(controlBytes() + 8192 <= OUTBOX_CONTROL_QUOTA_BYTES)
        preflight(reservation.peak - reservation.retained + STAGE_HEADROOM_BYTES + 8192)
        val dir = generation(reservation.generation)
        if (File(root, "active.tmp").exists()) throw IOException("UNPUBLISHED_ACTIVE_POINTER")
        check(!dir.exists())
        files.directory(root)
        files.directory(dir)
        val attempt = StagingAttempt(this, reservation.generation)
        try {
            files.write(File(dir, "reservation"), listOf("1", reservation.generation, reservation.retained,
                reservation.bodies, reservation.manifest, reservation.peak, reservation.limit).joinToString("\n").toByteArray(), replace = false)
        } catch (e: Exception) { rollback(attempt); throw e }
        return attempt
    }

    fun stageReservedBody(reservation: Reservation, index: Int, bytes: ByteArray, size: Int) {
        require(index in 0 until 100 && size in 0..524_288 && size <= bytes.size)
        val dir = generation(reservation.generation)
        val staged = dir.listFiles()!!.filter { it.name.startsWith("child-") }.sumOf { it.length() }
        check(staged + size <= reservation.bodies)
        preflight(STAGE_HEADROOM_BYTES + size)
        files.write(File(dir, "child-$index.json"), bytes, replace = false, size = size)
    }

    fun publishReserved(reservation: Reservation, manifest: ChunkManifest) {
        check(manifest.generation == reservation.generation && manifestEncodedBytes(manifest) <= reservation.manifest)
        preflight(STAGE_HEADROOM_BYTES + reservation.manifest + reservation.generation.length)
        val bytes = encode(manifest).toByteArray(Charsets.UTF_8)
        files.write(File(generation(manifest.generation), "manifest.json"), bytes)
        files.write(File(generation(manifest.generation), "published.json"), bytes, replace = false)
        val active = File(root, "active")
        val prior = selectedGeneration()
        if (prior != null) {
            val priorManifest = File(generation(prior), "manifest.json")
            val proof = File(root, "active-proof")
            val previousFile = File(root, "previous")
            val previous = when {
                authenticatedPointer(proof) == prior -> proof.readBytes()
                authenticatedPointer(previousFile) == prior -> previousFile.readBytes()
                else -> ("1\n" + prior + "\n" + sha256(priorManifest.readBytes()) + "\nlegacy").toByteArray()
            }
            writeControl(previousFile, previous)
        }
        writeControl(File(root, "active-proof"), ("1\n" + manifest.generation + "\n" + sha256(bytes) + "\npublished").toByteArray())
        writeControl(active, manifest.generation.toByteArray(Charsets.UTF_8))
    }

    fun rollback(attempt: StagingAttempt) {
        require(attempt.owner === this)
        val dir = generation(attempt.generation)
        // Once published, preserve the complete resumable generation, even if its fsync reported failure.
        if (File(root, "active").let { it.exists() && it.readText() == attempt.generation }) return
        val committed = File(dir, "manifest.json")
        if (committed.exists()) {
            val manifest = try { decode(committed.readText()) } catch (_: Exception) { return }
            if (manifest.generation != attempt.generation || manifest.phase !in setOf(ChunkPhase.STAGE, ChunkPhase.BLOCKED) ||
                manifest.children.any { it.acked || it.sentAt != null } || manifest.reason == ChunkReason.UNKNOWN_ACCEPTANCE) return
        }
        val allowed = Regex("child-[0-9]+\\.json(\\.tmp)?|(manifest|published)\\.json(\\.tmp)?|reservation(\\.tmp)?")
        val entries = dir.listFiles() ?: return
        if (entries.any { !it.isFile || !allowed.matches(it.name) }) return
        val proof = File(root, "active-proof")
        if (proof.isFile && proof.readLines().getOrNull(1) == attempt.generation) {
            val previous = File(root, "previous")
            if (previous.exists()) Files.move(previous.toPath(), proof.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.deleteIfExists(proof.toPath())
            files.sync(root)
        }
        entries.forEach { Files.deleteIfExists(it.toPath()); files.sync(dir) }
        Files.deleteIfExists(dir.toPath())
        File(root, "active.tmp").let { if (it.exists() && it.readText() == attempt.generation) Files.delete(it.toPath()) }
        files.sync(root)
    }

    internal class StagingAttempt internal constructor(internal val owner: ChunkOutbox, internal val generation: String)

    private fun isControl(file: File): Boolean = file.parentFile == root &&
        (file.name.removeSuffix(".tmp") in setOf("active", "active-proof", "previous", "backpressure") ||
            file.name.matches(Regex("receipt-[a-zA-Z0-9-]{1,100}\\.json(\\.tmp)?")))

    fun controlBytes(): Long = if (!root.exists()) 0 else root.listFiles()!!.filter { it.isFile && isControl(it) }.sumOf { it.length() }

    private fun writeControl(file: File, bytes: ByteArray, replace: Boolean = true) {
        require(isControl(file) && bytes.size <= CONTROL_RECORD_BYTES)
        if (controlBytes() + bytes.size > OUTBOX_CONTROL_QUOTA_BYTES) throw IOException("CONTROL_BUDGET")
        files.write(file, bytes, replace)
    }

    fun retainedBytes(): Long = if (!root.exists()) 0 else root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile && !isControl(it) }
        .fold(0L) { total, file -> Math.addExact(total, Files.size(file.toPath())) }

    private fun preflight(required: Long) {
        val directory = generateSequence(root.absoluteFile) { it.parentFile }.firstOrNull { it.isDirectory }
            ?: throw IOException("FREE_SPACE_UNAVAILABLE")
        val available = try { usableSpace(directory) }
        catch (e: SecurityException) { throw IOException("FREE_SPACE_UNAVAILABLE", e) }
        if (available < required) throw IOException("INSUFFICIENT_FREE_SPACE")
    }

    private fun authenticatedPointer(record: File): String? {
        if (!record.isFile || record.length() > CONTROL_RECORD_BYTES) return null
        return try {
            val fields = record.readLines()
            require(fields.size == 4 && fields[0] == "1")
            val dir = generation(fields[1])
            val publication = File(dir, if (fields[3] == "published") "published.json" else "manifest.json")
            require(publication.isFile && publication.length() <= FreshGenerationLimits().manifestBytes)
            val bytes = publication.readBytes()
            require(sha256(bytes) == fields[2])
            val initial = decode(bytes.toString(Charsets.UTF_8))
            val current = decode(File(dir, "manifest.json").readText())
            require(initial.generation == current.generation && initial.fingerprint == current.fingerprint)
            require(initial.recordIds == current.recordIds && initial.recordHashes == current.recordHashes && initial.targets == current.targets)
            require(initial.children.map { listOf(it.index, it.batchId, it.bodySha, it.count) } ==
                current.children.map { listOf(it.index, it.batchId, it.bodySha, it.count) })
            fields[1]
        } catch (_: Exception) { null }
    }

    private fun selectedGeneration(): String? {
        val active = File(root, "active")
        val proof = File(root, "active-proof")
        if (!proof.exists()) return if (active.exists()) active.readText(Charsets.UTF_8) else null
        val selected = authenticatedPointer(proof)
        if (active.isFile && active.length() <= 100 && active.readText() == selected) return selected
        val previous = authenticatedPointer(File(root, "previous"))
        if (previous != null) return previous
        if (!active.exists()) return null
        error("NO_VALID_ACTIVE_POINTER")
    }

    fun load(): ChunkManifest? {
        val selected = selectedGeneration() ?: return null
        val dir = generation(selected)
        val manifest = decode(File(dir, "manifest.json").readText(Charsets.UTF_8))
        require(dir.name == manifest.generation)
        require(manifest.fingerprint == parentFingerprint(manifest.endpointHash, manifest.recordIds, manifest.recordHashes, manifest.targets))
        require(manifest.recordIds.size == manifest.recordHashes.size)
        require(manifest.children.map { it.batchId }.distinct().size == manifest.children.size)
        require(manifest.children.all { receiverBatchId.matches(it.batchId) })
        require(manifest.children.map { it.index } == manifest.children.indices.toList())
        require(manifest.children.dropWhile { it.acked }.none { it.acked })
        require(manifest.children.all { !it.acked || (it.sentAt != null && it.ackedAt != null) })
        require(manifest.children.all { it.acked || it.ackedAt == null })
        val remaining = manifest.children.dropWhile { it.acked }
        when (manifest.phase) {
            ChunkPhase.STAGE -> require(manifest.children.none { it.acked || it.sentAt != null })
            ChunkPhase.ACK -> require(manifest.children.any { it.acked } && remaining.all { it.sentAt == null })
            ChunkPhase.SEND -> require(remaining.isNotEmpty() && remaining.first().sentAt != null && remaining.drop(1).all { it.sentAt == null })
            ChunkPhase.PLAN -> error("UNPUBLISHED_PLAN")
            else -> Unit
        }
        if (manifest.phase != ChunkPhase.BLOCKED || manifest.reason !in setOf(ChunkReason.OVERSIZED, ChunkReason.INVALID_TIMESTAMP, ChunkReason.GENERATION_RECORD_LIMIT, ChunkReason.GENERATION_CHILD_LIMIT, ChunkReason.MANIFEST_LIMIT, ChunkReason.ENVELOPE_LIMIT)) {
            require(manifest.children.isNotEmpty())
            require(manifest.children.sumOf { it.count } == manifest.recordIds.size)
        }
        if (manifest.phase == ChunkPhase.COMMIT || manifest.phase == ChunkPhase.DONE) require(manifest.children.all { it.acked })
        var recordIndex = 0
        manifest.children.forEachIndexed { index, child ->
            require(bodyFile(manifest, index).exists()) { "MISSING_REFERENCED_BODY" }
            val json = JSONObject(body(manifest, index).toString(Charsets.UTF_8))
            require(json.length() == 3 && json.getInt("schemaVersion") == 1 && json.getString("batchId") == child.batchId)
            val records = json.getJSONArray("records")
            require(records.length() == child.count)
            repeat(records.length()) {
                require(records.getJSONObject(it).getString("recordId") == manifest.recordIds.getOrNull(recordIndex++))
            }
        }
        if (manifest.children.isNotEmpty()) require(recordIndex == manifest.recordIds.size)
        return manifest
    }

    fun resume(manifest: ChunkManifest) {
        files.sync(checkNotNull(root.parentFile))
        files.sync(root)
        files.sync(generation(manifest.generation))
    }

    fun status(): ChunkStatus? = try { backpressure(allowRetry = false)?.let { ChunkStatus(ChunkPhase.BLOCKED, reason = it) } ?: load()?.status() }
        catch (_: IOException) { ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE) }
        catch (_: Exception) { ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.CORRUPT_OUTBOX) }

    private fun encode(m: ChunkManifest): String {
        if (manifestEncodedBytes(m) > FreshGenerationLimits().manifestBytes) throw ChunkBudgetExceeded(ChunkReason.MANIFEST_LIMIT)
        return JSONObject().apply {
            put("formatVersion", 1)
            put("generation", m.generation)
            put("fingerprint", m.fingerprint)
            put("endpointHash", m.endpointHash)
            put("recordIds", JSONArray(m.recordIds))
            put("recordHashes", JSONArray(m.recordHashes))
            put("recordCount", m.recordIds.size)
            put("targets", JSONObject(m.targets))
            put("createdAt", m.createdAt)
            put("updatedAt", m.updatedAt)
            put("phase", m.phase.name)
            put("reason", m.reason.name)
            put("blockedRecordId", m.blockedRecordId ?: JSONObject.NULL)
            put("blockedWireBytes", m.blockedWireBytes ?: JSONObject.NULL)
            put("blockedMappedBytes", m.blockedMappedBytes ?: JSONObject.NULL)
            put("children", JSONArray().apply { m.children.forEach { c -> put(JSONObject().apply {
                put("index", c.index); put("batchId", c.batchId); put("bodySha", c.bodySha); put("count", c.count)
                put("acked", c.acked); put("sentAt", c.sentAt ?: JSONObject.NULL); put("ackedAt", c.ackedAt ?: JSONObject.NULL)
            }) } })
        }.toString()
    }

    private fun decode(text: String): ChunkManifest {
        val j = JSONObject(text)
        require(j.getInt("formatVersion") == 1)
        fun strings(name: String): List<String> = j.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
        val ids = strings("recordIds")
        require(ids.size == j.getInt("recordCount"))
        val targets = j.getJSONObject("targets").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        val children = j.getJSONArray("children").let { a -> List(a.length()) { index -> a.getJSONObject(index).let { c ->
            ChunkChild(c.getInt("index"), c.getString("batchId"), c.getString("bodySha"), c.getInt("count"), c.getBoolean("acked"),
                if (c.isNull("sentAt")) null else c.getLong("sentAt"), if (c.isNull("ackedAt")) null else c.getLong("ackedAt"))
        } } }
        return ChunkManifest(j.getString("generation"), j.getString("fingerprint"), j.getString("endpointHash"), ids, strings("recordHashes"), targets,
            j.getLong("createdAt"), j.getLong("updatedAt"), ChunkPhase.valueOf(j.getString("phase")), children, ChunkReason.valueOf(j.getString("reason")),
            if (j.isNull("blockedRecordId")) null else j.getString("blockedRecordId"),
            if (j.isNull("blockedWireBytes")) null else j.getLong("blockedWireBytes"),
            if (j.isNull("blockedMappedBytes")) null else j.getLong("blockedMappedBytes"))
    }
}
