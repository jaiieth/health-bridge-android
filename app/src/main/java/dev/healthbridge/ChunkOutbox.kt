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

private const val STAGE_HEADROOM_BYTES = 1_048_576L

internal enum class ChunkPhase { PLAN, STAGE, SEND, ACK, COMMIT, DONE, PAUSED, BLOCKED }
internal enum class ChunkReason { NONE, FLAG_OFF, LEGACY_PENDING, INVALID_TIMESTAMP, UNKNOWN_ACCEPTANCE, INVALID_ACK, HTTP_400, HTTP_409, HTTP_413, HTTP_BLOCKED, HTTP_RETRY, IO_FAILURE, CORRUPT_OUTBOX, ENDPOINT_CHANGED, OVERSIZED }
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
        val dir = generation(manifest.generation)
        check(!dir.exists())
        val manifestBytes = encode(manifest).toByteArray(Charsets.UTF_8)
        val activeBytes = manifest.generation.toByteArray(Charsets.UTF_8)
        val required = try {
            (plan?.children ?: emptyList()).fold(STAGE_HEADROOM_BYTES + manifestBytes.size + activeBytes.size) { total, child ->
                Math.addExact(total, child.body.size.toLong())
            }
        } catch (e: ArithmeticException) { throw IOException("STAGE_SIZE_OVERFLOW", e) }
        val available = try {
            val directory = generateSequence(root.absoluteFile) { it.parentFile }.firstOrNull { it.isDirectory }
                ?: throw IOException("FREE_SPACE_UNAVAILABLE")
            usableSpace(directory)
        } catch (e: SecurityException) { throw IOException("FREE_SPACE_UNAVAILABLE", e) }
        if (available < required) throw IOException("INSUFFICIENT_FREE_SPACE")
        files.directory(root)
        files.directory(dir)
        plan?.children?.forEachIndexed { index, child -> files.write(bodyFile(manifest, index), child.body, replace = false) }
        files.write(File(dir, "manifest.json"), manifestBytes)
        files.write(File(root, "active"), activeBytes)
    }

    fun save(manifest: ChunkManifest) {
        files.write(File(generation(manifest.generation), "manifest.json"), encode(manifest).toByteArray(Charsets.UTF_8))
    }

    fun beginStreaming(generation: String) {
        val dir = generation(generation)
        check(!dir.exists())
        preflight(STAGE_HEADROOM_BYTES)
        files.directory(root)
        files.directory(dir)
    }

    fun retainedBytes(): Long = if (!root.exists()) 0 else root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile }
        .fold(0L) { total, file -> Math.addExact(total, Files.size(file.toPath())) }

    fun stageBody(generation: String, index: Int, bytes: ByteArray, size: Int) {
        require(index in 0 until 100 && size in 0..524_288 && size <= bytes.size)
        preflight(STAGE_HEADROOM_BYTES + size)
        files.write(File(generation(generation), "child-$index.json"), bytes, replace = false, size = size)
    }

    fun publishStreaming(manifest: ChunkManifest) {
        val bytes = encode(manifest).toByteArray(Charsets.UTF_8)
        val active = manifest.generation.toByteArray(Charsets.UTF_8)
        preflight(STAGE_HEADROOM_BYTES + bytes.size + active.size)
        files.write(File(generation(manifest.generation), "manifest.json"), bytes)
        files.write(File(root, "active"), active)
    }

    private fun preflight(required: Long) {
        val directory = generateSequence(root.absoluteFile) { it.parentFile }.firstOrNull { it.isDirectory }
            ?: throw IOException("FREE_SPACE_UNAVAILABLE")
        val available = try { usableSpace(directory) }
        catch (e: SecurityException) { throw IOException("FREE_SPACE_UNAVAILABLE", e) }
        if (available < required) throw IOException("INSUFFICIENT_FREE_SPACE")
    }

    fun load(): ChunkManifest? {
        val active = File(root, "active")
        if (!active.exists()) return null
        val dir = generation(active.readText(Charsets.UTF_8))
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
        if (manifest.phase != ChunkPhase.BLOCKED || manifest.reason !in setOf(ChunkReason.OVERSIZED, ChunkReason.INVALID_TIMESTAMP)) {
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

    fun status(): ChunkStatus? = try { load()?.status() }
        catch (_: IOException) { ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.IO_FAILURE) }
        catch (_: Exception) { ChunkStatus(ChunkPhase.PAUSED, reason = ChunkReason.CORRUPT_OUTBOX) }

    private fun encode(m: ChunkManifest): String {
        require(manifestEncodedBytes(m) <= FreshGenerationLimits().manifestBytes) { "MANIFEST_BYTE_BUDGET" }
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
