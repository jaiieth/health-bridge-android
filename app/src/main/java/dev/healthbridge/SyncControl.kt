package dev.healthbridge

interface SyncScheduler {
    fun schedule(minutes: Long)
    fun cancel()
}

class SyncControl(
    private val settings: Settings,
    private val scheduler: SyncScheduler,
    private val freshChunkingEnabled: Boolean = ChunkContracts.freshChunkingEnabled,
) {
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !freshChunkingEnabled) return false
        settings.syncEnabled = enabled
        if (settings.syncEnabled != enabled) return false
        if (enabled) scheduler.schedule(settings.minutes) else scheduler.cancel()
        return true
    }

    fun enforceDisabled(): Boolean {
        if (freshChunkingEnabled) return true
        val persistedOff = runCatching { settings.persistSyncDisabled() }.getOrDefault(false)
        val cancelled = runCatching { scheduler.cancel() }.isSuccess
        return persistedOff && cancelled
    }

    fun save(endpoint: String, token: String, minutes: Long) {
        if (!freshChunkingEnabled) {
            enforceDisabled()
            settings.save(endpoint, token, minutes)
            return
        }
        val intervalChanged = settings.minutes != minutes
        settings.save(endpoint, token, minutes)
        if (settings.syncEnabled && intervalChanged) scheduler.schedule(minutes)
    }
}
