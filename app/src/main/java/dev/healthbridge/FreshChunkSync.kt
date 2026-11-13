package dev.healthbridge

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.time.Instant

internal fun chunkOutbox(context: Context): ChunkOutbox = ChunkOutbox(
    File(context.filesDir, "fresh-sync-chunks"),
    DurableFiles(syncDirectory = { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }),
)

internal suspend fun runFreshChunkSync(context: Context, store: Store, settings: Settings): ChunkStatus {
    val cursorStore = object : ChunkCursorStore {
        override suspend fun hasLegacyPending() = store.pending() != null
        override suspend fun commit(targets: Map<String, String>) = store.commitChunkCursors(targets.map { Cursor(it.key, it.value) })
    }
    val runner = ChunkSync(chunkOutbox(context), cursorStore, send = { batchId, body ->
        Transport.validateUrl(settings.endpoint)
        require(settings.token.isNotBlank())
        Transport.sendDetailed(settings.endpoint, settings.token, batchId, body)
    })
    val result = runner.run(settings.syncEnabled, settings.endpoint, ChunkContracts.verifiedMapping) {
        Transport.validateUrl(settings.endpoint)
        require(settings.token.isNotBlank())
        val reader = HealthReader(context)
        check(reader.hasPermissions() && reader.canCollect())
        val (records, targets) = reader.read(store.cursors().associate { it.type to it.until }, Instant.now())
        FreshChunkInput(List(records.length()) { index -> records.getJSONObject(index).let {
            ChunkRecord(it.getString("recordId"), it.toString())
        } }, targets.keys().asSequence().associateWith { targets.getString(it) })
    }
    val old = store.status() ?: SyncStatus()
    store.status(old.copy(
        lastSuccess = if (result.phase == ChunkPhase.DONE) Instant.now().toString() else old.lastSuccess,
        result = "Health sync ${result.phase}: ${result.index}/${result.total}; ${result.reason}",
    ))
    return result
}
