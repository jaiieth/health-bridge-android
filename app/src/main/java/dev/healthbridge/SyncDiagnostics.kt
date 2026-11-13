package dev.healthbridge

import java.io.IOException

/**
 * Last-failure cause for display only. Never influences delivery, cursors or the outbox.
 * Records a bounded cause code, the failing operation and a sanitized error detail.
 * It never stores tokens, health values, file paths or free-form text from the network.
 */
internal object SyncDiagnostics {
    const val IO = "IO"
    const val PARSE = "PARSE"
    const val STATE = "STATE"
    const val QUOTA = "QUOTA"
    const val UNKNOWN = "UNKNOWN"

    private const val MAX_DETAIL = 64

    @Volatile private var value: String? = null

    fun begin() {
        value = null
    }

    fun last(): String? = value

    fun record(error: Throwable?, at: String) {
        value = describe(error, at)
    }

    fun note(note: String) {
        value = listOfNotNull(value, note).joinToString("; ")
    }

    fun code(error: Throwable?): String = when {
        error == null -> UNKNOWN
        error is ChunkBudgetExceeded -> QUOTA
        error is IOException -> IO
        error is org.json.JSONException -> PARSE
        isErrnoFailure(error) -> IO
        error is IllegalStateException || error is IllegalArgumentException -> STATE
        error is NullPointerException || error is IndexOutOfBoundsException -> STATE
        error is UnsupportedOperationException -> STATE
        else -> UNKNOWN
    }

    fun detail(error: Throwable?): String {
        if (error == null) return "no error captured"
        val type = error.javaClass.simpleName.ifBlank { "UnknownError" }
        val message = sanitize(error.message)
        return if (message.isEmpty()) type else "$type $message"
    }

    fun describe(error: Throwable?, at: String): String =
        "${code(error)} · $at · ${detail(error)}"

    /** Keeps only short printable detail without paths, URLs, separators or control characters. */
    fun sanitize(raw: String?): String {
        val text = raw?.filter { it.code in 32..126 }?.trim() ?: return ""
        if (text.isEmpty()) return ""
        if (text.any { it == '/' || it == '\\' || it == '@' }) return ""
        val collapsed = text.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed.take(MAX_DETAIL)
    }

    /** Android signals platform failures through android.system.ErrnoException. */
    private fun isErrnoFailure(error: Throwable): Boolean =
        error.javaClass.name == "android.system.ErrnoException" || error.cause?.let { isErrnoFailure(it) } == true

    internal fun reset() {
        value = null
    }
}

/** Directory fsync capability. Devices that reject it keep syncing with reduced durability. */
internal object DirectoryDurability {
    // EPERM, EINVAL, ENOSYS, ENOTSUP/EOPNOTSUPP: the filesystem cannot fsync a directory handle.
    private val unsupportedErrnos = setOf(1, 22, 38, 95)

    @Volatile var degraded: Boolean = false
        private set

    @Volatile var signal: String? = null
        private set

    fun isUnsupportedErrno(errno: Int): Boolean = errno in unsupportedErrnos

    fun label(errno: Int): String = when (errno) {
        1 -> "EPERM"
        5 -> "EIO"
        9 -> "EBADF"
        22 -> "EINVAL"
        28 -> "ENOSPC"
        30 -> "EROFS"
        38 -> "ENOSYS"
        95 -> "ENOTSUP"
        else -> "errno-$errno"
    }

    fun degrade(errno: Int) {
        degraded = true
        signal = "directory fsync ${label(errno)} rejected by this device"
    }

    internal fun reset() {
        degraded = false
        signal = null
    }
}
