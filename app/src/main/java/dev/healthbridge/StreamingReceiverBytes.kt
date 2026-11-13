package dev.healthbridge

internal fun productionMappedRecordBytes(record: ChunkRecord): Long {
    require(record.json.length <= FRESH_RECORD_BYTES)
    val parsed = EcmaJson.parse(record.json) as? Map<*, *> ?: error("INVALID_RECORD")
    fun text(value: Any?) = value as? String ?: error("INVALID_RECORD")
    val source = parsed["source"] as? Map<*, *> ?: error("INVALID_RECORD")
    val id = text(parsed["recordId"])
    require(id == record.recordId) { "INVALID_RECORD" }
    val type = text(parsed["type"])
    val origin = text(source["package"])
    val modified = receiverTimestampKey(parsed["lastModifiedTime"])
    val counter = JsonByteCounter()
    counter.ascii("{\"type\":"); counter.quoted(type)
    counter.ascii(",\"source\":"); counter.quoted(origin)
    counter.ascii(",\"id\":"); counter.quoted(id)
    counter.ascii(",\"modified\":"); counter.quoted(modified)
    counter.ascii(",\"payload\":")
    val payload = JsonByteCounter(escaped = true)
    payload.value(parsed)
    return counter.bytes + 2 + payload.bytes + 1
}

private class JsonByteCounter(private val escaped: Boolean = false) {
    var bytes = 0L
        private set
    fun ascii(text: String) { text.forEach { ascii(it) } }
    private fun ascii(c: Char) { bytes += if (escaped && (c == '"' || c == '\\')) 2 else 1 }
    fun quoted(text: String) {
        ascii('"')
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            when (c) {
                '"' -> ascii("\\\"")
                '\\' -> ascii("\\\\")
                '\b' -> ascii("\\b")
                '\u000c' -> ascii("\\f")
                '\n' -> ascii("\\n")
                '\r' -> ascii("\\r")
                '\t' -> ascii("\\t")
                else -> when {
                    c < ' ' || c.isSurrogate() && !(c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate()) -> {
                        ascii("\\u")
                        repeat(4) { ascii('0') }
                    }
                    c.code < 128 -> ascii(c)
                    c.code < 2048 -> bytes += 2
                    c.isHighSurrogate() -> { bytes += 4; index++ }
                    else -> bytes += 3
                }
            }
        }
        ascii('"')
    }
    fun value(value: Any?) {
        when (value) {
            null -> ascii("null")
            is String -> quoted(value)
            is Boolean -> ascii(value.toString())
            is Number -> ascii(ecmaNumber(value.toDouble()))
            is List<*> -> {
                ascii('[')
                value.forEachIndexed { index, item -> if (index > 0) ascii(','); value(item) }
                ascii(']')
            }
            is Map<*, *> -> {
                fun arrayIndex(key: String): Long? = key.toLongOrNull()?.takeIf {
                    it >= 0 && it < 4_294_967_295L && it.toString() == key
                }
                val keys = value.keys.map { it as String }
                val numeric = keys.mapNotNull { key -> arrayIndex(key)?.let { key to it } }.sortedBy { it.second }
                ascii('{')
                var first = true
                fun field(key: String) {
                    if (!first) ascii(',')
                    first = false
                    quoted(key); ascii(':'); value(value[key])
                }
                numeric.forEach { field(it.first) }
                keys.forEach { if (arrayIndex(it) == null) field(it) }
                ascii('}')
            }
            else -> error("UNSUPPORTED_JSON_VALUE")
        }
    }
}
