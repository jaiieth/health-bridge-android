package dev.healthbridge

interface SyncScheduler {
    fun schedule(minutes: Long)
    fun cancel()
}

class SyncControl(private val settings: Settings, private val scheduler: SyncScheduler) {
    fun setEnabled(enabled: Boolean): Boolean {
        settings.syncEnabled = enabled
        if (settings.syncEnabled != enabled) return false
        if (enabled) scheduler.schedule(settings.minutes) else scheduler.cancel()
        return true
    }

    fun save(endpoint: String, token: String, minutes: Long) {
        val intervalChanged = settings.minutes != minutes
        settings.save(endpoint, token, minutes)
        if (settings.syncEnabled && intervalChanged) scheduler.schedule(minutes)
    }
}
