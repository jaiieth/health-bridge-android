package dev.healthbridge

/** Includes quotes; surrogate pairs contribute four UTF-8 bytes. No encoded copy is allocated. */
internal fun manifestStringBytes(value: String): Long {
    var bytes = 2L
    var index = 0
    while (index < value.length) {
        val c = value[index++]
        bytes += when {
            c.code < 0x20 || c.code in 0x80..0x9f || c.code in 0x2000..0x20ff -> 6
            c == '"' || c == '\\' || c == '/' -> 2
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> { index++; 4 }
            // Six also covers JSON implementations that escape isolated surrogates.
            c.isSurrogate() -> 6
            else -> 3
        }
    }
    return bytes
}

/** Two array entries, including their commas, even for the first record. */
internal fun manifestRecordBytes(id: String, hash: String): Long =
    manifestStringBytes(id) + manifestStringBytes(hash) + 2

/** 4096 covers fixed keys, punctuation, numeric/enum fields and fingerprint. */
internal fun manifestBaseBytes(generation: String, endpoint: String, targets: Map<String, String>): Long =
    4096L + manifestStringBytes(generation) + manifestStringBytes(endpoint) +
        targets.entries.sumOf { manifestStringBytes(it.key) + manifestStringBytes(it.value) + 2 }

internal fun manifestEncodedBytes(manifest: ChunkManifest): Long = with(manifest) {
    manifestBaseBytes(generation, endpointHash, targets) + manifestStringBytes(fingerprint) +
        recordIds.sumOf { manifestStringBytes(it) + 1 } +
        recordHashes.sumOf { manifestStringBytes(it) + 1 } +
        (blockedRecordId?.let(::manifestStringBytes) ?: 4) +
        children.sumOf { 256L + manifestStringBytes(it.batchId) + manifestStringBytes(it.bodySha) }
}
