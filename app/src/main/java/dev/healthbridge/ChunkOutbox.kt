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
internal const val OUTBOX_CONTROL_RESERVE_BYTES = 64L * 1024
private const val CONTROL_RECORD_BYTES = 2048
internal const val RETAINED_DONE_GENERATIONS = 3
internal const val COMPLETED_RECEIPT_COUNT = 16
internal enum class ReleaseBoundary { TOMBSTONE_DURABLE, BODY_REMOVED, BODIES_SYNCED, METADATA_REMOVED, MANIFEST_REMOVED, GENERATION_REMOVED, PARENT_SYNCED }

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
    private val releaseFault: (ReleaseBoundary, String) -> Unit = { _, _ -> },
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
        check(retainedBytes() + controlBytes() + manifestEncodedBytes(manifest) <= OUTBOX_HARD_QUOTA_BYTES)
        val reservation = File(generation(manifest.generation), "reservation")
        if (reservation.exists()) {
            val fields = reservation.readLines()
            check(manifestEncodedBytes(manifest) <= fields[4].toLong())
            check(retainedBytes() + manifestEncodedBytes(manifest) <= fields[5].toLong() - (if (fields[0] == "2") fields[7].toLong() else 0))
        }
        files.write(File(generation(manifest.generation), "manifest.json"), encode(manifest).toByteArray(Charsets.UTF_8))
    }

    internal data class Reservation(val generation: String, val retained: Long, val bodies: Long,
        val manifest: Long, val peak: Long, val limit: Long, val controls: Long = 0)

    // The control slice is reserved inside the hard total, including atomic control-write copies.
    // Publication, current manifest and a replacement temp can coexist; bodies have one name.
    fun reserve(manifest: ChunkManifest, bodies: Long, limit: Long = OUTBOX_HARD_QUOTA_BYTES): Reservation {
        generation(manifest.generation)
        require(bodies >= 0 && limit in 0..OUTBOX_HARD_QUOTA_BYTES)
        val bound = manifestEncodedBytes(manifest)
        if (bound > FreshGenerationLimits().manifestBytes) {
            recordBackpressure(ChunkReason.MANIFEST_LIMIT, manifest.generation, Long.MAX_VALUE, limit)
            throw ChunkBudgetExceeded(ChunkReason.MANIFEST_LIMIT)
        }
        val retained = retainedBytes()
        val controls = controlBytes()
        val payloadPeak = Math.addExact(retained, Math.addExact(bodies, 3 * bound + 256))
        val peak = Math.addExact(payloadPeak, controls)
        if (peak > limit || payloadPeak > limit - OUTBOX_CONTROL_RESERVE_BYTES) {
            recordBackpressure(ChunkReason.OUTBOX_QUOTA, manifest.generation, peak, limit)
            throw ChunkBudgetExceeded(ChunkReason.OUTBOX_QUOTA)
        }
        return Reservation(manifest.generation, retained, bodies, bound, peak, limit, controls)
    }

    fun recordBackpressure(reason: ChunkReason, generation: String, required: Long, limit: Long = OUTBOX_HARD_QUOTA_BYTES) {
        generation(generation)
        val file = File(root, "backpressure")
        if (file.exists()) return
        if (File(root, "backpressure.tmp").exists()) throw IOException("UNPUBLISHED_BACKPRESSURE")
        val bytes = listOf("2", reason.name, generation, required.toString(), limit.toString(), retainedBytes().toString(), FreshGenerationLimits().manifestBytes.toString(), controlBytes().toString()).joinToString("\n").toByteArray()
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
            if (limit == OUTBOX_HARD_QUOTA_BYTES && (retainedBytes() > limit - OUTBOX_CONTROL_RESERVE_BYTES - 8192 ||
                    retainedBytes() + controlBytes() > limit - 8192)) {
                if (allowRetry) recordBackpressure(ChunkReason.OUTBOX_QUOTA, "uncollected", retainedBytes() + controlBytes() + 8192, limit)
                return ChunkReason.OUTBOX_QUOTA
            }
            return null
        }
        if (!file.isFile || file.length() > CONTROL_RECORD_BYTES || Files.isSymbolicLink(file.toPath())) return ChunkReason.CORRUPT_OUTBOX
        val fields = file.readLines()
        if (fields.size != 8 || fields[0] != "2") return ChunkReason.CORRUPT_OUTBOX
        val reason = try {
            require(file.length() <= CONTROL_RECORD_BYTES && !Files.isSymbolicLink(file.toPath()))
            generation(fields[2])
            require(fields[3].toLong() >= 0 && fields[4].toLong() in 0..OUTBOX_HARD_QUOTA_BYTES &&
                fields[5].toLong() >= 0 && fields[6].toLong() > 0 && fields[7].toLong() >= 0)
            ChunkReason.valueOf(fields[1]).also { require(it in setOf(ChunkReason.OUTBOX_QUOTA, ChunkReason.MANIFEST_LIMIT, ChunkReason.GENERATION_RECORD_LIMIT,
                ChunkReason.GENERATION_CHILD_LIMIT, ChunkReason.ENVELOPE_LIMIT, ChunkReason.OVERSIZED, ChunkReason.INVALID_TIMESTAMP)) }
        } catch (_: Exception) { return ChunkReason.CORRUPT_OUTBOX }
        val retained = retainedBytes()
        val currentControls = controlBytes() - file.length()
        val requiredPayload = if (reason == ChunkReason.OUTBOX_QUOTA)
            Math.addExact(fields[3].toLong() - fields[7].toLong() - fields[5].toLong(), retained) else Long.MAX_VALUE
        val changedBudget = fields[4].toLong() != limit || fields[6].toLong() != FreshGenerationLimits().manifestBytes
        val fits = requiredPayload >= 0 && requiredPayload <= limit - OUTBOX_CONTROL_RESERVE_BYTES && requiredPayload <= limit - currentControls
        val released = retained + currentControls < fields[5].toLong() + fields[7].toLong()
        val retry = if (reason == ChunkReason.OUTBOX_QUOTA) (explicitRetry || released) && fits else explicitRetry
        if (changedBudget || retry) {
            if (!allowRetry) return reason
            Files.delete(file.toPath())
            files.sync(root)
            return null
        }
        return reason
    }

    fun beginReserved(reservation: Reservation): StagingAttempt {
        check(reservation.peak <= reservation.limit && retainedBytes() == reservation.retained && controlBytes() == reservation.controls)
        check(controlBytes() + 8192 <= OUTBOX_CONTROL_RESERVE_BYTES)
        preflight(reservation.peak - reservation.retained + STAGE_HEADROOM_BYTES + 8192)
        val dir = generation(reservation.generation)
        if (File(root, "active.tmp").exists()) throw IOException("UNPUBLISHED_ACTIVE_POINTER")
        check(!dir.exists())
        files.directory(root)
        files.directory(dir)
        val attempt = StagingAttempt(this, reservation.generation)
        try {
            files.write(File(dir, "reservation"), listOf("2", reservation.generation, reservation.retained,
                reservation.bodies, reservation.manifest, reservation.peak, reservation.limit, reservation.controls).joinToString("\n").toByteArray(), replace = false)
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

    private fun readReservation(file: File, id: String): Reservation {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 256)
        return parseReservation(file.readLines(), id)
    }

    private fun parseReservation(fields: List<String>, id: String): Reservation {
        require((fields.size == 7 && fields[0] == "1") || (fields.size == 8 && fields[0] == "2"))
        require(fields[1] == id)
        generation(id)
        require(fields.drop(2).all { it.matches(Regex("0|[1-9][0-9]*")) })
        val r = Reservation(id, fields[2].toLong(), fields[3].toLong(), fields[4].toLong(),
            fields[5].toLong(), fields[6].toLong(), if (fields.size == 8) fields[7].toLong() else 0)
        require(r.retained in 0..OUTBOX_HARD_QUOTA_BYTES && r.bodies in 0..OUTBOX_HARD_QUOTA_BYTES &&
            r.manifest in 1..FreshGenerationLimits().manifestBytes && r.limit in 0..OUTBOX_HARD_QUOTA_BYTES &&
            r.controls in 0..OUTBOX_CONTROL_RESERVE_BYTES)
        require(r.peak == Math.addExact(Math.addExact(r.retained, r.controls), Math.addExact(r.bodies, 3 * r.manifest + 256)))
        require(r.peak <= r.limit)
        return r
    }

    private val stagingName = Regex("child-([0-9]|[1-9][0-9])\\.json(\\.tmp)?|(manifest|published)\\.json(\\.tmp)?|reservation(\\.tmp)?")
    private val digestPattern = Regex("[0-9a-f]{64}")

    private fun stagingEntries(dir: File): List<File> {
        require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
        val entries = dir.listFiles() ?: throw IOException("DIRECTORY_LIST_FAILED")
        require(entries.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && stagingName.matches(it.name) })
        return entries.sortedBy { it.name }
    }

    private fun stagingEvidence(dir: File, expected: Reservation? = null): Reservation {
        val entries = stagingEntries(dir)
        val reservation = File(dir, "reservation")
        val r = if (reservation.exists()) readReservation(reservation, dir.name) else {
            require(expected != null && entries.isEmpty())
            expected
        }
        if (expected != null) require(r == expected)
        entries.filter { it.name == "reservation.tmp" }.forEach { require(readReservation(it, dir.name) == r) }
        require(entries.filter { it.name.startsWith("child-") }.sumOf { it.length() } <= r.bodies)
        require(entries.filter { it.name.startsWith("child-") }.all { it.length() <= 524_288 })
        val manifests = entries.filter { it.name.startsWith("manifest.") || it.name.startsWith("published.") }.map { file ->
            require(file.length() <= r.manifest)
            decode(file.readText()).also { m ->
                require(m.generation == dir.name && m.recordIds.size == m.recordHashes.size)
                require(m.fingerprint == parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes, m.targets))
                require(m.phase in setOf(ChunkPhase.STAGE, ChunkPhase.BLOCKED) && m.reason != ChunkReason.UNKNOWN_ACCEPTANCE)
                require(m.children.none { it.acked || it.sentAt != null || it.ackedAt != null })
                require(m.children.map { it.index } == m.children.indices.toList())
            }
        }
        require(entries.any { it.name == "manifest.json" || it.name == "published.json" })
        require(manifests.map { it.children }.distinct().size <= 1)
        val children = manifests.first().children
        if (expected == null) require(children.all { File(dir, "child-${it.index}.json").isFile })
        entries.filter { it.name.startsWith("child-") }.forEach { file ->
            val index = file.name.substringAfter("child-").substringBefore(".").toInt()
            require(index in children.indices && sha256(file.readBytes()) == children[index].bodySha)
        }
        return r
    }

    private sealed interface StagingRecovery {
        data class Reclaim(val reservation: Reservation) : StagingRecovery
        data object Retain : StagingRecovery
        data object Block : StagingRecovery
    }

    private fun classifyStaging(dir: File): StagingRecovery {
        val metadata = listOf("manifest.json", "published.json", "manifest.json.tmp", "published.json.tmp")
            .map { File(dir, it) }.filter { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        val hasPublishedMetadata = metadata.any { it.name == "manifest.json" || it.name == "published.json" }
        try {
            val entries = stagingEntries(dir)
            require(entries.all { file -> file.length() <= when {
                file.name.startsWith("child-") -> 524_288L
                file.name.startsWith("reservation") -> 256L
                else -> FreshGenerationLimits().manifestBytes
            } })
        } catch (_: Exception) { return StagingRecovery.Block }
        if (!hasPublishedMetadata) return StagingRecovery.Retain
        val safeMetadata = try {
            val manifests = metadata.map { file ->
                require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= FreshGenerationLimits().manifestBytes)
                decode(file.readText()).also { m ->
                    require(m.generation == dir.name && m.recordIds.size == m.recordHashes.size)
                    require(m.fingerprint == parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes, m.targets))
                    require(m.phase in setOf(ChunkPhase.STAGE, ChunkPhase.BLOCKED) &&
                        m.reason != ChunkReason.UNKNOWN_ACCEPTANCE)
                    require(m.children.none { it.acked || it.sentAt != null || it.ackedAt != null })
                    require(m.children.map { it.index } == m.children.indices.toList())
                    stagingEntries(dir).filter { it.name.startsWith("child-") }.forEach { body ->
                        val index = body.name.substringAfter("child-").substringBefore(".").toInt()
                        require(index in m.children.indices && sha256(body.readBytes()) == m.children[index].bodySha)
                    }
                }
            }
            manifests.map { it.children }.distinct().size <= 1
        } catch (_: Exception) { false }
        if (!safeMetadata) return StagingRecovery.Block
        if (receiptFile(dir.name).exists()) return StagingRecovery.Retain
        return try { StagingRecovery.Reclaim(stagingEvidence(dir)) }
        catch (_: Exception) { StagingRecovery.Retain }
    }

    private fun inventoryDigest(dir: File, omit: String? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        for (entry in stagingEntries(dir).filter { it.name != omit }) {
            digest.update((entry.name.length.toString() + ":" + entry.name + ":" + entry.length() + ":").toByteArray(Charsets.UTF_8))
            entry.inputStream().use { input ->
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private data class StagingTombstone(val reservation: Reservation, val evidence: String, val before: String, val after: String)

    private fun tombstoneBytes(t: StagingTombstone): ByteArray = with(t.reservation) {
        listOf("1", generation, retained, bodies, manifest, peak, limit, controls, t.evidence, t.before, t.after)
            .joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun readTombstone(file: File): StagingTombstone {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= CONTROL_RECORD_BYTES)
        val fields = file.readLines()
        require(fields.size == 11 && fields[0] == "1" && fields.takeLast(3).all { digestPattern.matches(it) })
        val r = parseReservation(listOf("2") + fields.subList(1, 8), fields[1])
        return StagingTombstone(r, fields[8], fields[9], fields[10])
    }

    private fun recoveryReferences(): Set<String> {
        val ids = mutableSetOf<String>()
        val proofs = listOf("active-proof", "active-proof.tmp", "previous", "previous.tmp")
        for (name in proofs) {
            val file = File(root, name)
            require(!Files.isSymbolicLink(file.toPath()))
            if (!file.exists()) continue
            if (!file.isFile) throw IOException("POINTER_NOT_A_FILE")
            require(file.length() <= CONTROL_RECORD_BYTES)
            val fields = file.readLines()
            require(fields.size == 4 && fields[3] in setOf("published", "legacy"))
            val id = authenticatedPointer(file) ?: error("UNAUTHENTICATED_POINTER")
            require(generation(id).isDirectory)
            ids += id
        }
        for (name in listOf("active", "active.tmp")) {
            val file = File(root, name)
            require(!Files.isSymbolicLink(file.toPath()))
            if (!file.exists()) continue
            if (!file.isFile) throw IOException("POINTER_NOT_A_FILE")
            require(file.length() in 1..100)
            val id = file.readText()
            val dir = generation(id)
            require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
            if (id !in ids) {
                require(name == "active" && proofs.none { File(root, it).exists() })
                require(load()?.generation == id)
            }
            ids += id
        }
        return ids
    }

    /** Reclaims at most one fully evidenced unpublished attempt. The caller excludes legacy Pending. */
    fun recoverStaging(): ChunkReason? = try {
        if (File(root, "backpressure").exists() && backpressure(allowRetry = false) == ChunkReason.CORRUPT_OUTBOX) ChunkReason.CORRUPT_OUTBOX
        else if (!root.exists()) null else {
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
            val references = try { recoveryReferences() }
                catch (_: IOException) { error("UNREADABLE_POINTER") }
            val tombstone = File(root, "staging-tombstone")
            val temporary = File(root, "staging-tombstone.tmp")
            val recorded = when {
                tombstone.exists() -> readTombstone(tombstone)
                temporary.exists() -> readTombstone(temporary)
                else -> null
            }
            if (temporary.exists()) {
                val pending = readTombstone(temporary)
                require(recorded == null || pending.reservation == recorded.reservation && pending.evidence == recorded.evidence)
            }
            var candidate: File? = null
            var reservation: Reservation? = null
            val entries = root.listFiles() ?: throw IOException("DIRECTORY_LIST_FAILED")
            for (entry in entries) {
                require(!Files.isSymbolicLink(entry.toPath()))
                if (!entry.isDirectory) continue
                if (entry.name in references) continue
                if (receiptFile(entry.name).let { it.exists() && readReceipt(it) != null }) continue
                if (recorded?.reservation?.generation == entry.name) continue
                if (completedManifest(entry) != null) continue
                when (val decision = classifyStaging(entry)) {
                    is StagingRecovery.Reclaim -> if (candidate == null) {
                        candidate = entry
                        reservation = decision.reservation
                    }
                    StagingRecovery.Retain -> Unit
                    StagingRecovery.Block -> error("AMBIGUOUS_DELIVERY")
                }
            }
            if (recorded != null) {
                require(recorded.reservation.generation !in references && !receiptFile(recorded.reservation.generation).exists())
                val dir = generation(recorded.reservation.generation)
                if (!dir.exists()) {
                    files.sync(root)
                    Files.deleteIfExists(temporary.toPath())
                    Files.deleteIfExists(tombstone.toPath())
                    files.sync(root)
                } else {
                    val remaining = stagingEntries(dir)
                    if (remaining.any { it.name == "manifest.json" || it.name == "published.json" }) {
                        stagingEvidence(dir, recorded.reservation)
                    } else {
                        // Only a durable tombstone and its inventory digest can resume after phase metadata removal.
                        require(tombstone.exists() && remaining.all { it.name == "reservation" })
                        remaining.forEach { require(readReservation(it, dir.name) == recorded.reservation) }
                    }
                    val digest = inventoryDigest(dir)
                    require(digest == recorded.before || digest == recorded.after)
                    if (!tombstone.exists()) {
                        // A temp alone has not authorized deletion. Revalidate full attempt evidence first.
                        require(digest == recorded.evidence)
                    }
                    deleteStaging(dir, recorded.reservation, recorded.evidence)
                }
            } else if (candidate != null) {
                deleteStaging(candidate, checkNotNull(reservation), inventoryDigest(candidate))
            }
            null
        }
    } catch (_: IOException) { ChunkReason.IO_FAILURE }
      catch (_: Exception) { ChunkReason.CORRUPT_OUTBOX }

    private fun deleteStaging(dir: File, reservation: Reservation, evidence: String) {
        val tombstone = File(root, "staging-tombstone")
        var digest = inventoryDigest(dir)
        writeControl(tombstone, tombstoneBytes(StagingTombstone(reservation, evidence, digest, digest)))
        releaseFault(ReleaseBoundary.TOMBSTONE_DURABLE, dir.name)
        val entries = stagingEntries(dir)
        val bodies = entries.filter { it.name.startsWith("child-") }
        val metadata = entries.filter { !it.name.startsWith("child-") }.sortedBy {
            when (it.name) { "reservation" -> 3; "manifest.json" -> 2; else -> 1 }
        }
        for (entry in bodies + metadata) {
            if (entry == metadata.firstOrNull()) {
                files.sync(dir)
                releaseFault(ReleaseBoundary.BODIES_SYNCED, dir.name)
            }
            val after = inventoryDigest(dir, entry.name)
            writeControl(tombstone, tombstoneBytes(StagingTombstone(reservation, evidence, digest, after)))
            Files.delete(entry.toPath())
            releaseFault(when {
                entry.name.startsWith("child-") -> ReleaseBoundary.BODY_REMOVED
                entry.name == "manifest.json" -> ReleaseBoundary.MANIFEST_REMOVED
                else -> ReleaseBoundary.METADATA_REMOVED
            }, dir.name)
            files.sync(dir)
            digest = after
        }
        files.sync(dir)
        Files.delete(dir.toPath())
        releaseFault(ReleaseBoundary.GENERATION_REMOVED, dir.name)
        files.sync(root)
        releaseFault(ReleaseBoundary.PARENT_SYNCED, dir.name)
        Files.deleteIfExists(File(root, "staging-tombstone.tmp").toPath())
        Files.delete(tombstone.toPath())
        files.sync(root)
    }

    internal class StagingAttempt internal constructor(internal val owner: ChunkOutbox, internal val generation: String)

    private fun isControl(file: File): Boolean = file.parentFile == root &&
        (file.name.removeSuffix(".tmp") in setOf("active", "active-proof", "previous", "backpressure", "staging-tombstone") ||
            file.name.matches(Regex("receipt-[a-zA-Z0-9-]{1,100}\\.json(\\.tmp)?")))

    fun controlBytes(): Long = if (!root.exists()) 0 else root.listFiles()!!.filter { it.isFile && isControl(it) }.sumOf { it.length() }

    private fun writeControl(file: File, bytes: ByteArray, replace: Boolean = true) {
        require(isControl(file) && bytes.size <= CONTROL_RECORD_BYTES)
        if (controlBytes() + bytes.size > OUTBOX_CONTROL_RESERVE_BYTES ||
            retainedBytes() + controlBytes() + bytes.size > OUTBOX_HARD_QUOTA_BYTES) throw IOException("CONTROL_BUDGET")
        files.write(file, bytes, replace)
    }

    fun retainedBytes(): Long = if (!root.exists()) 0 else root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile && !isControl(it) }
        .fold(0L) { total, file -> Math.addExact(total, Files.size(file.toPath())) }

    private data class Receipt(
        val generation: String, val fingerprint: String, val endpointHash: String,
        val targets: Map<String, String>, val chunkCount: Int, val totalBytes: Long,
        val contentHash: String, val completedAt: Long, val manifestHash: String,
    )

    private fun receiptFile(id: String) = File(root, "receipt-$id.json")
    private fun receiptBytes(receipt: Receipt): ByteArray = with(receipt) {
        JSONObject().apply {
            put("format", "health-bridge-completed-v1"); put("generation", generation)
            put("fingerprint", fingerprint); put("endpointHash", endpointHash)
            put("targets", JSONObject(targets)); put("chunkCount", chunkCount)
            put("totalBytes", totalBytes); put("contentHash", contentHash)
            put("completedAt", completedAt); put("manifestHash", manifestHash)
        }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= CONTROL_RECORD_BYTES) }
    }

    private fun readReceipt(file: File): Receipt? = try {
        require(file.isFile && file.length() <= CONTROL_RECORD_BYTES)
        val j = JSONObject(file.readText())
        require(j.getString("format") == "health-bridge-completed-v1" && j.length() == 10)
        val id = j.getString("generation")
        generation(id)
        require(file.name == receiptFile(id).name)
        val targets = j.getJSONObject("targets").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        require(targets.isNotEmpty() && targets.size <= 6 && targets.keys.all { it in freshTypeNames })
        require(targets.values.all { it.length <= 40 && runCatching { java.time.Instant.parse(it) }.isSuccess })
        val receipt = Receipt(id, j.getString("fingerprint"), j.getString("endpointHash"), targets,
            j.getInt("chunkCount"), j.getLong("totalBytes"), j.getString("contentHash"),
            j.getLong("completedAt"), j.getString("manifestHash"))
        require(listOf(receipt.fingerprint, receipt.endpointHash, receipt.contentHash, receipt.manifestHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(receipt.chunkCount in 1..100 && receipt.totalBytes >= 0 && receipt.completedAt >= 0)
        receipt
    } catch (_: Exception) { null }

    private fun referencedGenerations(): Set<String> {
        val ids = mutableSetOf<String>()
        for (name in listOf("active", "active.tmp", "active-proof", "active-proof.tmp", "previous", "previous.tmp")) {
            val pointer = File(root, name)
            if (!pointer.exists()) continue
            require(pointer.isFile && pointer.length() <= CONTROL_RECORD_BYTES)
            val lines = pointer.readLines()
            ids += if (name.removeSuffix(".tmp") == "active") pointer.readText() else lines.getOrNull(1) ?: error("UNKNOWN_POINTER")
        }
        return ids
    }

    private fun recognisedMetadata(dir: File, id: String, fingerprint: String, current: ChunkManifest? = null): Boolean = try {
        val publication = File(dir, "published.json")
        if (publication.exists()) {
            require(publication.isFile && publication.length() <= FreshGenerationLimits().manifestBytes)
            val initial = decode(publication.readText())
            require(initial.generation == id && initial.fingerprint == fingerprint && initial.phase == ChunkPhase.STAGE && initial.reason == ChunkReason.NONE)
            require(initial.fingerprint == parentFingerprint(initial.endpointHash, initial.recordIds, initial.recordHashes, initial.targets))
            require(initial.children.none { it.acked || it.sentAt != null || it.ackedAt != null })
            if (current != null) require(initial.children.map { listOf(it.index, it.batchId, it.bodySha, it.count) } ==
                current.children.map { listOf(it.index, it.batchId, it.bodySha, it.count) })
        }
        val reservation = File(dir, "reservation")
        if (reservation.exists()) {
            require(reservation.isFile && reservation.length() <= 256)
            readReservation(reservation, id)
        }
        true
    } catch (_: Exception) { false }

    private fun completedManifest(dir: File): ChunkManifest? = try {
        require(dir.isDirectory && generation(dir.name) == dir)
        val file = File(dir, "manifest.json")
        require(file.isFile && file.length() <= FreshGenerationLimits().manifestBytes)
        val m = decode(file.readText())
        require(m.generation == dir.name && m.phase == ChunkPhase.DONE && m.reason == ChunkReason.NONE)
        require(m.targets.isNotEmpty() && m.targets.size <= 6 && m.targets.keys.all { it in freshTypeNames })
        require(m.targets.values.all { it.length <= 40 && runCatching { java.time.Instant.parse(it) }.isSuccess })
        require(m.fingerprint == parentFingerprint(m.endpointHash, m.recordIds, m.recordHashes, m.targets))
        require(m.endpointHash.matches(Regex("[0-9a-f]{64}")) && m.recordIds.size == m.recordHashes.size)
        require(m.children.size in 1..100 && m.children.map { it.index } == m.children.indices.toList())
        require(m.children.map { it.batchId }.distinct().size == m.children.size)
        require(m.children.sumOf { it.count } == m.recordIds.size && m.updatedAt >= 0)
        require(m.children.all { it.acked && it.sentAt != null && it.ackedAt != null && receiverBatchId.matches(it.batchId) })
        val allowed = setOf("manifest.json", "published.json", "reservation") + m.children.map { "child-${it.index}.json" }
        require(dir.listFiles()!!.all { it.isFile && it.name in allowed })
        require(recognisedMetadata(dir, m.generation, m.fingerprint, m))
        m
    } catch (_: Exception) { null }

    private fun makeReceipt(m: ChunkManifest): Receipt {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        m.children.forEach { child ->
            val file = bodyFile(m, child.index)
            require(file.isFile && file.length() <= FRESH_RECORD_BYTES)
            require(sha256(file.readBytes()) == child.bodySha)
            bytes = Math.addExact(bytes, file.length())
            digest.update(child.bodySha.toByteArray(Charsets.US_ASCII))
        }
        val contentHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return Receipt(m.generation, m.fingerprint, m.endpointHash, m.targets, m.children.size, bytes,
            contentHash, m.updatedAt, sha256(File(generation(m.generation), "manifest.json").readBytes()))
    }

    private fun admitReceipt(receipt: Receipt): Boolean {
        val target = receiptFile(receipt.generation)
        if (target.exists()) return readReceipt(target) == receipt
        val records = root.listFiles()!!.filter { it.name.matches(Regex("receipt-[a-zA-Z0-9-]{1,100}\\.json")) }
        if (records.size >= COMPLETED_RECEIPT_COUNT) {
            val referenced = referencedGenerations()
            val old = records.mapNotNull { file -> readReceipt(file)?.let { file to it } }
                .filter { (_, r) -> !generation(r.generation).exists() && r.generation !in referenced }
                .minWithOrNull(compareBy<Pair<File, Receipt>> { it.second.completedAt }.thenBy { it.second.generation }) ?: return false
            Files.delete(old.first.toPath())
            files.sync(root)
        }
        writeControl(target, receiptBytes(receipt), replace = false)
        return true
    }

    private suspend fun release(receipt: Receipt, cursors: ChunkCursorStore) {
        val dir = generation(receipt.generation)
        if (!dir.exists() || receipt.generation in referencedGenerations()) return
        if (cursors.hasLegacyPending() || !cursors.committed(receipt.targets)) return
        val entries = dir.listFiles() ?: throw IOException("DIRECTORY_LIST_FAILED")
        val allowed = setOf("manifest.json", "published.json", "reservation") + (0 until receipt.chunkCount).map { "child-$it.json" }
        if (entries.any { !it.isFile || it.name !in allowed }) return
        val manifestFile = File(dir, "manifest.json")
        val m = if (manifestFile.exists()) completedManifest(dir) ?: return else null
        if (m != null && (sha256(manifestFile.readBytes()) != receipt.manifestHash || m.fingerprint != receipt.fingerprint ||
                m.targets != receipt.targets || m.children.size != receipt.chunkCount)) return
        if (m == null && entries.any { it.name.startsWith("child-") }) return
        if (!recognisedMetadata(dir, receipt.generation, receipt.fingerprint, m)) return
        // Fence the already-written receipt again on restart before releasing a single body.
        files.sync(root)
        releaseFault(ReleaseBoundary.TOMBSTONE_DURABLE, receipt.generation)
        if (m != null) for (child in m.children) {
            val body = bodyFile(m, child.index)
            if (body.exists()) {
                if (sha256(body.readBytes()) != child.bodySha) return
                Files.delete(body.toPath())
                releaseFault(ReleaseBoundary.BODY_REMOVED, receipt.generation)
            }
        }
        files.sync(dir)
        releaseFault(ReleaseBoundary.BODIES_SYNCED, receipt.generation)
        // The manifest survives until every body is gone. A receipt alone recovers metadata removal.
        for (name in listOf("published.json", "reservation", "manifest.json")) {
            Files.deleteIfExists(File(dir, name).toPath())
            releaseFault(if (name == "manifest.json") ReleaseBoundary.MANIFEST_REMOVED else ReleaseBoundary.METADATA_REMOVED, receipt.generation)
        }
        Files.delete(dir.toPath())
        releaseFault(ReleaseBoundary.GENERATION_REMOVED, receipt.generation)
        files.sync(root)
        releaseFault(ReleaseBoundary.PARENT_SYNCED, receipt.generation)
    }

    suspend fun reclaimDone(cursors: ChunkCursorStore) {
        if (!root.isDirectory || cursors.hasLegacyPending()) return
        // A receipt is durable release authority, never a reason to collect or send a body again.
        val receipts = root.listFiles()!!.mapNotNull(::readReceipt)
        for (receipt in receipts.sortedWith(compareBy<Receipt> { it.completedAt }.thenBy { it.generation })) release(receipt, cursors)
        val candidates = root.listFiles()!!.mapNotNull(::completedManifest)
        val proven = candidates.filter { cursors.committed(it.targets) }
            .sortedWith(compareByDescending<ChunkManifest> { it.updatedAt }.thenByDescending { it.generation })
        for (m in proven.drop(RETAINED_DONE_GENERATIONS)) {
            if (m.generation in referencedGenerations() || cursors.hasLegacyPending()) continue
            val receipt = try { makeReceipt(m) } catch (_: Exception) { continue }
            if (admitReceipt(receipt)) release(receipt, cursors)
        }
    }

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
